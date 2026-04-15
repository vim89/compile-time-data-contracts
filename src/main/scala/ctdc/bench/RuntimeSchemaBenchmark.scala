package ctdc.bench

import ctdc.ContractsCore.SchemaPolicy
import ctdc.SparkCore.{PolicyRuntime, SparkSchema}
import org.apache.spark.sql.types.{DataType, StructField, StructType}

import java.nio.file.{Files, Path}
import java.util.Locale

object RuntimeSchemaBenchmark:

  private final case class BenchmarkCase(name: String, run: () => Boolean)
  private final case class BenchmarkResult(
      name: String,
      avgNsPerOp: Double,
      minNsPerOp: Double,
      maxNsPerOp: Double,
      measurementIterations: Int,
      opsPerIteration: Int
  )

  @volatile private var blackhole: Long = 0L

  final case class Geo(lat: Double, lon: Double)
  final case class Address(street: String, city: String, zip: Option[Int], geo: Geo)
  final case class Event(kind: String, at: Long, tags: List[Option[String]], attrs: Map[String, String])
  final case class Contract(
      id: Long,
      email: String,
      age: Option[Int],
      address: Address,
      events: List[Event],
      metrics: Map[String, Option[Int]]
  )

  def main(args: Array[String]): Unit =
    val outputPath            = args.headOption.map(Path.of(_))
    val warmupIterations      = args.lift(1).map(_.toInt).getOrElse(3)
    val measurementIterations = args.lift(2).map(_.toInt).getOrElse(8)
    val opsPerIteration       = args.lift(3).map(_.toInt).getOrElse(250000)

    val exactRuntime      = summon[PolicyRuntime[SchemaPolicy.Exact.type]]
    val byPositionRuntime = summon[PolicyRuntime[SchemaPolicy.ExactByPosition.type]]

    val expected        = summon[SparkSchema[Contract]].struct
    val renamedExpected = renameStruct(expected, "col")

    val cases = List(
      BenchmarkCase(
        "custom_exact_unordered_match",
        () => exactRuntime.ok(expected, expected)
      ),
      BenchmarkCase(
        "spark_equals_ignore_case_and_nullability_match",
        () => DataType.equalsIgnoreCaseAndNullability(expected, expected)
      ),
      BenchmarkCase(
        "custom_exact_by_position_match",
        () => byPositionRuntime.ok(renamedExpected, expected)
      ),
      BenchmarkCase(
        "spark_equals_structurally_match",
        () => DataType.equalsStructurally(renamedExpected, expected, true)
      )
    )

    val results =
      cases.map(runBenchmark(_, warmupIterations, measurementIterations, opsPerIteration))

    val rendered = renderCsv(results)
    outputPath.foreach { path =>
      Files.createDirectories(path.getParent)
      Files.writeString(path, rendered)
    }

    println("benchmark,avg_ns_per_op,min_ns_per_op,max_ns_per_op,measurement_iterations,ops_per_iteration")
    println(rendered)

  private def runBenchmark(
      bench: BenchmarkCase,
      warmupIterations: Int,
      measurementIterations: Int,
      opsPerIteration: Int
  ): BenchmarkResult =
    var warmup = 0
    while warmup < warmupIterations do
      runBatch(bench.run, opsPerIteration)
      warmup += 1

    val measurements = Array.ofDim[Long](measurementIterations)

    var idx = 0
    while idx < measurementIterations do
      measurements(idx) = runBatch(bench.run, opsPerIteration)
      idx += 1

    val nsPerOp = measurements.map(_.toDouble / opsPerIteration.toDouble)

    BenchmarkResult(
      name = bench.name,
      avgNsPerOp = nsPerOp.sum / nsPerOp.length.toDouble,
      minNsPerOp = nsPerOp.min,
      maxNsPerOp = nsPerOp.max,
      measurementIterations = measurementIterations,
      opsPerIteration = opsPerIteration
    )

  private def runBatch(run: () => Boolean, opsPerIteration: Int): Long =
    var matches = 0L
    var i       = 0
    val start   = System.nanoTime()

    while i < opsPerIteration do
      if run() then matches += 1
      i += 1

    val elapsed = System.nanoTime() - start
    blackhole = blackhole ^ matches
    elapsed

  private def renderCsv(results: List[BenchmarkResult]): String =
    results
      .map { result =>
        f"${result.name},${result.avgNsPerOp}%.2f,${result.minNsPerOp}%.2f,${result.maxNsPerOp}%.2f,${result.measurementIterations},${result.opsPerIteration}"
      }
      .mkString("\n")

  private def renameStruct(struct: StructType, prefix: String): StructType =
    StructType(
      struct.fields.zipWithIndex.map { case (field, index) =>
        StructField(
          name = s"${prefix}_$index",
          dataType = renameDataType(field.dataType, s"${prefix}_${index}"),
          nullable = field.nullable,
          metadata = field.metadata
        )
      }
    )

  private def renameDataType(dataType: org.apache.spark.sql.types.DataType, prefix: String): org.apache.spark.sql.types.DataType =
    dataType match
      case struct: StructType =>
        renameStruct(struct, prefix.toLowerCase(Locale.ROOT))
      case org.apache.spark.sql.types.ArrayType(elementType, containsNull) =>
        org.apache.spark.sql.types.ArrayType(renameDataType(elementType, s"${prefix}_elem"), containsNull)
      case org.apache.spark.sql.types.MapType(keyType, valueType, valueContainsNull) =>
        org.apache.spark.sql.types.MapType(
          renameDataType(keyType, s"${prefix}_key"),
          renameDataType(valueType, s"${prefix}_value"),
          valueContainsNull
        )
      case other =>
        other

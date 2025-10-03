package ctdc

import ctdc.ContractsCore.{CompileTime, SchemaPolicy}
import ctdc.ContractsCore.CompileTime.SchemaConforms
import ctdc.SparkCore.{PipelineBuilder, PolicyRuntime, SchemaCheck, SparkSchema, TypedIO, TypedSink, TypedSource}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.*

/**
 * POC examples to run This file keeps two sections:
 * 1> Compile-only evidence (summon the proof; toggle failures)
 * 2> A tiny CSV -> Parquet job that uses the contract schema at runtime
 */

object CtdcPoc {

  /**
   * 1> Compile-only: summon evidence or fail to compile
   */

  object CompileOnly {

    final case class ContractUser(id: Long, email: String, age: Option[Int] = None)

    final case class OutOk_Backward(id: Long, email: String, age: Option[Int], extra: String)

    final case class OutExact_Same(id: Long, email: String, age: Option[Int])

    // Backward: extras allowed; missing allowed only if optional/default.
    val ev1: SchemaConforms[OutOk_Backward, ContractUser, SchemaPolicy.Backward.type] = summon

    // Exact: no extras, no missing, types match strictly (unordered, CI).
    val ev2: SchemaConforms[OutExact_Same, ContractUser, SchemaPolicy.Exact.type] = summon

    // Deep / Nested

    final case class LineItem(sku: String, qty: Int, attrs: Map[String, String])

    final case class Address(street: String, zip: String)

    final case class OrderOut(
                               id: Long,
                               items: List[LineItem],
                               shipTo: Option[Address],
                               tags: Set[String]
                             )

    final case class OrderContract(
                                    id: Long,
                                    items: Seq[LineItem],
                                    shipTo: Option[Address],
                                    tags: Seq[String] = Nil
                                  )

    val evDeepOk: SchemaConforms[OrderOut, OrderContract, SchemaPolicy.Backward.type] = summon

    // Forward demo

    final case class FutureContract(id: Long, email: String, age: Option[Int], note: Option[String] = None)

    final case class ProducerForwardOK(id: Long, email: String, age: Option[Int])

    val evForwardOk: SchemaConforms[ProducerForwardOK, FutureContract, SchemaPolicy.Forward.type] = summon

    /**
     * Uncomment to see compile-time failures
     */
    // final case class ProducerNoAge(id: Long, email: String)
    // val evBackwardFail: SchemaConforms[ProducerNoAge, ContractUser, SchemaPolicy.Backward.type] = summon
    //
    // final case class ProducerTypes(a: Long, b: String)
    // final case class ContractTypes (a: Int,  b: String)
    // val evTypeFail: SchemaConforms[ProducerTypes, ContractTypes, SchemaPolicy.Exact.type] = summon
  }

  /** Demo: build a CSV -> (optional transform) -> Parquet pipeline with compile-time contracts.
   *
   * What this shows:
   * 1) We generate CSV input entirely in code (no external files).
   * 2) Pipeline A (noTransform): compile-time fuse validates Producer -> Contract under Backward.
   * 3) Pipeline B (transformAs[Next]): compile-time fuse validates declared Next -> Contract.
   * 4) Both paths also run a runtime pin mirrored to the same policy via Spark structural comparators.
   *
   * Run:
   * sbt "runMain ctdc.run"
   */

  object PipelineBuilderCsvParquetDemo {

    // Contracts and producers

    /** Sink contract: the target schema we promise to write. */
    final case class CustomerContract(id: Long, email: String, age: Option[Int] = None)

    /** Producer: imagine an upstream source adds an extra field `segment`. */
    final case class CustomerProducer(id: Long, email: String, age: Option[Int], segment: String)

    // If you want to see a compile-time failure on the noTransform path, swap the field order:
    // final case class BadProducer(email: String, id: Long, age: Option[Int], segment: String)
    // Above will fail under ExactByPosition, but we use Backward in the demo below.

    /** Declared "Next" schema after a transform (dropping `segment`). */
    final case class CustomerNext(id: Long, email: String, age: Option[Int])


    def demo()(using spark: SparkSession): Unit = {
      val inPath = "src/main/resources/data/input/customer.csv"
      val tmpOutA = "src/main/resources/data/output/ctdc_OutA" // pipeline A output (parquet)
      val tmpOutB = "src/main/resources/data/output/ctdc_OutB" // pipeline B output (parquet)

      import spark.implicits.*

      val expected = summon[ctdc.SparkCore.SparkSchema[CustomerProducer]].struct
      val df = spark.read.format("csv").option("header", "true").schema(expected).load(inPath)

      /**
       * 2> Build Pipeline A (no transform)
       * - Source contract = CustomerProducer
       * - Sink  contract  = CustomerContract
       * - Policy = Backward (extras allowed on producer)
       *
       * COMPILE-TIME FUSE:
       * addSink requires SchemaConforms[CurContract, R, P].
       * After addSource + noTransform, CurContract = CustomerProducer.
       * Therefore we must prove: CustomerProducer <= (Backward) -> CustomerContract (OK: producer has extra `segment`).
       */
      val srcA = TypedSource[CustomerProducer]("csv", inPath, Map("header" -> "true"))
      val sinkA = TypedSink[CustomerContract](s"$tmpOutA/parquet")

      // Uncomment to see a compile-time failure on Pipeline A (mismatch under Exact, for example):
      // val _boomA: SchemaConforms[CustomerProducer, CustomerContract, SchemaPolicy.Exact.type] = summon

      //val planA =
        //PipelineBuilder[CustomerProducer]("CSV -> Parquet A: noTransform, Backward")
          //.addSource(srcA)
          //.noTransform
          //.addSink[CustomerContract, SchemaPolicy.Exact.type](sinkA)
          //.build

      //val outA = planA(spark)
      //println(s"[A] rows = ${outA.count()}, schema:\n${outA.schema.treeString}")

      /**
       * 3> Build Pipeline B (explicit transform)
       * - We *declare* that after the transform, output schema is CustomerNext (drop `segment`).
       * - At the sink we must now prove: CustomerNext conforms to CustomerContract under policy P.
       * Using Exact here is fine (same names/types).
       */
      val srcB = TypedSource[CustomerProducer]("csv", inPath, Map("header" -> "true"))
      val sinkB = TypedSink[CustomerContract](s"$tmpOutB")

      val dropExtras: DataFrame => DataFrame =
        _.select($"id", $"email", $"age") // drop segment

      val planB =
        PipelineBuilder[CustomerContract]("CSV -> Parquet B: transformAs[CustomerNext], Exact")
          .addSource(srcB)
          .transformAs[CustomerNext]("drop segment")(dropExtras)
          .addSink[CustomerContract, SchemaPolicy.Exact.type](sinkB)
          .build

      val outB = planB(spark)
      println(s"[B] rows = ${outB.count()}, schema:\n${outB.schema.treeString}")

      /**
       * 4> Read back Parquet outputs to show they were written
       */
      // val checkA = spark.read.parquet(s"$tmpOutA")
      // println(s"[A] wrote ${checkA.count()} rows to $tmpOutA")
      val checkB = spark.read.parquet(s"$tmpOutB")
      println(s"[B] wrote ${checkB.count()} rows to $tmpOutB")
      checkB.show(false)
    }
  }

  // If you want to see a compile-time failure on the transform path, tweak a type, e.g.:
  // final case class BadNext(id: Long, email: String, age: Long) // <- wrong type, Int != Long

  /**
   * MAIN
   */
  @main def run(): Unit =
    implicit val spark: SparkSession =
      SparkSession.builder().appName("ctdc-pipeline-demo").master("local[*]").getOrCreate()
    try
      PipelineBuilderCsvParquetDemo.demo()
    finally
      spark.stop()
}

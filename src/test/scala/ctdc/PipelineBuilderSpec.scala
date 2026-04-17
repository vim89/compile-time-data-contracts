package ctdc

import ctdc.ContractsCore.SchemaPolicy
import ctdc.SparkCore.{PipelineBuilder, TypedSink, TypedSource}
import munit.FunSuite
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.*

import java.nio.file.Files

import scala.compiletime.testing.typeCheckErrors

class PipelineBuilderSpec extends FunSuite:

  private lazy val spark: SparkSession =
    SparkSession
      .builder()
      .appName("ctdc-pipeline-builder-spec")
      .master("local[1]")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .getOrCreate()

  override def afterAll(): Unit =
    try spark.stop()
    finally super.afterAll()

  private inline def assertTypeFails(inline code: String, expectedSnippets: String*): Unit =
    val errors = typeCheckErrors(code)
    assert(
      errors.nonEmpty,
      clues("Expected code to fail typechecking, but it compiled successfully.")
    )
    val rendered = errors.map(_.message).mkString("\n---\n")
    expectedSnippets.foreach { snippet =>
      assert(
        rendered.contains(snippet),
        clues(s"Expected error output to contain: $snippet", rendered)
      )
    }

  private def writeCsv(prefix: String, body: String): String =
    val dir = Files.createTempDirectory(prefix)
    val csv = dir.resolve("data.csv")
    Files.writeString(csv, body)
    dir.toString

  private def writeCustomerCsv(): String =
    writeCsv(
      "ctdc-builder-in",
      """id,email,age,segment
        |1,a@b.com,21,S
        |2,b@c.com,,L
        |""".stripMargin
    )

  test("PipelineBuilder addSink surfaces compile-time contract drift") {
    assertTypeFails(
      """
        import ctdc.ContractsCore.SchemaPolicy
        import ctdc.SparkCore.*

        final case class Contract(id: Long, email: String)
        final case class Producer(id: Long, email: String, segment: String)

        val src  = TypedSource[Producer]("csv", "/tmp/in", Map("header" -> "true"))
        val sink = TypedSink[Contract]("/tmp/out")

        PipelineBuilder[Contract]("exact-extra")
          .addSource(src)
          .noTransform
          .addSink[Contract, SchemaPolicy.Exact.type](sink)
      """,
      "parameter ev1 of method addSink",
      "SchemaConforms[Producer, Contract"
    )
  }

  test("[A5/D8] PipelineBuilder addSource cannot reset the builder after a transform") {
    assertTypeFails(
      """
        import ctdc.SparkCore.*
        import org.apache.spark.sql.DataFrame

        final case class SourceRow(id: Long)
        final case class NextRow(id: Long)

        val src1 = TypedSource[SourceRow]("csv", "/tmp/in1", Map("header" -> "true"))
        val src2 = TypedSource[SourceRow]("csv", "/tmp/in2", Map("header" -> "true"))

        PipelineBuilder[SourceRow]("builder-state")
          .addSource(src1)
          .transformAs[NextRow]("identity")((df: DataFrame) => df)
          .addSource(src2)
      """,
      "Cannot prove that",
      "Empty"
    )
  }

  test("PipelineBuilder writes when declared transform output and runtime output both match the sink policy") {
    import spark.implicits.*

    final case class CustomerContract(id: Long, email: String, age: Option[Int] = None)
    final case class CustomerProducer(id: Long, email: String, age: Option[Int], segment: String)
    final case class CustomerNext(id: Long, email: String, age: Option[Int])

    val src  = TypedSource[CustomerProducer]("csv", writeCustomerCsv(), Map("header" -> "true"))
    val out  = Files.createTempDirectory("ctdc-builder-out").toString
    val sink = TypedSink[CustomerContract](out)

    val plan =
      PipelineBuilder[CustomerContract]("pipeline-green")
        .addSource(src)
        .transformAs[CustomerNext]("drop segment")(_.select($"id", $"email", $"age"))
        .addSink[CustomerContract, SchemaPolicy.ExactByPosition.type](sink)
        .build

    val result  = plan(spark)
    val written = spark.read.parquet(out)

    assertEquals(result.columns.toList, List("id", "email", "age"))
    assertEquals(result.count(), 2L)
    assertEquals(written.columns.toList, List("id", "email", "age"))
    assertEquals(written.count(), 2L)
  }

  test("PipelineBuilder runtime sink pin rejects a transform that violates the chosen sink policy") {
    import spark.implicits.*

    final case class CustomerContract(id: Long, email: String, age: Option[Int] = None)
    final case class CustomerProducer(id: Long, email: String, age: Option[Int], segment: String)
    final case class CustomerNext(id: Long, email: String, age: Option[Int])

    val src  = TypedSource[CustomerProducer]("csv", writeCustomerCsv(), Map("header" -> "true"))
    val out  = Files.createTempDirectory("ctdc-builder-bad-out").toString
    val sink = TypedSink[CustomerContract](out)

    val plan =
      PipelineBuilder[CustomerContract]("pipeline-red")
        .addSource(src)
        .transformAs[CustomerNext]("reorder columns")(_.select($"email", $"id", $"age"))
        .addSink[CustomerContract, SchemaPolicy.ExactByPosition.type](sink)
        .build

    val ex = intercept[IllegalArgumentException] {
      plan(spark)
    }

    assert(clue(ex.getMessage).contains("Runtime schema mismatch"))
  }

  test("PipelineBuilder Backward sink allows source extras and missing defaulted contract fields without a transform") {
    final case class CustomerContract(id: Long, email: String, age: Option[Int] = None, region: String = "IN")
    final case class CustomerProducer(id: Long, email: String, segment: String)

    val src =
      TypedSource[CustomerProducer](
        "csv",
        writeCsv(
          "ctdc-builder-backward-in",
          """id,email,segment
            |1,a@b.com,S
            |2,b@c.com,L
            |""".stripMargin
        ),
        Map("header" -> "true")
      )
    val out  = Files.createTempDirectory("ctdc-builder-backward-out").toString
    val sink = TypedSink[CustomerContract](out)

    val plan =
      PipelineBuilder[CustomerContract]("pipeline-backward")
        .addSource(src)
        .noTransform
        .addSink[CustomerContract, SchemaPolicy.Backward.type](sink)
        .build

    val result  = plan(spark)
    val written = spark.read.parquet(out)

    assertEquals(result.columns.toList, List("id", "email", "segment"))
    assertEquals(result.count(), 2L)
    assertEquals(written.columns.toList, List("id", "email", "segment"))
    assertEquals(written.count(), 2L)
  }

  test("PipelineBuilder Forward sink allows a producer subset without a transform") {
    final case class CustomerContract(id: Long, email: String, age: Option[Int], region: String)
    final case class CustomerProducer(id: Long, email: String)

    val src =
      TypedSource[CustomerProducer](
        "csv",
        writeCsv(
          "ctdc-builder-forward-in",
          """id,email
            |1,a@b.com
            |2,b@c.com
            |""".stripMargin
        ),
        Map("header" -> "true")
      )
    val out  = Files.createTempDirectory("ctdc-builder-forward-out").toString
    val sink = TypedSink[CustomerContract](out)

    val plan =
      PipelineBuilder[CustomerContract]("pipeline-forward")
        .addSource(src)
        .noTransform
        .addSink[CustomerContract, SchemaPolicy.Forward.type](sink)
        .build

    val result  = plan(spark)
    val written = spark.read.parquet(out)

    assertEquals(result.columns.toList, List("id", "email"))
    assertEquals(result.count(), 2L)
    assertEquals(written.columns.toList, List("id", "email"))
    assertEquals(written.count(), 2L)
  }

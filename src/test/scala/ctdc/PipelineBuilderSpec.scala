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

  private def writeCustomerCsv(): String =
    val dir = Files.createTempDirectory("ctdc-builder-in")
    val csv = dir.resolve("customer.csv")
    Files.writeString(
      csv,
      """id,email,age,segment
        |1,a@b.com,21,S
        |2,b@c.com,,L
        |""".stripMargin
    )
    dir.toString

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

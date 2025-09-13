import org.apache.spark.sql.SparkSession

/**
 * Hello world: Quick apache spark setup using scala 3
 */

object HelloWorld:
  @main def run(): Unit =
    val spark = SparkSession.builder
      .appName("HelloWorld")
      .master("local[*]")
      .enableHiveSupport()
      .getOrCreate()

    import spark.implicits.*
    val df = List("hello", "world").toDF
    df.show()

    spark.stop

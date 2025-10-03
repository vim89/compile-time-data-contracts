import ctdc.MacrosDefine
import org.apache.spark.sql.SparkSession

import scala.quoted.{Expr, Quotes}

/**
 * Hello world: Quick apache spark setup using scala 3
 */

object HelloWorld:

  @main def run(): Unit =
    def increment(x: Int): Int = x + 1

    inline def inc(x: Int): Int = x + 1

    val aNumber = 3
    val four = increment(aNumber)
    val four_v2 = inc(aNumber)


    val firstMacroCall = MacrosDefine.firstMacro(42, "Scala")


/*    val spark = SparkSession.builder
      .appName("HelloWorld")
      .master("local[*]")
      .enableHiveSupport()
      .getOrCreate()

    import spark.implicits.*
    val df = List("hello", "world").toDF
    df.show()

    spark.stop*/


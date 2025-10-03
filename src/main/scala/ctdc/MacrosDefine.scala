package ctdc

import scala.quoted.{Expr, Quotes}

object MacrosDefine {
  inline def firstMacro(number: Int, string: String): String =
    ${ firstMacroImpl('number, 'string) }

  def firstMacroImpl(numExpr: Expr[Int], strExpr: Expr[String])(using Quotes): Expr[String] =
    Expr("This is my first macro")
}

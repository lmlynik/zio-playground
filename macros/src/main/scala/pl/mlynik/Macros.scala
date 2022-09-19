package pl.mlynik

import scala.compiletime.*
import scala.quoted.*
import scala.compiletime.ops.*

object Macros {
  def inspectCode(x: Expr[Any])(using Quotes): Expr[Any] =
    println(x.show)
    x

  inline def inspect(inline x: Any): Any = ${inspectCode('x)}

  def pow(x: Double, n: Int): Double =
    if n == 0 then 1 else x * pow(x, n - 1)

  def powerCode(
                 x: Expr[Double],
                 n: Expr[Int]
               )(using Quotes): Expr[Double] = {
    import quotes.reflect.*
    (x, n) match {
      case (Expr(base), Expr(exponent)) => Expr(pow(base, exponent))
      case (Expr(_), _) => report.errorAndAbort("Expected a known value for the exponent, but was " + n.show, n)
      case _ => report.errorAndAbort("Expected a known value for the base, but was " + x.show, x)
    }
  }

  inline def power(inline x: Double, inline n: Int) =
    ${debugPowerCode('x, 'n)}

  def debugPowerCode(
                      x: Expr[Double],
                      n: Expr[Int]
                    )(using Quotes): Expr[Double] =
    println(
      s"""powerCode
         |  x := ${x.show}
         |  n := ${n.show}""".stripMargin)
    val code = powerCode(x, n)
    println(s"  code := ${code.show}")
    code

}

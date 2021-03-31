package arithexpr
package arithmetic
package simplifier

import scala.language.implicitConversions

/**
 * Generic expression simplifier.
 */
object ExprSimplifier {

  /**
   * Simplify an expression.
   * @param expr The expression to simplify.
   * @return A simplified expression equivalent to expr or expr itself if it is already simplified.
   */
  def apply(expr: ArithExpr): ArithExpr with SimplifiedExpr = expr match {
    case e: SimplifiedExpr => e
    case _ => oneStep(expr)
  }

  def apply(aes: Seq[ArithExpr]): Seq[ArithExpr with SimplifiedExpr] = aes.map(apply)
  def apply(aes: List[ArithExpr]): List[ArithExpr with SimplifiedExpr] = aes.map(apply)

  def fixpoint(expr: ArithExpr with SimplifiedExpr,
               fuel: Int = 1_000,
               visited: Set[ArithExpr] = Set()): ArithExpr with SimplifiedExpr = {
    if (visited.contains(expr)) { println("WARNING: cycle detected"); return expr }
    if (fuel <= 0) { throw new Exception("fixpoint out of fuel") }
    val again = oneStep(expr.asInstanceOf[ArithExpr])
    if (expr == again) { expr } else { fixpoint(again, fuel - 1, visited + expr) }
  }

  def oneStep(expr: ArithExpr): ArithExpr with SimplifiedExpr = expr match {
    case ? => ?
    case PosInf => PosInf
    case NegInf => NegInf
    case c: Cst => c
    case f: ArithExprFunctionCall => f
    case v: Var => SimplifyVar(v)
    case Pow(x, y) => SimplifyPow(x, y)
    case Prod(factors) => SimplifyProd(factors)
    case Sum(terms) => SimplifySum(terms)
    case Mod(a, b) => SimplifyMod(a, b)
    case IntDiv(a, b) => SimplifyIntDiv(a, b)
    case IfThenElse(test, t, el) => SimplifyIfThenElse(test, t, el)
    case AbsFunction(ae) => SimplifyAbs(ae)
    case FloorFunction(ae) => SimplifyFloor(ae)
    case CeilingFunction(ae) => SimplifyCeiling(ae)
    case bs:BigSum => SimplifyBigSum(bs)
  }
}

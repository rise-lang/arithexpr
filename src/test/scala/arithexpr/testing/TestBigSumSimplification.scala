package arithexpr.testing

import arithexpr.arithmetic.BoolExpr.ArithPredicate
import arithexpr.arithmetic._
import org.junit.Assert.assertEquals
import org.junit.Test

class TestBigSumSimplification {

  @Test
  def inclusivity = assertEquals(BigSum(from = 0, upTo = 0, _ => 1), Cst(1))

  @Test
  def constant = assertEquals(BigSum(from = 0, upTo = 10 - 1, _ => 1), Cst(10))

  @Test
  def splitSum = {
    val x = Var("x")
    val y = Var("y")
    val bs = BigSum(from = 0, upTo = 10 - 1, _ => x + y)
    assertEquals(bs, (10*x) + (10*y))
  }

  @Test
  def euler = assertEquals(BigSum(from = 0, upTo = 10 - 1, x => x), Cst(45))

  @Test
  def takeOutFactorAndThenEuler =assertEquals(BigSum(from = 0, upTo = 10 - 1, x => 2 * x), Cst(90))

  @Test
  def splitIfSum = {
    val s = BigSum(from = 0, upTo = 10, i =>
      IfThenElse(ArithPredicate(i, 5, ArithPredicate.Operator.<), i, 2*i))
    assertEquals(s, Cst(100))
  }
}

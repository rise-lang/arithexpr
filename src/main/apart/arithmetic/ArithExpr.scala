package apart
package arithmetic

import java.util.concurrent.atomic.AtomicLong

import apart.arithmetic.simplifier._

import scala.language.implicitConversions

/**
  * Class `ArithExpr` is the base class for arithmetic expression trees.
  *
  * An arithmetic expression is a collection of statements representing algebraic operations (+,-,*,/,...), constants
  * and variables. Precedence is taken care of using Scala's operator precedence.
  *
  * These expressions follow mostly natural arithmetic, with a few exceptions:
  * - Modulo is defined for all integers (like the remainder operator `%` in C)
  * - The division operator `/` performs an integer division (the fractional part is discarded)
  * - The operator `/^` is a division operator in the rational set (using ordinal arithmetic)
  */
abstract sealed class ArithExpr {

  /**
    * By default the expression is not simplified
    */
  val simplified: Boolean = false

  /* Should be overridden by any class that extends ArithExpr and is outside the arithmetic package */
  lazy val sign: Sign.Value = Sign(this)

  /**
    * Return the min or max of this arithmetic expression by setting all the variables to their min or max values.
    * Should be overridden by any class that extends ArithExpr and is outside the arithmetic package.
    */
  lazy val (min: ArithExpr, max: ArithExpr) = _minmax()

  /** This method should only be used internally or in special cases where we want to customise the behaviour
    * based on the variables
    */
  private def _minmax() : (ArithExpr, ArithExpr) =
  this match {
    case AbsFunction(expr) =>
            (ArithExpr.min(abs(expr.min), abs(expr.max)),
             ArithExpr.max(abs(expr.min), abs(expr.max)))
    case PosInf => (PosInf, PosInf)
    case NegInf => (NegInf, NegInf)
    case c: CeilingFunction => (ceil(c.ae.min), ceil(c.ae.max))
    case f: FloorFunction => (floor(f.ae.min), floor(f.ae.max))
    case c: Cst => (c,c)
    case Prod(factors) =>
      this.sign match {
        case Sign.Positive => (factors.map(abs(_).min).reduce[ArithExpr](_ * _), factors.map(abs(_).max).reduce[ArithExpr](_ * _))
        case Sign.Negative => (factors.map(abs(_).max).reduce[ArithExpr](_ * _) * -1, factors.map(abs(_).min).reduce[ArithExpr](_ * _) * -1)
        case Sign.Unknown => (?,?) // impossible to determine the min and max
      }
    case Sum(terms) =>
      (terms.map(_.min).reduce[ArithExpr](_ + _), terms.map(_.max).reduce[ArithExpr](_ + _))
    case IntDiv(numer, denom) =>
      this.sign match {
        case Sign.Positive => (ExprSimplifier(numer.min / denom.max), ExprSimplifier(numer.max / denom.min))
        case Sign.Negative => (ExprSimplifier(numer.max / denom.min), ExprSimplifier(numer.min / denom.max))
        case Sign.Unknown => (?,?) // impossible to determine the min and max
      }
    case ite : IfThenElse =>
      (ArithExpr.Math.Min(ite.t.min, ite.e.min), ArithExpr.Math.Max(ite.t.max, ite.e.max))
    case l:Log =>
      assert (l.x.sign == Sign.Positive)
      (l.x-1).sign match {
        case Sign.Positive => (Log(l.b.max, l.x.min), Log(l.b.min,l.x.max))
        case Sign.Negative => (Log(l.b.min, l.x.max), Log(l.b.max,l.x.min))
        case _ => (?,?) // impossible to determine the min and max
      }
    case Mod(dividend, divisor) =>
      (dividend.sign,divisor.sign) match{
        case (Sign.Positive, Sign.Positive) => (0, divisor.max-1)
        case (Sign.Positive, Sign.Negative) => (0, (0-divisor.max)-1)
        case (Sign.Negative, Sign.Positive) => (0-(divisor.max-1), 0)
        case (Sign.Negative, Sign.Negative) => (0-((0-divisor).max-1),0)
        case _ => (?,?) // impossible to determine the min and max
      }
    case Pow(b,e) =>
      (b.sign, e.sign) match {
        case (Sign.Positive, Sign.Positive) => (b.min pow e.min, b.max pow e.max)
        case (Sign.Positive, Sign.Negative) => (b.max pow e.min, b.min pow e.max)
        case (Sign.Positive, _) => (?,?) // could be anything
        case (Sign.Negative, _) => (?,?) // could be anything
        case (Sign.Unknown, _) => (?,?) // unkown
      }
    case v: Var => (v.range.min.min: ArithExpr, v.range.max.max: ArithExpr)
    case ? => (?,?)
    case _ => (?,?)
  }

  /**
    * Evaluates an arithmetic expression.
    *
    * @return The Int value of the expression.
    * @throws NotEvaluableException if the expression cannot be fully evaluated.
    */
  lazy val eval: Int = {
    // Evaluating is quite expensive, traverse the tree to check assess evaluability
    if (!isEvaluable)
      throw ArithExpr.NotEvaluable
    val dblResult = ArithExpr.evalDouble(this)
    if (dblResult.isValidInt)
      dblResult.toInt
    else throw ArithExpr.NotEvaluable
  }

  lazy val isEvaluable: Boolean = {
    !ArithExpr.visitUntil(this, x => {
      x == PosInf || x == NegInf || x == ? ||
        x.isInstanceOf[ArithExprFunction] || x.isInstanceOf[Var] || x.isInstanceOf[IfThenElse]
    })
  }

  lazy val evalDbl: Double = ArithExpr.evalDouble(this)

  lazy val atMax: ArithExpr = {
    val vars = varList.filter(_.range.max != ?)
    val exprFunctions = ArithExprFunction.getArithExprFuns(this).filter(_.range.max != ?)
    val maxLens = vars.map(_.range.max) ++ exprFunctions.map(_.range.max)
    ArithExpr.substitute(this, (vars ++ exprFunctions, maxLens).zipped.toMap)
  }

  lazy val atMin: ArithExpr = {
    val vars = varList.filter(_.range.min != ?)
    val exprFunctions = ArithExprFunction.getArithExprFuns(this).filter(_.range.min != ?)
    val maxLens = vars.map(_.range.min) ++ exprFunctions.map(_.range.min)
    ArithExpr.substitute(this, (vars ++ exprFunctions, maxLens).zipped.toMap)
  }

  lazy val varList = getVars(this)

  private def getVars(e: ArithExpr, l: Set[Var] = Set[Var]()): Set[Var] = {
    e match {
      case adds: Sum => adds.terms.foldLeft(l)((acc, expr) => getVars(expr, acc))
      case muls: Prod => muls.factors.foldLeft(l)((acc, expr) => getVars(expr, acc))
      case Pow(b, oe) => l ++ getVars(b) ++ getVars(oe)
      case v: Var => l + v
      case _ => l
    }
  }

  /**
    * Fast Equality operator.
    * The function first compares the seeds, then the digests. If they are equal, the trees are compared using the
    * full equality operator.
    *
    * @param that Another expression.
    * @return True if the two expressions are equal, false otherwise.
    * @note This operator works only for simplified expressions.
    */
  def ==(that: ArithExpr): Boolean = {
    if (this.HashSeed() == that.HashSeed() && digest() == that.digest())
      this === that
    else false
  }

  /**
    * True equality operator. Compare each operands.
    *
    * @param that Another expression.
    * @return True iif the two expressions are equal.
    * @note This operator works only for simplified expressions.
    */
  def ===(that: ArithExpr): Boolean = (this, that) match {
    case (Cst(x), Cst(y)) => x == y
    case (IntDiv(x1, y1), IntDiv(x2, y2)) => x1 == x2 && y1 == y2
    case (Pow(x1, y1), Pow(x2, y2)) => x1 == x2 && y1 == y2
    case (Log(x1, y1), Log(x2, y2)) => x1 == x2 && y1 == y2
    case (Mod(x1, y1), Mod(x2, y2)) => x1 == x2 && y1 == y2
    case (FloorFunction(a), FloorFunction(x)) => a == x
    case (CeilingFunction(x), CeilingFunction(y)) => x == y
    case (Sum(a), Sum(b)) => a.length == b.length && (a zip b).forall(x => x._1 == x._2)
    case (Prod(a), Prod(b)) => a.length == b.length && (a zip b).forall(x => x._1 == x._2)
    case (IfThenElse(test1, t1, e1), IfThenElse(test2, t2, e2)) =>
      test1.op == test2.op && test1.lhs == test2.lhs && test1.rhs == test2.rhs && t1 == t2 && e1 == e2
    case (lu1: Lookup, lu2: Lookup) => lu1.table == lu2.table && lu1.index == lu2.index
    case (f1: ArithExprFunction, f2: ArithExprFunction) => f1.name == f2.name
    case (v1: Var, v2: Var) => v1.id == v2.id
    case (AbsFunction(x), AbsFunction(y)) => x == y
    case _ =>
      System.err.println(s"$this and $that are not equal")
      false
  }


  def pow(that: ArithExpr): ArithExpr = SimplifyPow(this, that)

  /**
    * Multiplication operator.
    *
    * @param that Right-hand side.
    * @return An expression representing the product (not necessarily a Prod object).
    */
  def *(that: ArithExpr): ArithExpr = SimplifyProd(this, that)

  /**
    * Addition operator.
    *
    * @param that Right-hand side.
    * @return An expression representing the sum (not necessarily a Sum object).
    */
  def +(that: ArithExpr): ArithExpr = SimplifySum(this, that)

  /**
    * Division operator in Natural set (ie int div like Scala): `1/2=0`.
    *
    * @param that Right-hand side (divisor).
    * @return An IntDiv object wrapping the operands.
    * @throws ArithmeticException if the right-hand-side is zero.
    */
  def /(that: ArithExpr) = SimplifyIntDiv(this, that)

  /**
    * Ordinal division operator.
    * This prevents integer arithmetic simplification through exponentiation.
    *
    * @param that Right-hand side (divisor).
    * @return The expression multiplied by the divisor exponent -1.
    */
  def /^(that: ArithExpr) = SimplifyDivision(this, that)

  /**
    * Transform subtraction into sum of product with -1
    *
    * @param that Right-hand side of the division
    * @return A Sum object
    */
  def -(that: ArithExpr) = this + (that * -1)

  /**
    * The % operator yields the remainder from the division of the first expression by the second.
    *
    * @param that The right-hand side (divisor)
    * @return A Mod expression
    * @throws ArithmeticException if the right-hand-side is zero.
    * @note This operation is defined for negative number since it computes the remainder of the algebraic quotient
    *       without fractional part times the divisor, ie (a/b)*b + a%b is equal to a.
    */
  def %(that: ArithExpr) = SimplifyMod(this, that)

  /**
    * Lower than comparison operator.
    *
    * @param that Right-hand side of the comparison
    * @return A Predicate object
    */
  def lt(that: ArithExpr) = Predicate(this, that, Predicate.Operator.<)

  /**
    * Greater than comparison operator.
    *
    * @param that Right-hand side of the comparison
    * @return A Predicate object
    */
  def gt(that: ArithExpr) = Predicate(this, that, Predicate.Operator.>)

  /**
    * Lower-or-equal comparison operator.
    *
    * @param that Right-hand side of the comparison
    * @return A Predicate object
    */
  def le(that: ArithExpr) = Predicate(this, that, Predicate.Operator.<=)

  /**
    * Greater-or-equal comparison operator.
    *
    * @param that Right-hand side of the comparison
    * @return A Predicate object
    */
  def ge(that: ArithExpr) = Predicate(this, that, Predicate.Operator.>=)

  /**
    * Equality comparison operator.
    *
    * @note Silently overrides the reference comparison operator `AnyRef.eq`
    * @param that Right-hand side of the comparison
    * @return A Predicate object
    */
  def eq(that: ArithExpr) = Predicate(this, that, Predicate.Operator.==)

  /**
    * Inequality comparison operator.
    *
    * @note Silently overrides the reference comparison operator `AnyRef.ne`
    * @param that Right-hand side of the comparison
    * @return A Predicate object
    */
  def ne(that: ArithExpr) = Predicate(this, that, Predicate.Operator.!=)

  /**
    * The hash function creates a 32 bit digest of the expression. Each node type has a unique salt and combines
    * the hashes of the subexpressions using a commutative and associative operator (most likely XOR).
    *
    * The probability of a collision is already fairly low, but in order to guarantee equality one should call
    * visit with a hash comparison function on the sub-tree to guarantee that each node matches. The probability
    * of a collision is then the probability of a collision of a leaf node, which is zero for constant nodes and zero
    * for the first 2,147,483,647 variable instances.
    *
    * @return A 32 bit digest of the expression.
    */
  def digest(): Int

  override def hashCode = digest()

  def HashSeed(): Int
}

object ArithExpr {

  implicit def IntToCst(i: Int): Cst = Cst(i)

  def NotEvaluable = new NotEvaluableException()

  val sort: (ArithExpr, ArithExpr) => Boolean = (x: ArithExpr, y: ArithExpr) => (x, y) match {
    case (c:Cst, _) => true                 // constants first
    case (_, c:Cst) => false
    case (x:Var, y:Var) => x.name < y.name  // order variables lexicographically
    case (v:Var, _) => true                 // variables always after constants second
    case (_, v:Var) => false
    case (x:Prod, y:Prod) => x.factors.zip(y.factors).map(x => sort(x._1, x._2)).foldLeft(false)(_ || _)
    case _ => x.HashSeed() < y.HashSeed() || (x.HashSeed() == y.HashSeed() && x.digest() < y.digest())
  }

  def gcd(a: ArithExpr, b: ArithExpr): ArithExpr = ComputeGCD(a, b)

  def max(e1: ArithExpr, e2: ArithExpr): ArithExpr = minmax(e1, e2)._2

  def min(e1: ArithExpr, e2: ArithExpr): ArithExpr = minmax(e1, e2)._1

  def minmax(e1: ArithExpr, e2: ArithExpr): (ArithExpr, ArithExpr) = {
    e1 - e2 match {
      case Cst(c) if c < 0 => (e1, e2) /* e1 is smaller than e2 */
      case Cst(c) => (e2, e1) /* e2 is smaller than e1*/
      case _ =>
        (e1, e2) match {
          case (v: Var, c: Cst) => minmax(v, c)
          case (c: Cst, v: Var) => minmax(v, c).swap

          case (p: Prod, c: Cst) => minmax(p, c)
          case (c: Cst, p: Prod) => minmax(p, c).swap

          case _ => throw NotEvaluable
        }
    }
  }

  def minmax(v: Var, c: Cst): (ArithExpr, ArithExpr) = {
    val m1 = v.range.min match {
      case Cst(min) => if (min >= c.c) Some((c, v)) else None
      case ? => throw new NotEvaluableException()
      case _ => throw new NotImplementedError()
    }

    if (m1.isDefined) return m1.get

    val m2 = v.range.max match {
      case Cst(max) => if (max <= c.c) Some((v, c)) else None
      case _ => throw new NotImplementedError()
    }

    if (m2.isDefined) return m2.get

    throw NotEvaluable
  }

  def minmax(p: Prod, c: Cst): (ArithExpr, ArithExpr) = {
    try {
      val lb = lowerBound(p)
      if (lb.isDefined && lb.get >= c.c) return (c, p)

      val ub = upperBound(p)
      if (ub.isDefined && ub.get <= c.c) return (p, c)
    } catch {
      case _: IllegalArgumentException =>
    }

    throw NotEvaluable
  }

  private def upperBound(p: Prod): Option[Int] = {
    Some(Prod(p.factors.map({
      case v: Var => v.range.max match {
        case max: Cst => max
        case _ => return None
      }
      case c: Cst => c
      case _ => throw new IllegalArgumentException("upperBound expects a Var or a Cst")
    })).eval)
  }

  private def lowerBound(p: Prod): Option[Int] = {
    Some(Prod(p.factors.map({
      case v: Var => v.range.min match {
        case min: Cst => min
        case _ => return None
      }
      case c: Cst => c
      case _ => throw new IllegalArgumentException("lowerBound expects a Var or a Cst")
    })).eval)
  }


  def contains(expr: ArithExpr, elem: ArithExpr): Boolean = {
    visit(expr, e => if (e == elem) return true)
    false
  }

  /**
    * Find if an expression is possibly a multiple of another.
    *
    * @param expr The expression.
    * @param that A possible multiple.
    * @return True if `that` is a multiple of `expr`, false otherwise
    */
  def multipleOf(expr: ArithExpr, that: ArithExpr): Boolean = (ExprSimplifier(expr), that) match {

    // Compare two products, look for inclusion of common denominator
    case (Prod(terms), Prod(otherTerms)) => terms.count(isDivision) == otherTerms.count(isDivision) && otherTerms.map({
      case pow: Pow => terms.exists(multipleOf(_, pow))
      case x => terms.contains(x)
    }).reduce(_ && _)

    // A constant is a multiple of a product if it is a multiple of its constant factor
    case (Prod(terms), Cst(c)) =>
      val cst = terms.find(_.isInstanceOf[Cst])
      cst.isDefined && cst.get.asInstanceOf[Cst].c % c == 0

    // If it is something else, it is a multiple if it is included in the list of factors and the product does not
    // contain a division
    case (Prod(terms), _) => !terms.exists(isDivision) && terms.contains(that)

    // Check multiple of constants
    case (Cst(c1), Cst(c2)) => c1 % c2 == 0

    // Look for common denominator in fractions
    case (IntDiv(n1, d1), IntDiv(n2, d2)) => multipleOf(d2, d1) && multipleOf(n1, n2)

    // Look for the denominator for two inverses
    case (Pow(b1, Cst(-1)), Pow(b2, Cst(-1))) => multipleOf(b2, b1)

    // Finally, the two expressions are multiple of each other if they are the same
    case (x, y) => x == y
  }

  private[arithmetic] def hasDivision(factors: List[ArithExpr]): Boolean = {
    factors.exists(isDivision)
  }

  private[arithmetic] def isDivision: (ArithExpr) => Boolean = {
    case Pow(_, Cst(x)) if x < 0 => true
    case e => false
  }


  def collectVars(ae: ArithExpr): Set[Var] = {
    val vars = new scala.collection.mutable.HashSet[Var]()
    ArithExpr.visit(ae, {
      case v: Var =>
        vars += v
        vars ++= collectVars(v.range.max)
        vars ++= collectVars(v.range.min)
      case _ =>
    }
    )
    vars.toSet
  }

  def mightBeNegative(expr: ArithExpr): Boolean = {
    expr.sign != Sign.Positive
  }

  /**
    * Return true if ae1 is definitively smaller than ae2.
    * Return false if this cannot be proven (this does not mean that ae1 is always larger than ae2)
    */
  def isSmaller(ae1: ArithExpr, ae2: ArithExpr): Option[Boolean] = {

    // 1) if ae1 and ae2 constants, return True or False
    // 2) collect all the variables that appears only in ae1 or only in ae2
    // 3) if no unique var, then return : don't know
    // 4) call isSmaller (max(ae1),min(ae2)) by forcing min and max to only set the unique vars (in other word the min or max of all the other var should be the var itself (and not the min or max of its range))
    // this can be achieved probably by rewriting the expression using a special var which wraps the original var, and when the call returns we can unwrap them, this is needed to ensure the min or max of these var is the var itself

    try {
      // we check to see if the difference can be evaluated
      val diff = ae2 - ae1
      if (diff.isEvaluable)
        return Some(diff.evalDbl > 0)
    } catch {
      case _: NotEvaluableException =>
    }

    try {
      return Some(ae1.max.eval < ae2.min.eval)
    } catch {
      case _: NotEvaluableException =>
    }

    // TODO: Find a more generic solution for these cases
    (ae1, ae2) match {
      // a * v /^ b < v (true if a < b)
      case (Prod(Cst(c1) :: (v1: Var) :: Pow(Cst(c2), Cst(-1)) :: Nil), v2: Var) if v1 == v2 && c1 < c2 =>
        return Some(true)
      // v /^ a < v (true if a > 1)
      case (Prod((v1: Var) :: Pow(Cst(c), Cst(-1)) :: Nil), v2: Var) if v1 == v2 && c > 1 =>
        return Some(true)
      // a < b (true if a.max < b)
      case (v1: Var, v2: Var) if isSmaller(v1.range.max + 1, v2).getOrElse(false) =>
        return Some(true)
      // Abs(a + x) < n true if (a + x) < n and -1(a + x) < n
      case (AbsFunction(Sum(Cst(a) :: (x: Var) :: Nil)), n: Var) if
          isSmaller(Sum(a :: x.range.max :: Nil), n).getOrElse(false) &&
          isSmaller(Prod(Cst(-1) :: Sum(a :: x.range.min :: Nil) :: Nil), n).getOrElse(false) =>
        return Some(true)
      case (Mod((a: ArithExpr), (v1:Var)), (v2:Var)) if v1 == v2 =>
        return Some(true)
      case _ =>
    }

    // if we see an opaque var or unknown, we cannot say anything
    if (ae1.isInstanceOf[OpaqueVar] | ae2.isInstanceOf[OpaqueVar] | ae1 == ? | ae2 == ?)
      return None

    //  handling of infinite values
    (ae1, ae2) match {
      case (PosInf, PosInf) => return None
      case (NegInf, NegInf) => return None
      case (PosInf, NegInf) => return Some(false)
      case (NegInf, PosInf) => return Some(true)
      case (PosInf, _) if ae2.isEvaluable => return Some(false)
      case (NegInf, _) if ae2.isEvaluable => return Some(true)
      case (_, NegInf) if ae1.isEvaluable => return Some(false)
      case (_, PosInf) if ae1.isEvaluable => return Some(true)

      case _ =>
    }


    val ae1Vars = collectVars(ae1).filter(_ match { case _: OpaqueVar => false case _ => true })
    val ae2Vars = collectVars(ae2).filter(_ match { case _: OpaqueVar => false case _ => true })
    val commonVars = ae1Vars & ae2Vars

    val varsOnlyInae1 = ae1Vars -- commonVars
    val varsOnlyInae2 = ae2Vars -- commonVars
    val varsOnlyInae1orae2 = varsOnlyInae1 ++ varsOnlyInae2

    if (varsOnlyInae1orae2.isEmpty)
      return None

    val replacements = commonVars.map(v => (v, new OpaqueVar(v))).toMap
    val ae1WithFixedVars = ArithExpr.substitute(ae1, replacements.toMap)
    val ae2WithFixedVars = ArithExpr.substitute(ae2, replacements.toMap)

    try {
      val ae1WithFixedVarsMax = ae1WithFixedVars.max
      val ae2WithFixedVarsMin = ae2WithFixedVars.min
      isSmaller(ae1WithFixedVarsMax, ae2WithFixedVarsMin)
    } catch {
      case _: NotEvaluableException => None
    }
  }

  /**
    * Warning, this function does not visit the range inside the var (maybe we wants this?)
    */
  def visit(e: ArithExpr, f: (ArithExpr) => Unit): Unit = {
    f(e)
    e match {
      case Pow(base, exp) =>
        visit(base, f)
        visit(exp, f)
      case IntDiv(n, d) =>
        visit(n, f)
        visit(d, f)
      case Mod(dividend, divisor) =>
        visit(dividend, f)
        visit(divisor, f)
      case Log(b, x) =>
        visit(b, f)
        visit(x, f)
      case FloorFunction(expr) => visit(expr, f)
      case CeilingFunction(expr) => visit(expr, f)
      case Sum(terms) => terms.foreach(t => visit(t, f))
      case Prod(terms) => terms.foreach(t => visit(t, f))
      case IfThenElse(test, thenE, elseE) =>
        visit(test.lhs, f)
        visit(test.rhs, f)
        visit(thenE, f)
        visit(elseE, f)
      case lu: Lookup =>
        visit(lu.index, f)
        lu.table.foreach(t => visit(t, f))
      case Var(_, _) | Cst(_) | ArithExprFunction(_, _) =>
      case x if x.getClass == ?.getClass =>
      case PosInf | NegInf =>
      case AbsFunction(expr) => visit(expr, f)
    }
  }

  def visitUntil(e: ArithExpr, f: (ArithExpr) => Boolean): Boolean = {
    if (f(e)) true
    else {
      e match {
        case Pow(base, exp) =>
          visitUntil(base, f) || visitUntil(exp, f)
        case IntDiv(n, d) =>
          visitUntil(n, f) || visitUntil(d, f)
        case Mod(dividend, divisor) =>
          visitUntil(dividend, f) || visitUntil(divisor, f)
        case Log(b, x) =>
          visitUntil(b, f) || visitUntil(x, f)
        case FloorFunction(expr) => visitUntil(expr, f)
        case CeilingFunction(expr) => visitUntil(expr, f)
        case Sum(terms) =>
          terms.foreach(t => if (visitUntil(t, f)) return true)
          false
        case Prod(terms) =>
          terms.foreach(t => if (visitUntil(t, f)) return true)
          false
        case gc: Lookup => visitUntil(gc.index, f)
        case Var(_, _) | Cst(_) | IfThenElse(_, _, _) | ArithExprFunction(_, _) => false
        case x if x.getClass == ?.getClass => false
        case PosInf | NegInf => false
        case AbsFunction(expr) => visitUntil(expr, f)
      }
    }
  }

  // TODO: needs to substitute range of functions (get_local_id for instance)
  // (the copy method is currently borken since it will generate a new id for the var)
  def substitute(e: ArithExpr, substitutions: scala.collection.Map[ArithExpr, ArithExpr]): ArithExpr =
    substitutions.getOrElse(e, e) match {
      case Pow(l, r) => substitute(l, substitutions) pow substitute(r, substitutions)
      case AbsFunction(ae) => abs(substitute(ae, substitutions))
      case IntDiv(n, d) => substitute(n, substitutions) / substitute(d, substitutions)
      case Mod(dividend, divisor) => substitute(dividend, substitutions) % substitute(divisor, substitutions)
      case Log(b, x) => Log(substitute(b, substitutions), substitute(x, substitutions))
      case IfThenElse(i, thenE, elseE) =>
        val cond = Predicate(substitute(i.lhs, substitutions), substitute(i.rhs, substitutions), i.op)
        cond ?? substitute(thenE, substitutions) !! substitute(elseE, substitutions)
      case FloorFunction(expr) => FloorFunction(substitute(expr, substitutions))
      case CeilingFunction(expr) => CeilingFunction(substitute(expr, substitutions))
      case adds: Sum => adds.terms.map(t => substitute(t, substitutions)).reduce(_ + _)
      case muls: Prod => muls.factors.map(t => substitute(t, substitutions)).reduce(_ * _)
      case lu: Lookup => SimplifyLookup(lu.table, substitute(lu.index, substitutions), lu.id)
      case v: Var => v.copy(Range.substitute(v.range, substitutions))
      case ? => ?
      case f: ArithExprFunction => f
      case c: Cst => c
      case NegInf => NegInf
      case PosInf => PosInf
      case x: SimplifiedExpr => x
    }

  private def evalDouble(e: ArithExpr): Double = e match {
    case Cst(c) => c

    case IntDiv(n, d) => scala.math.floor(evalDouble(n) / evalDouble(d))


    case Pow(base, exp) => scala.math.pow(evalDouble(base), evalDouble(exp))
    case Log(b, x) => scala.math.log(evalDouble(x)) / scala.math.log(evalDouble(b))

    case Mod(dividend, divisor) => dividend.eval % divisor.eval

    case Sum(terms) => terms.foldLeft(0.0)((result, expr) => result + evalDouble(expr))
    case Prod(terms) => terms.foldLeft(1.0)((result, expr) => result * evalDouble(expr))

    case FloorFunction(expr) => scala.math.floor(evalDouble(expr))
    case CeilingFunction(expr) => scala.math.ceil(evalDouble(expr))

    case AbsFunction(expr) => scala.math.abs(evalDouble(expr))

    case IfThenElse(_, _, _) => throw NotEvaluable

    case ? | NegInf | PosInf | _: Var | _: ArithExprFunction | _: SimplifiedExpr => throw NotEvaluable
  }


  def toInt(e: ArithExpr): Int = ExprSimplifier(e) match {
    case Cst(i) => i
    case _ => throw NotEvaluable
  }


  def asCst(e: ArithExpr) = ExprSimplifier(e) match {
    case c: Cst => c
    case _ => throw new IllegalArgumentException
  }


  /**
    * Math operations derived from the basic operations
    */
  object Math {
    /**
      * Computes the minimal value between the two argument
      *
      * @param x The first value
      * @param y The second value
      * @return The minimum between x and y
      */
    def Min(x: ArithExpr, y: ArithExpr) = {
      // Since Min duplicates the expression, we simplify it in place to point to the same node
      val sx = ExprSimplifier(x)
      val sy = ExprSimplifier(y)
      (sx le sy) ?? sx !! sy
    }

    /**
      * Computes the maximal value between the two argument
      *
      * @param x The first value
      * @param y The second value
      * @return The maximum between x and y
      */
    def Max(x: ArithExpr, y: ArithExpr) = {
      // Since Max duplicates the expression, we simplify it in place to point to the same node
      val sx = ExprSimplifier(x)
      val sy = ExprSimplifier(y)
      (sx gt sy) ?? sx !! sy
    }

    /**
      * Clamps a value to a given range
      *
      * @param x   The input value
      * @param min Lower bound of the range
      * @param max Upper bound of the range
      * @return The value x clamped to the interval [min,max]
      */
    def Clamp(x: ArithExpr, min: ArithExpr, max: ArithExpr) = Min(Max(x, min), max)
  }
}

trait SimplifiedExpr extends ArithExpr {
  override val simplified = true
}

/* ? represents an unknown value. */
case object ? extends ArithExpr with SimplifiedExpr {
  override val HashSeed = 0x3fac31

  override val digest: Int = HashSeed

  override lazy val sign = Sign.Unknown

  override def ==(that: ArithExpr): Boolean = that.getClass == this.getClass
}

case object PosInf extends ArithExpr with SimplifiedExpr {
  override val HashSeed = 0x4a3e87

  override val digest: Int = HashSeed

  override lazy val sign = Sign.Positive

  override def ==(that: ArithExpr): Boolean = that.getClass == this.getClass
}

case object NegInf extends ArithExpr with SimplifiedExpr {
  override val HashSeed = 0x4a3e87

  override val digest: Int = HashSeed

  override lazy val sign = Sign.Negative

  override def ==(that: ArithExpr): Boolean = that.getClass == this.getClass
}

case class Cst private[arithmetic](c: Int) extends ArithExpr with SimplifiedExpr {
  override val HashSeed = Integer.hashCode(c)

  override lazy val digest: Int = Integer.hashCode(c)

  override lazy val toString = c.toString
}

case class IntDiv private[arithmetic](numer: ArithExpr, denom: ArithExpr) extends ArithExpr() {
  if (denom == Cst(0))
    throw new ArithmeticException()

  override val HashSeed = 0xf233de5a

  override lazy val digest: Int = HashSeed ^ numer.digest() ^ ~denom.digest()

  override def toString: String = s"($numer) / ($denom)"
}

case class Pow private[arithmetic](b: ArithExpr, e: ArithExpr) extends ArithExpr {
  override val HashSeed = 0x63fcd7c2

  override lazy val digest: Int = HashSeed ^ b.digest() ^ e.digest()

  override def toString: String = e match {
    case Cst(-1) => "1/^(" + b + ")"
    case _ => "pow(" + b + "," + e + ")"
  }
}

case class Log private[arithmetic](b: ArithExpr, x: ArithExpr) extends ArithExpr with SimplifiedExpr {
  override val HashSeed = 0x370285bf

  override lazy val digest: Int = HashSeed ^ b.digest() ^ ~x.digest()

  override def toString: String = "log" + b + "(" + x + ")"
}

/**
  * Represent a product of two or more expressions.
  *
  * @param factors The list of factors. The list should contain at least 2 operands and should not contain other products.
  */
case class Prod private[arithmetic](factors: List[ArithExpr]) extends ArithExpr {

  if (Debug.SanityCheck && simplified) {
    Debug.Assert(factors.view.zip(factors.tail).forall(x => ArithExpr.sort(x._1, x._2)), "Factors should be sorted")
    Debug.Assert(factors.length > 1, s"Factors should have at least two terms in $toString")
    factors.foreach(x => {
      Debug.AssertNot(x.isInstanceOf[Prod], s"Prod cannot contain a Prod in $toString")
      Debug.AssertNot(x.isInstanceOf[Sum], "Prod should not contain a Sum")
    })
  }

  override val HashSeed = 0x286be17e

  override lazy val digest: Int = factors.foldRight(HashSeed)((x, hash) => hash ^ x.digest())

  override def equals(that: Any) = that match {
    case p: Prod => factors.length == p.factors.length && factors.intersect(p.factors).length == factors.length
    case _ => false
  }

  override lazy val toString: String = {
    val m = if (factors.nonEmpty) factors.mkString("*") else ""
    "(" + m + ")"
  }

  def contains(e: ArithExpr): Boolean = factors.contains(e)

  /**
    * Remove a list of factors from the factors of the product and return either a Product with the remaining factors,
    * the only factors left or 1 in the case of removing all factors.
    * Removing factors does not create new optimization opportunity, therefore the resulting prod is still simplified.
    */
  def withoutFactors(list: List[ArithExpr]): ArithExpr = {
    assert(simplified, "This function only works on simplified products")
    val rest: List[ArithExpr] = factors.diff(list)
    // If we took all the elements out, return neutral (1 for product)
    if (rest.isEmpty) Cst(1)
    // If there is only one left, return it
    else if (rest.length == 1) rest.head
    // Otherwise create a new product, which is also simplified by construction
    else new Prod(rest) with SimplifiedExpr
  }

  def withoutFactor(factor: ArithExpr): ArithExpr = withoutFactors(List(factor))

  lazy val cstFactor: Cst = {
    if (simplified) factors.find(_.isInstanceOf[Cst]).getOrElse(Cst(1)).asInstanceOf[Cst]
    else Cst(factors.filter(_.isInstanceOf[Cst]).foldLeft[Int](1)(_ + _.asInstanceOf[Cst].c))
  }
}


case class Sum private[arithmetic](terms: List[ArithExpr]) extends ArithExpr {

  if (Debug.SanityCheck && simplified) {
    Debug.Assert(terms.view.zip(terms.tail).forall(x => ArithExpr.sort(x._1, x._2)), "Terms should be sorted")
    Debug.Assert(terms.length > 1, s"Terms should have at least two terms in $toString")
    terms.foreach(x => {
      Debug.AssertNot(x.isInstanceOf[Sum], "Sum cannot contain a Sum")
    })
  }

  override val HashSeed = 0x8e535130

  override lazy val digest: Int = terms.foldRight(HashSeed)((x, hash) => hash ^ x.digest())

  override def equals(that: Any) = that match {
    case s: Sum => terms.length == s.terms.length && terms.intersect(s.terms).length == terms.length
    case _ => false
  }

  override lazy val toString: String = {
    val m = if (terms.nonEmpty) terms.mkString("+") else ""
    "(" + m + ")"
  }

  /**
    * Remove a list of terms from the terms of the sum and returns either a Sum of the remaining terms or the only term
    * left.
    * Removing terms does not create new optimization opportunity, therefore the resulting sum is still simplified.
    */
  def withoutTerm(list: List[ArithExpr]): ArithExpr = {
    assert(simplified, "This function only works on simplified products")
    val rest: List[ArithExpr] = terms.diff(list)
    assert(rest.nonEmpty, "Cannot remove all factors from a product")
    if (rest.length == 1) rest.head
    else new Sum(rest) with SimplifiedExpr
  }

  lazy val cstTerm: Cst = {
    if (simplified) terms.find(_.isInstanceOf[Cst]).getOrElse(Cst(0)).asInstanceOf[Cst]
    else Cst(terms.filter(_.isInstanceOf[Cst]).foldLeft[Int](0)(_ + _.asInstanceOf[Cst].c))
  }
}

// this is really the remainder and not modulo! (I.e. it implements the C semantics of modulo)
case class Mod private[arithmetic](dividend: ArithExpr, divisor: ArithExpr) extends ArithExpr {
  //override val HashSeed = 0xedf6bb88
  override val HashSeed = 0xedf6bb8

  override lazy val digest: Int = HashSeed ^ dividend.digest() ^ ~divisor.digest()

  override lazy val toString: String = s"($dividend % ($divisor))"
}

case class AbsFunction private[arithmetic](ae: ArithExpr) extends ArithExpr {
  override val HashSeed = 0x3570a2ce

  override lazy val digest: Int = HashSeed ^ ae.digest()

  override lazy val toString: String = "Abs(" + ae + ")"
}

object abs {
  def apply(ae: ArithExpr): ArithExpr = SimplifyAbs(ae)
}

case class FloorFunction private[arithmetic](ae: ArithExpr) extends ArithExpr {
  override val HashSeed = 0x558052ce

  override lazy val digest: Int = HashSeed ^ ae.digest()

  override lazy val toString: String = "Floor(" + ae + ")"
}

object floor {
  def apply(ae: ArithExpr): ArithExpr = SimplifyFloor(ae)
}

case class CeilingFunction private[arithmetic](ae: ArithExpr) extends ArithExpr {
  override val HashSeed = 0xa45d23d0

  override lazy val digest: Int = HashSeed ^ ae.digest()

  override lazy val toString: String = "Ceiling(" + ae + ")"
}

object ceil {
  def apply(ae: ArithExpr): ArithExpr = SimplifyCeiling(ae)
}

/* Conditional operator. Behaves like the `?:` operator in C. */
case class IfThenElse private[arithmetic](test: Predicate, t: ArithExpr, e: ArithExpr) extends ArithExpr {
  override val HashSeed = 0x32c3d095

  override lazy val digest: Int = HashSeed ^ test.digest ^ t.digest() ^ ~e.digest()

  override lazy val toString: String = s"( $test ? $t : $e )"
}

/* This class is ment to be used as a superclass, therefore, it is not private to this package */
case class ArithExprFunction(name: String, range: Range = RangeUnknown) extends ArithExpr with SimplifiedExpr {
  override val HashSeed = 0x3105f133

  override lazy val digest: Int = HashSeed ^ range.digest() ^ name.hashCode

  override lazy val toString: String = s"$name($range)"
}

object ArithExprFunction {
  def getArithExprFuns(expr: ArithExpr): Set[ArithExprFunction] = {
    val exprFunctions = scala.collection.mutable.HashSet[ArithExprFunction]()
    ArithExpr.visit(expr, {
      case function: ArithExprFunction => exprFunctions += function
      case _ =>
    })
    exprFunctions.toSet
  }
}

class Lookup private[arithmetic](val table: Seq[ArithExpr],
                                 val index: ArithExpr, val id: Int) extends ArithExprFunction("lookup") {
  override lazy val digest: Int = HashSeed ^ table.hashCode ^ index.digest() ^ id.hashCode()

  override lazy val toString: String = "lookup" + id + "(" + index + ")"

  override def equals(that: Any) = that match {
    case thatLookup: Lookup => thatLookup.table == this.table &&
      thatLookup.index == this.index && thatLookup.id == this.id
    case _ => false
  }
}

object Lookup {
  def apply(table: Seq[ArithExpr], index: ArithExpr, id: Int): ArithExpr = SimplifyLookup(table, index, id)
}

/**
  * Represents a variable in the expression. A variable is an unknown term which is immutable within the expression
  * but its value may change between expression, like a variable in C.
  *
  * @param name  Name for the variable. Might be empty, in which case a name will be generated.
  * @param range Range of possible values for the variable.
  * @note The uniqueness of the variable name is not enforced since there is no notion of scope.
  *       Also note that the name is purely decorative during partial evaluation: the variable is actually tracked
  *       using an instance counter, hence multiple instances sharing the same name will not be simplified.
  */
class Var private[arithmetic](val name: String,
                              val range: Range = RangeUnknown,
                              fixedId: Option[Long] = None) extends ArithExpr with SimplifiedExpr {
  override lazy val hashCode = 8 * 79 + id.hashCode

  override val HashSeed = 0x54e9bd5e

  override lazy val digest: Int = HashSeed ^ name.hashCode ^ id.hashCode ^ range.digest()

  override def equals(that: Any) = that match {
    case v: Var => this.id == v.id
    case _ => false
  }

  override lazy val toString = s"v_${name}_$id"

  lazy val toStringWithRange = s"$toString[${range.toString}]"

  val id: Long = {
    if (fixedId.isDefined)
      fixedId.get
    else {
      var _id: Long = 0
      do {
        _id = Var.cnt.incrementAndGet()
        if (_id < 0)
          Var.cnt.compareAndSet(_id, 0)
      } while (_id < 0)
      _id
    }
  }

  def copy(r: Range) = new Var(name, r, Some(this.id))
}

object Var {
  private val cnt = new AtomicLong(-1) /* Instance counter */

  def apply(name: String = ""): Var = new Var(name)

  def apply(range: Range): Var = new Var("", range)

  def apply(name: String, range: Range): Var = new Var(name, range)

  def unapply(v: Var): Option[(String, Range)] = Some((v.name, v.range))
}

object PosVar {
  def apply(name: String): Var = new Var(name, StartFromRange(Cst(0)))
}

object SizeVar {
  def apply(name: String): Var = new Var(name, StartFromRange(Cst(1)))
}

class OpaqueVar(val v: Var,
                r: Range = RangeUnknown,
                fixedId: Option[Long] = None) extends ExtensibleVar("", r, fixedId) {
  override def makeCopy(r: Range) = new OpaqueVar(v, r, Some(this.id))

  override lazy val (min: ArithExpr, max: ArithExpr) = (this, this)
  override lazy val sign: Sign.Value = v.sign

  override lazy val isEvaluable = false
}

/* This class is ment to be used as a superclass, therefore, it is not private to this package */
abstract class ExtensibleVar(override val name: String,
                             override val range: Range = RangeUnknown,
                             fixedId: Option[Long] = None) extends Var(name, range, fixedId) {
  override def copy(r: Range): Var = makeCopy(r)

  /* force subclasses to implement makeCopy by making it abstract here */
  protected def makeCopy(r: Range): ExtensibleVar
}

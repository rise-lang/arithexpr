package arithexpr
package arithmetic

sealed abstract class Range {
  // default impl
  def *(e: ArithExpr with SimplifiedExpr): Range = this
  // minimum value this range can take (note this is not recursive, if there is a var in the range somewhere, we do not try to take its minimum value
  val min : ArithExpr with SimplifiedExpr
  // maximum value this range can take
  val max : ArithExpr with SimplifiedExpr

  def digest(): Int = min.digest() ^ max.digest()

  override def equals(that: Any): Boolean = that match {
    case r: Range => this.min == r.min && this.max == r.max
    case _ => false
  }

  def visitAndRebuild(f: ArithExpr => ArithExpr): Range

  def substitute(subs: collection.Map[ArithExpr, ArithExpr]): Option[Range]

  /* Number of different values this range can take */
  lazy val numVals: ArithExpr with SimplifiedExpr = ?
}

object Range {
  def unapply(r: Range): Option[(ArithExpr with SimplifiedExpr, ArithExpr with SimplifiedExpr)] = Some(r.min, r.max)

  def substitute(r: Range, substitutions: scala.collection.Map[ArithExpr, ArithExpr]): Range = {
    r match {
      case s: StartFromRange => StartFromRange(ArithExpr.substitute(s.start, substitutions))
      case g: GoesToRange => GoesToRange(ArithExpr.substitute(g.end, substitutions))
      case a: RangeAdd => RangeAdd(ArithExpr.substitute(a.start, substitutions),ArithExpr.substitute(a.stop, substitutions),ArithExpr.substitute(a.step, substitutions))
      case m: RangeMul => RangeMul(ArithExpr.substitute(m.start, substitutions),ArithExpr.substitute(m.stop, substitutions),ArithExpr.substitute(m.mul, substitutions))
      case RangeUnknown => r
    }
  }

  /**
    * Converts a Range to a Scala notation String which can be evaluated into a valid Range
    */
  def printToScalaString(r: Range, printNonFixedVarIds: Boolean): String = r match {
    case StartFromRange(start) =>             s"StartFromRange(${ArithExpr.printToScalaString(start, printNonFixedVarIds)})"
    case GoesToRange(end) =>                  s"GoesToRange(${ArithExpr.printToScalaString(end, printNonFixedVarIds)})"
    case RangeAdd(start, stop, step) =>       s"RangeAdd(${ArithExpr.printToScalaString(start, printNonFixedVarIds)}, " +
                                                       s"${ArithExpr.printToScalaString(stop, printNonFixedVarIds)}, " +
                                                       s"${ArithExpr.printToScalaString(step, printNonFixedVarIds)})"
    case RangeMul(start, stop, mul) =>        s"RangeMul(${ArithExpr.printToScalaString(start, printNonFixedVarIds)}, " +
                                                       s"${ArithExpr.printToScalaString(stop, printNonFixedVarIds)}, " +
                                                       s"${ArithExpr.printToScalaString(mul, printNonFixedVarIds)})"
    case RangeUnknown =>                      s"RangeUnknown"
    case _ =>
      throw new NotImplementedError(s"Range $r is not supported in printing Range to Scala notation String")
  }
}

class RangeUnknownException(msg: String) extends Exception(msg)

case class StartFromRange(start: ArithExpr with SimplifiedExpr) extends Range {
  override def *(e: ArithExpr with SimplifiedExpr): Range = {
    StartFromRange(start * e)
  }
  override val min: ArithExpr with SimplifiedExpr = start
  override val max: ArithExpr with SimplifiedExpr = PosInf

  override def equals(that: Any): Boolean = that match {
    case r: StartFromRange => this.start == r.start
    case _ => false
  }

  override def visitAndRebuild(f: (ArithExpr) => ArithExpr): Range =
    StartFromRange(start.visitAndRebuild(f))

  override def substitute(subs: collection.Map[ArithExpr, ArithExpr]): Option[Range] =
    start.substitute(subs).map(StartFromRange(_))
}

case class GoesToRange(end: ArithExpr with SimplifiedExpr) extends Range {
  override def *(e: ArithExpr with SimplifiedExpr): Range = {
    GoesToRange(end * e)
  }
  override val min: ArithExpr with SimplifiedExpr = NegInf
  override val max: ArithExpr with SimplifiedExpr = end-1

  override def equals(that: Any): Boolean = that match {
    case r: GoesToRange => this.end == r.end
    case _ => false
  }

  override def visitAndRebuild(f: (ArithExpr) => ArithExpr): Range =
    GoesToRange(end.visitAndRebuild(f))

  override def substitute(subs: collection.Map[ArithExpr, ArithExpr]): Option[Range] =
    end.substitute(subs).map(GoesToRange(_))
}

case class RangeAdd(start: ArithExpr with SimplifiedExpr,
                    stop: ArithExpr with SimplifiedExpr,
                    step: ArithExpr with SimplifiedExpr) extends Range {

  override def *(e: ArithExpr with SimplifiedExpr): Range = {
    RangeAdd(start * e, stop * e, step)
  }

  private def checkBound(up: Boolean, result: ArithExpr with SimplifiedExpr): ArithExpr with SimplifiedExpr = {
    try {
      val evaluatedResult = result.evalDouble
      val evaluatedStart = start.evalDouble

      if ((evaluatedResult < evaluatedStart) != up)
        result
      else
        // Fall back on `start` is `result` is out of bounds
        start
    } catch {
      case NotEvaluableException() => result
    }
  }

  override val min: ArithExpr with SimplifiedExpr = {
    if (step.sign == Sign.Negative)
      checkBound(up=false, stop + 1)
    else
      start
  }
  override val max: ArithExpr with SimplifiedExpr = {
    if (step.sign == Sign.Positive)
      // TODO: this maximum is too high! consider the following range: RangeAdd(0,10,5) in which case the max is 5, not 9
      checkBound(up=true, stop - 1)
    else if (step.sign == Sign.Negative)
      start
    else
      stop
  }

  override def equals(that: Any): Boolean = that match {
    case r: RangeAdd => this.start == r.start && this.stop == r.stop && this.step == r.step
    case _ => false
  }

  override lazy val numVals: ArithExpr with SimplifiedExpr = {
    // TODO: Workaround. See TestExpr.numValsNotSimplifying
    // TODO: and TestExpr.ceilNotSimplifying
    if ((
          ArithExpr.isSmaller(start, stop).getOrElse(false) &&
          ArithExpr.isSmaller(max, start + step).getOrElse(false)) ||
        (
          ArithExpr.isSmaller(stop, start).getOrElse(false) &&
          ArithExpr.isSmaller(start + step, min).getOrElse(false)))
      Cst(1)
    else {
      if (this.step.sign == Sign.Positive)
        ceil((this.stop - this.start) /^ this.step)
      else
        ceil((this.start - this.stop) /^(-1 * this.step))
    }
  }

  override def visitAndRebuild(f: (ArithExpr) => ArithExpr): Range =
    RangeAdd(start.visitAndRebuild(f), stop.visitAndRebuild(f), step.visitAndRebuild(f))

  override def substitute(subs: collection.Map[ArithExpr, ArithExpr]): Option[Range] = {
    val bs = start.substitute(subs)
    val es = stop.substitute(subs)
    val ss = step.substitute(subs)
    if (bs.isEmpty && es.isEmpty && ss.isEmpty) {
      None
    } else {
      Some(RangeAdd(bs.getOrElse(start), es.getOrElse(stop), ss.getOrElse(step)))
    }
  }
}

case class RangeMul(start: ArithExpr with SimplifiedExpr,
                    stop: ArithExpr with SimplifiedExpr,
                    mul: ArithExpr with SimplifiedExpr) extends Range {
  override def *(e: ArithExpr with SimplifiedExpr): Range = {
    RangeMul(start * e, stop * e, mul)
  }
  override val min: ArithExpr with SimplifiedExpr = start
  override val max: ArithExpr with SimplifiedExpr = stop /^ mul

  override def equals(that: Any): Boolean = that match {
    case r: RangeMul => this.start == r.start && this.stop == r.stop && this.mul == r.mul
    case _ => false
  }

  override def visitAndRebuild(f: (ArithExpr) => ArithExpr): Range =
    RangeMul(start.visitAndRebuild(f), stop.visitAndRebuild(f), mul.visitAndRebuild(f))

  override def substitute(subs: collection.Map[ArithExpr, ArithExpr]): Option[Range] = {
    val bs = start.substitute(subs)
    val es = stop.substitute(subs)
    val ms = mul.substitute(subs)
    if (bs.isEmpty && es.isEmpty && ms.isEmpty) {
      None
    } else {
      Some(RangeMul(bs.getOrElse(start), es.getOrElse(stop), ms.getOrElse(mul)))
    }
  }
}

object ContinuousRange {
  def apply(start: ArithExpr, stop: ArithExpr): RangeAdd = {
    RangeAdd(start, stop, Cst(1))
  }
}

case object RangeUnknown extends Range {
  override val min: ArithExpr with SimplifiedExpr = ?
  override val max: ArithExpr with SimplifiedExpr = ?

  override def visitAndRebuild(f: (ArithExpr) => ArithExpr): Range = this

  override def substitute(subs: collection.Map[ArithExpr, ArithExpr]): Option[Range] = None
}

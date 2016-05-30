package apart
package arithmetic
package simplifier

object SimplifyCeiling {

  def apply(ae: ArithExpr): ArithExpr = {
    ae match {
      case c: Cst => c
      case _ =>
        try {
          val d = new CeilingFunction(ae).evalDbl
          assert(d.isValidInt)
          new Cst(d.toInt)
        } catch {
          case _: NotEvaluableException =>
            // ok let's try to evaluate ceiling of min and max
            try {
              val min = new CeilingFunction(ae.min).evalDbl
              val max = new CeilingFunction(ae.max).evalDbl
              if (min == max) {
                assert(min.isValidInt)
                return new Cst(min.toInt)
              }
            } catch {
              case _: NotEvaluableException => new CeilingFunction(ae) with SimplifiedExpr
              case e: Throwable => throw e
            }
            new CeilingFunction(ae) with SimplifiedExpr
          case e: Throwable => throw e
        }
    }
  }

}
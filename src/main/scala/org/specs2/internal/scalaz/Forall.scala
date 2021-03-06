package org.specs2.internal.scalaz

/** A universally quantified value */
trait Forall[P[_]] {
  def apply[A]: P[A]
}

object Forall {
  /** Universal quantification by doubly negating an existential. */
  type Not[A] = A => Nothing
  type DNE[P[_]] = Not[P[A]] forSome { type A }
  type CPS[P[_]] = Not[DNE[P]]

  /** Construct a universal quantifier by continuation-passing. */
  def apply[P[_]](p: CPS[P]): Forall[P] = new Forall[P] {
    def apply[A]: P[A] = {
      case class Control(arg : P[A]) extends Throwable
      val res = try {
        p((arg:P[A]) => throw new Control(arg))
      } catch {
        case Control(arg) => arg
        case e => throw e
      }
      res
    }
  }
}

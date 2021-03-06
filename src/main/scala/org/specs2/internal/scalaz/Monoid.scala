package org.specs2.internal.scalaz

/**
 * A categorical monoid.
 *
 * <p>
 * All monoid instances must satisfy the semigroup law and 2 additional laws:
 * <ol>
 * <li><strong>left identity</strong><br/><code>forall a. append(zero, a) == a</code></li>
 * <li><strong>right identity</strong><br/><code>forall a. append(a, zero) == a</code></li>
 * </p>
 */
trait Monoid[M] extends Zero[M] with Semigroup[M]

abstract class MonoidLow {
  implicit def monoid[M](implicit s: Semigroup[M], z: Zero[M]): Monoid[M] = new Monoid[M] {
    def append(s1: M, s2: => M) = s append (s1, s2)

    val zero = z.zero
  }
}

object Monoid extends MonoidLow {
  import Semigroup._
  import Zero._

  implicit def EitherLeftMonoid[A, B](implicit bz: Zero[B]) = monoid[Either.LeftProjection[A, B]](EitherLeftSemigroup, EitherLeftZero[A, B](bz))

  /** A monoid for sequencing Applicative effects. */
  def liftMonoid[F[_], M](implicit m: Monoid[M], a: Applicative[F]): Monoid[F[M]] = new Monoid[F[M]] {
    val zero: F[M] = a.pure(m.zero)
    def append(x: F[M], y: => F[M]): F[M] = a.liftA2(x, y, (m1: M, m2: M) => m.append(m1, m2))
  }
}

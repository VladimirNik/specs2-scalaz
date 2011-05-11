package org.specs2.internal.scalaz

package object effects {

  import Scalaz._
  
  private[effects] val realWorld = World[RealWorld]()

  /** Put a value in a state thread */
  def returnST[S, A](a: => A): ST[S, A] = ST(s => (s, a))

  /** Run a state thread */
  def runST[A](f: Forall[({type λ[S] = ST[S, A]})#λ]): A =
    f.apply.apply(realWorld)._2

  /** Allocates a fresh mutable reference. */
  def newVar[S, A](a: A): ST[S, STRef[S, A]] =
    returnST(new STRef[S, A](a))

  /** Allocates a fresh mutable array. */
  def newArr[S, A:Manifest](size: Int, z: A): ST[S, STArray[S, A]] =
    returnST(new STArray[S, A](size, z))

  /** Allows the result of a state transformer computation to be used lazily inside the computation. */
  def fixST[S, A](k: (=> A) => ST[S, A]): ST[S, A] = ST(s => {
    lazy val ans: (World[S], A) = k(r)(s)
    lazy val (_, r) = ans
    ans
  })

  /** Accumulates an integer-associated list into an immutable array. */
  def accumArray[F[_]:Foldable, A: Manifest, B](size: Int, f: (A, B) => A, z: A, ivs: F[(Int, B)]): ImmutableArray[A] = { 
    type STA[S] = ST[S, ImmutableArray[A]]
    runST(new Forall[STA] {
      def apply[S] = for {
        a <- newArr(size, z)
        _ <- ivs.foldMap(x => a.update(f, x._1, x._2))
        frozen <- a.freeze
      } yield frozen
    })
  }

  implicit def stMonoid[S, A: Monoid]: Monoid[ST[S, A]] = Monoid.liftMonoid[({ type λ[A] = ST[S, A] })#λ, A]
  implicit def ioMonoid[A: Monoid]: Monoid[IO[A]] = Monoid.liftMonoid

  implicit def stApplicative[S]: Applicative[({ type λ[A] = ST[S, A] })#λ] = stMonad[S]

  implicit def stMonad[S]: Monad[({ type λ[A] = ST[S, A] })#λ] = new Monad[({ type λ[A] = ST[S, A] })#λ] {
    def pure[A](a: => A) = returnST(a)
    def bind[A, B](m: ST[S, A], f: A => ST[S, B]): ST[S, B] = m flatMap f
  }

  /** Equality for STRefs is reference equality */
  implicit def stRefEqual[S, A]: Equal[STRef[S, A]] = new Equal[STRef[S, A]] {
    def equal(s1: STRef[S, A], s2: STRef[S, A]): Boolean = s1 == s2
  }

  // Implicit conversions between IO and ST
  implicit def stToIO[A](st: ST[RealWorld, A]): IO[A] = IO(st(_))
  implicit def ioToST[A](io: IO[A]): ST[RealWorld, A] = ST(io(_))
 
  // Standard I/O
  def getChar: IO[Char] = IO(rw => (rw, readChar))
  def putChar(c: Char): IO[Unit] = IO(rw => (rw, { print(c); () }))
  def putStr(s: String): IO[Unit] = IO(rw => (rw, { print(s); () }))
  def putStrLn(s: String): IO[Unit] = IO((rw => (rw, { println(s); () })))
  def readLn: IO[String] = IO(rw => (rw, readLine))
  def putOut[A](a: A): IO[Unit] = IO(rw => (rw, { print(a); () }))

  // Mutable variables in the IO monad
  def newIORef[A](a: => A) = stToIO(newVar(a)) >>= (v => new IORef(v).pure[IO])

  /** Throw the given error in the IO monad. */
  def throwIO[A](e: Throwable): IO[A] = IO(rw => (rw, throw e))

  type RunInBase[M[_], Base[_]] = Forall[({type λ[B] = M[B] => Base[M[B]]})#λ]

  def idLiftControl[M[_]: Monad, A](f: RunInBase[M, M] => M[A]): M[A] = 
    f(new RunInBase[M, M] { def apply[B] = (x: M[B]) => x.pure[M] })

  def controlIO[M[_], A](f: RunInBase[M, IO] => IO[M[A]])(implicit m: MonadControlIO[M]): M[A] = {
    implicit val monad: Monad[M] = m.value
    m.liftControlIO(f).join
  }

  /**
   * Register a finalizer in the current region. When the region terminates,
   * all registered finalizers will be performed if they're not duplicated to a parent region.
   */
  def onExit[S, P[_]: MonadIO](finalizer: IO[Unit]):
    RegionT[S, P, FinalizerHandle[({type λ[α] = RegionT[S, P, α]})#λ]] =
      RegionT(kleisli(hsIORef => (for {
        refCntIORef <- newIORef(1)
        val h = RefCountedFinalizer(finalizer, refCntIORef)
        _ <- hsIORef.mod(h :: _)
      } yield FinalizerHandle[({type λ[α] = RegionT[S, P, α]})#λ](h)).liftIO[P]))


  /**
   * Execute a region inside its parent region P. All resources which have been opened in the given
   * region and which haven't been duplicated using "dup", will be closed on exit from this function
   * whether by normal termination or by raising an exception.
   * Also all resources which have been duplicated to this region from a child region are closed
   * on exit if they haven't been duplicated themselves.
   * The Forall quantifier prevents resources from being returned by this function.
   */
  def runRegionT[P[_]:MonadControlIO, A](r: Forall[({type λ[S] = RegionT[S, P, A]})#λ]): P[A] = {
    def after(hsIORef: IORef[List[RefCountedFinalizer]]) = for {
      hs <- hsIORef.read
      _ <- hs.traverse_ {
        case RefCountedFinalizer(finalizer, refCntIORef) => for {
          refCnt <- refCntIORef.mod(_ - 1)
          _ <- if (refCnt == 0) finalizer else ().pure[IO]
        } yield ()
      }
    } yield ()
    newIORef(List[RefCountedFinalizer]()).bracketIO(after)(s => r.apply.value(s))
  }

  /** Duplicates a handle to its parent region. */
  def dup[H[_[_]]: Dup, PP[_]:MonadIO, CS, PS](h: H[({type λ[α] = RegionT[CS, ({type λ[β] = RegionT[PS, PP, β]})#λ, α]})#λ]):
    RegionT[CS, ({type λ[α] = RegionT[PS, PP, α]})#λ, H[({type λ[β] = RegionT[PS, PP, β]})#λ]] = implicitly[Dup[H]].dup.apply(h)
}


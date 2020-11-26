/*
 * Copyright 2020 TimWSpence
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.timwspence.cats.stm

import cats.effect.{Concurrent, Resource}
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.{Monad, Monoid, MonoidK, StackSafeMonad}
import cats.implicits._
import scala.annotation.tailrec

trait STM[F[_]] {
  import Internals._

  //TODO should we split this into public trait and private impl?
  class TVar[A] private[stm] (
    val id: TVarId,
    @volatile var value: A,
    val lock: Semaphore[F],
    val retries: Ref[F, List[Deferred[F, Unit]]]
  ) {
    def get: Txn[A] = Get(this)

    def modify(f: A => A): Txn[Unit] = Modify(this, f)

    def set(a: A): Txn[Unit] = modify(_ => a)

    private[stm] def registerRetry(signal: Deferred[F, Unit]): F[Unit] = retries.update(signal :: _)

  }

  object TVar {
    def of[A](a: A): Txn[TVar[A]] = Alloc(a)
  }

  sealed abstract class Txn[+A] {

    /**
      * Functor map on `STM`.
      */
    final def map[B](f: A => B): Txn[B] = Bind(this, f.andThen(Pure(_)))

    /**
      * Monadic bind on `STM`.
      */
    final def flatMap[B](f: A => Txn[B]): Txn[B] = Bind(this, f)

    /**
      * Try an alternative `STM` action if this one retries.
      */
    final def orElse[B >: A](other: Txn[B]): Txn[B] = OrElse(this, other)
  }

  object Txn {
    def pure[A](a: A): Txn[A] = Pure(a)

    def retry[A]: Txn[A] = Retry

    implicit val txnMonad: StackSafeMonad[Txn] with MonoidK[Txn] =
      new StackSafeMonad[Txn] with MonoidK[Txn] {
        override def flatMap[A, B](fa: Txn[A])(f: A => Txn[B]): Txn[B] = fa.flatMap(f)

        override def pure[A](x: A): Txn[A] = Txn.pure(x)

        override def empty[A]: Txn[A] = Txn.retry

        override def combineK[A](x: Txn[A], y: Txn[A]): Txn[A] = x.orElse(y)
      }

    implicit def stmMonoid[A](implicit M: Monoid[A]): Monoid[Txn[A]] =
      new Monoid[Txn[A]] {
        override def empty: Txn[A] = Txn.pure(M.empty)

        override def combine(x: Txn[A], y: Txn[A]): Txn[A] =
          for {
            l <- x
            r <- y
          } yield M.combine(l, r)
      }
  }

  //TODO do we need this or will it be found successfully on the companion object?
  // implicit val T: Monad[Txn]

  def commit[A](txn: Txn[A]): F[A]

  private[stm] object Internals {

    case class Pure[A](a: A)                                extends Txn[A]
    case class Alloc[A](a: A)                               extends Txn[TVar[A]]
    case class Bind[A, B](stm: Txn[B], f: B => Txn[A])      extends Txn[A]
    case class Get[A](tvar: TVar[A])                        extends Txn[A]
    case class Modify[A](tvar: TVar[A], f: A => A)          extends Txn[Unit]
    case class OrElse[A](attempt: Txn[A], fallback: Txn[A]) extends Txn[A]
    case class Abort(error: Throwable)                      extends Txn[Nothing]
    case object Retry                                       extends Txn[Nothing]

    sealed trait TResult[+A]                    extends Product with Serializable
    case class TSuccess[A](value: A)      extends TResult[A]
    case class TFailure(error: Throwable) extends TResult[Nothing]
    case object TRetry                    extends TResult[Nothing]

    type Cont   = Any => Txn[Any]
    type TVarId = Long
    type TxId   = Long

    case class TLog(private var map: Map[TVarId, TLogEntry]) {

      def values: Iterable[TLogEntry] = map.values

      def get(tvar: TVar[Any]): Any =
        if (map.contains(tvar.id))
          map(tvar.id).unsafeGet[Any]
        else {
          val current = tvar.value
          map = map + (tvar.id -> TLogEntry(tvar, current))
          current
        }

      def modify(tvar: TVar[Any], f: Any => Any): Unit =
        if (map.contains(tvar.id)) {
          val e       = map(tvar.id)
          val current = e.unsafeGet[Any]
          val entry   = e.unsafeSet[Any](f(current))
          map = map + (tvar.id -> entry)
        } else {
          val current = tvar.value
          map = map + (tvar.id -> TLogEntry(tvar, f(current)))
        }

      def isDirty: Boolean = values.exists(_.isDirty)

      def snapshot(): TLog = TLog(map)

      def delta(tlog: TLog): TLog =
        TLog(
          map.foldLeft(tlog.map) { (acc, p) =>
            val (id, e) = p
            if (acc.contains(id)) acc else acc + (id -> TLogEntry(e.tvar, e.tvar.value))
          }
        )

      def commit(implicit F: Concurrent[F]): F[Unit] = F.delay(values.foreach(_.commit()))

      def signal(implicit F: Monad[F]): F[Unit] =
        values.toList.traverse_(e =>
          for {
            signals <- e.tvar.retries.getAndSet(Nil)
            _       <- signals.traverse_(_.complete(()))
          } yield ()
        )

      def registerRetry(signal: Deferred[F, Unit])(implicit F: Monad[F]): F[Unit] =
        values.toList.traverse_(e => e.tvar.registerRetry(signal))

      // def registerRetry(txId: TxId, fiber: RetryFiber): Unit = {
      //   fiber.tvars = values.map(_.tvar).toSet
      //   values.foreach { e =>
      //     e.tvar.registerRetry(txId, fiber)
      //   }
      // }

      // def collectPending(): List[RetryFiber] = {
      //   var pending: Map[TxId, RetryFiber] = Map.empty
      //   values.foreach { e =>
      //     val p = e.tvar.unregisterAll()
      //     pending = pending ++ p

      //   }
      //   pending.values.foreach { retry =>
      //     retry.tvars.foreach { t =>
      //       t.unregisterRetry(retry.txId)
      //     }
      //   }
      //   pending.values.toList
      // }

    }

    object TLog {
      def empty: TLog = TLog(Map.empty)
    }

    abstract class TLogEntry { self =>
      type Repr
      var current: Repr
      val initial: Repr
      val tvar: TVar[Repr]

      def unsafeGet[A]: A = current.asInstanceOf[A]

      def unsafeSet[A](a: A): TLogEntry = TLogEntry[Repr](tvar, a.asInstanceOf[Repr])

      def commit(): Unit = tvar.value = current

      def isDirty: Boolean = initial != tvar.value.asInstanceOf[Repr]

      def snapshot(): TLogEntry =
        new TLogEntry {
          override type Repr = self.Repr
          override var current: Repr    = self.current
          override val initial: Repr    = self.initial
          override val tvar: TVar[Repr] = self.tvar
        }

    }

    object TLogEntry {

      def apply[A](tvar0: TVar[A], current0: A): TLogEntry =
        new TLogEntry {
          override type Repr = A
          override var current: A    = current0
          override val initial: A    = tvar0.value.asInstanceOf[A]
          override val tvar: TVar[A] = tvar0
        }

    }

    def eval[A](idGen: Ref[F, Long], txn: Txn[A])(implicit F: Concurrent[F]): F[(TResult[A], TLog)] = {
      var conts: List[Cont]                             = Nil
      var fallbacks: List[(Txn[Any], TLog, List[Cont])] = Nil
      var log: TLog                                     = TLog.empty

      //Construction of a TVar requires allocating state but we want this to be tail-recursive
      //and non-effectful so we trampoline it with run
      @tailrec
      def go(
        nextId: TVarId,
        lock: Semaphore[F],
        ref: Ref[F, List[Deferred[F, Unit]]],
        txn: Txn[Any]
      ): Either[Txn[Any], TResult[Any]] =
        txn match {
          case Pure(a) =>
            if (conts.isEmpty)
              Right(TSuccess(a))
            else {
              val f = conts.head
              conts = conts.tail
              go(nextId, lock, ref, f(a))
            }
          case Alloc(a) => Left(Pure((new TVar(nextId, a, lock, ref))))
          case Bind(stm, f) =>
            conts = f :: conts
            go(nextId, lock, ref, stm)
          case Get(tvar)       => go(nextId, lock, ref, Pure(log.get(tvar)))
          case Modify(tvar, f) => go(nextId, lock, ref, Pure(log.modify(tvar.asInstanceOf[TVar[Any]], f)))
          case OrElse(attempt, fallback) =>
            fallbacks = (fallback, log.snapshot(), conts) :: fallbacks
            go(nextId, lock, ref, attempt)
          case Abort(error) =>
            Right(TFailure(error))
          case Retry =>
            if (fallbacks.isEmpty) Right(TRetry)
            else {
              val (fb, lg, cts) = fallbacks.head
              log = log.delta(lg)
              conts = cts
              fallbacks = fallbacks.tail
              go(nextId, lock, ref, fb)
            }
        }

      def run(txn: Txn[Any]): F[TResult[Any]] =
        for {
          id   <- idGen.updateAndGet(_ + 1)
          lock <- Semaphore[F](1)
          ref  <- Ref.of[F, List[Deferred[F, Unit]]](Nil)
          //Trampoline so we can generate a new id/lock/ref to supply
          //if we need to contruct a new tvar
          res <- go(id, lock, ref, txn) match {
            case Left(t)  => run(t)
            case Right(v) => F.pure(v)
          }
        } yield res

      //Safe by construction
      run(txn.asInstanceOf[Txn[Any]]).map(res => res.asInstanceOf[TResult[A]] -> log)
    }

  }

}

object STM {
  def apply[F[_]](implicit F: Concurrent[F]): Resource[F, STM[F]] =
    for {
      idGen  <- Resource.liftF(Ref.of[F, Long](0))
      global <- Resource.liftF(Semaphore[F](1)) //TODO remove this and just lock each tvar
    } yield new STM[F] {
      import Internals._

      def commit[A](txn: Txn[A]): F[A] =
        for {
          signal <- Deferred[F, Unit]
          p      <- eval(idGen, txn)
          (res, log) = p
          r <- res match {
            //Double-checked dirtyness
            case TSuccess(a) =>
              if (log.isDirty) commit(txn)
              else
                for {
                  committed <- global.withPermit(
                    if (log.isDirty) F.pure(false)
                    else
                      log.commit.as(true)
                  )
                  r <- if (committed) log.signal >> F.pure(a) else commit(txn)
                } yield r
            case TFailure(e) => if (log.isDirty) commit(txn) else F.raiseError(e)
            //TODO make retry blocking safely cancellable
            case TRetry =>
              if (log.isDirty)
                commit(txn)
              else log.registerRetry(signal) >> signal.get >> commit(txn)
          }
        } yield r
    }

}

// import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
// import java.util.concurrent.{Executors, ThreadFactory}

// import scala.annotation.tailrec

// import cats.effect.concurrent.Deferred
// import cats.effect.implicits._
// import cats.effect.{Blocker, Concurrent, ContextShift}
// import cats.implicits._
// import cats.{Monoid, MonoidK, StackSafeMonad}
// import io.github.timwspence.cats.stm.STM.internal._

// /**
//   * Monad representing transactions involving one or more
//   * `TVar`s.
//   *
//   * This design was inspired by [Beautiful Concurrency](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/beautiful.pdf) and informed by ZIO
//   * which has a common origin in that paper via the [stm package](http://hackage.haskell.org/package/stm).
//   */
// sealed abstract class STM[+A] {

//   /**
//     * Functor map on `STM`.
//     */
//   final def map[B](f: A => B): STM[B] = Bind(this, f.andThen(Pure(_)))

//   /**
//     * Monadic bind on `STM`.
//     */
//   final def flatMap[B](f: A => STM[B]): STM[B] = Bind(this, f)

//   /**
//     * Try an alternative `STM` action if this one retries.
//     */
//   final def orElse[B >: A](fallback: STM[B]): STM[B] = OrElse(this, fallback)

//   /**
//     * Run transaction atomically
//     */
//   final def atomically[F[+_]: Concurrent: ContextShift]: F[A] = STM.atomically[F](this)

// }

// object STM {

//   val blocker: Blocker = Blocker.liftExecutorService(
//     Executors.newCachedThreadPool(
//       new ThreadFactory {
//         val ctr = new AtomicInteger(0)
//         def newThread(r: Runnable): Thread = {
//           val back = new Thread(r, s"stm-blocking-${ctr.getAndIncrement()}")
//           back.setDaemon(true)
//           back
//         }
//       }
//     )
//   )

//   /**
//     * Commit the `STM` action as an `IO` action. The mutable
//     * state of `TVar`s is only modified when this is invoked
//     * (hence the `IO` context - modifying mutable state
//     * is a side effect).
//     */
//   def atomically[F[_]] = new AtomicallyPartiallyApplied[F]

//   /**
//     * Convenience definition.
//     */
//   def retry[A]: STM[A] = Retry

//   /**
//     * Fallback to an alternative `STM` action if the first one
//     * retries. The whole `orElse` action is retried if both
//     * {@code attempt} and {@code fallback} retry.
//     */
//   def orElse[A](attempt: STM[A], fallback: STM[A]): STM[A] = attempt.orElse(fallback)

//   /**
//     * Retry transaction until {@code check} succeeds.
//     */
//   def check(check: => Boolean): STM[Unit] = if (check) unit else retry

//   /**
//     * Abort a transaction. Will raise {@code error} whenever
//     * evaluated with [[atomically]].
//     */
//   def abort[A](error: Throwable): STM[A] = Abort(error)

//   /**
//     * Monadic return.
//     */
//   def pure[A](a: A): STM[A] = Pure(a)

//   /**
//     * Alias for `pure(())`.
//     */
//   val unit: STM[Unit] = pure(())

//   //TODO can we make an ApplicativeError/MonadError here?
//   implicit val stmMonad: StackSafeMonad[STM] with MonoidK[STM] =
//     new StackSafeMonad[STM] with MonoidK[STM] {
//       override def flatMap[A, B](fa: STM[A])(f: A => STM[B]): STM[B] = fa.flatMap(f)

//       override def pure[A](x: A): STM[A] = STM.pure(x)

//       override def empty[A]: STM[A] = STM.retry

//       override def combineK[A](x: STM[A], y: STM[A]): STM[A] = x.orElse(y)
//     }

//   implicit def stmMonoid[A](implicit M: Monoid[A]): Monoid[STM[A]] =
//     new Monoid[STM[A]] {
//       override def empty: STM[A] = STM.pure(M.empty)

//       override def combine(x: STM[A], y: STM[A]): STM[A] =
//         for {
//           l <- x
//           r <- y
//         } yield M.combine(l, r)
//     }

//   final class AtomicallyPartiallyApplied[F[_]] {

//     def apply[A](stm: STM[A])(implicit F: Concurrent[F], CS: ContextShift[F]): F[A] =
//       for {
//         //TODO this shouldn't need to be a critical section
//         e <- criticalSection[F](eval(stm))
//         (r, log) = e
//         res <- r match {
//           case TSuccess(res) =>
//             for {
//               x <- criticalSection[F] {
//                 var commit                    = false
//                 var pending: List[RetryFiber] = Nil
//                 if (!log.isDirty) {
//                   commit = true
//                   log.commit()
//                   pending = log.collectPending()
//                 }
//                 commit -> pending
//               }
//               r <-
//                 if (x._1)
//                   x._2.traverse_(_.run.asInstanceOf[F[Unit]]).start >>
//                     F.pure(res)
//                 else apply(stm)
//             } yield r
//           case TFailure(e) =>
//             for {
//               dirty <- criticalSection[F](log.isDirty)
//               res   <- if (dirty) apply(stm) else F.raiseError[A](e)
//             } yield res
//           case TRetry =>
//             for {
//               txId  <- F.delay(IdGen.incrementAndGet())
//               defer <- Deferred[F, Either[Throwable, A]]
//               retryFiber = RetryFiber.make(stm, txId, defer)
//               dirty <- criticalSection[F] {
//                 if (log.isDirty) true
//                 else {
//                   log.registerRetry(txId, retryFiber)
//                   false
//                 }
//               }
//               res <-
//                 if (dirty) apply(stm)
//                 else
//                   for {
//                     e   <- defer.get
//                     res <- e.fold(F.raiseError[A](_), F.pure(_))
//                   } yield res
//             } yield res
//         }

//       } yield res

//   }

//   private[stm] object internal {

//     //TODO this shouldn't be blocking with synchronized and requiring
//     //another (blocking) threadpool
//     def criticalSection[F[_]] =
//       new CriticialSectionPartiallyApplied[F]

//     class CriticialSectionPartiallyApplied[F[_]]() {
//       def apply[A](a: => A)(implicit F: Concurrent[F], CS: ContextShift[F]): F[A] =
//         blocker.blockOn(
//           F.delay {
//             STM.synchronized {
//               a
//             }
//           }
//         )
//     }

//     val IdGen = new AtomicLong()

//     final case class Pure[A](a: A)                                extends STM[A]
//     final case class Delay[A](thunk: () => A)                     extends STM[A]
//     final case class Alloc[A](a: A)                               extends STM[TVar[A]]
//     final case class Bind[A, B](stm: STM[B], f: B => STM[A])      extends STM[A]
//     final case class Get[A](tvar: TVar[A])                        extends STM[A]
//     final case class Modify[A](tvar: TVar[A], f: A => A)          extends STM[Unit]
//     final case class OrElse[A](attempt: STM[A], fallback: STM[A]) extends STM[A]
//     final case class Abort(error: Throwable)                      extends STM[Nothing]
//     final case object Retry                                       extends STM[Nothing]

//     sealed trait TResult[+A]                    extends Product with Serializable
//     final case class TSuccess[A](value: A)      extends TResult[A]
//     final case class TFailure(error: Throwable) extends TResult[Nothing]
//     final case object TRetry                    extends TResult[Nothing]

//     abstract class RetryFiber {

//       type Effect[X]
//       type Result

//       val defer: Deferred[Effect, Either[Throwable, Result]]
//       val stm: STM[Result]
//       val txId: TxId
//       var tvars: Set[TVar[_]] = Set.empty
//       implicit def F: Concurrent[Effect]
//       implicit def CS: ContextShift[Effect]

//       def run: Effect[Unit] =
//         for {
//           //TODO this shouldn't need to be a critical section
//           e <- criticalSection[Effect](eval(stm))
//           (r, log) = e
//           res <- r match {
//             case TSuccess(res) =>
//               for {
//                 x <- criticalSection[Effect] {
//                   var commit                    = false
//                   var pending: List[RetryFiber] = Nil
//                   if (!log.isDirty) {
//                     commit = true
//                     log.commit()
//                     pending = log.collectPending()
//                   }
//                   (commit, pending)
//                 }
//                 _ <-
//                   if (x._1)
//                     x._2.traverse_(_.run.asInstanceOf[Effect[Unit]]).start >>
//                       defer.complete(Right(res))
//                   else run
//               } yield ()
//             case TFailure(e) =>
//               for {
//                 dirty <- criticalSection[Effect](log.isDirty)
//                 res   <- if (dirty) run else defer.complete(Left(e))
//               } yield res
//             case TRetry =>
//               for {
//                 dirty <- criticalSection[Effect] {
//                   if (log.isDirty) true
//                   else {
//                     log.registerRetry(txId, this)
//                     false
//                   }
//                 }
//                 res <- if (dirty) run else F.unit
//               } yield res
//           }
//         } yield res
//     }

//     object RetryFiber {
//       type Aux[F[_], A] = RetryFiber { type Effect[X] = F[X]; type Result = A }

//       def make[F[_], A](stm0: STM[A], txId0: TxId, defer0: Deferred[F, Either[Throwable, A]])(implicit
//         F0: Concurrent[F],
//         CS0: ContextShift[F]
//       ): RetryFiber.Aux[F, A] =
//         new RetryFiber {
//           type Effect[X] = F[X]
//           type Result    = A

//           val defer: Deferred[F, Either[Throwable, A]] = defer0
//           val stm: STM[A]                              = stm0
//           val txId                                     = txId0
//           implicit def F: Concurrent[F]                = F0
//           implicit def CS: ContextShift[F]             = CS0
//         }
//     }

//     def eval[A](stm: STM[A]): (TResult[A], TLog) = {
//       var conts: List[Cont]                             = Nil
//       var fallbacks: List[(STM[Any], TLog, List[Cont])] = Nil
//       var log: TLog                                     = TLog.empty

//       @tailrec
//       def go(stm: STM[Any]): TResult[Any] =
//         stm match {
//           case Pure(a) =>
//             if (conts.isEmpty)
//               TSuccess(a)
//             else {
//               val f = conts.head
//               conts = conts.tail
//               go(f(a))
//             }
//           case Delay(thunk) => go(Pure(thunk()))
//           case Alloc(a)     => go(Delay(() => (new TVar(IdGen.incrementAndGet(), a, new AtomicReference(Map())))))
//           case Bind(stm, f) =>
//             conts = f :: conts
//             go(stm)
//           case Get(tvar)       => go(Pure(log.get(tvar)))
//           case Modify(tvar, f) => go(Pure(log.modify(tvar.asInstanceOf[TVar[Any]], f)))
//           case OrElse(attempt, fallback) =>
//             fallbacks = (fallback, log.snapshot(), conts) :: fallbacks
//             go(attempt)
//           case Abort(error) =>
//             TFailure(error)
//           case Retry =>
//             if (fallbacks.isEmpty) TRetry
//             else {
//               val (fb, lg, cts) = fallbacks.head
//               log = log.delta(lg)
//               conts = cts
//               fallbacks = fallbacks.tail
//               go(fb)
//             }
//         }

//       //Safe by construction
//       go(stm).asInstanceOf[TResult[A]] -> log
//     }

//     case class TLog(private var map: Map[TVarId, TLogEntry]) {

//       def values: Iterable[TLogEntry] = map.values

//       def get(tvar: TVar[Any]): Any =
//         if (map.contains(tvar.id))
//           map(tvar.id).unsafeGet[Any]
//         else {
//           val current = tvar.value
//           map = map + (tvar.id -> TLogEntry(tvar, current))
//           current
//         }

//       def modify(tvar: TVar[Any], f: Any => Any): Unit =
//         if (map.contains(tvar.id)) {
//           val e       = map(tvar.id)
//           val current = e.unsafeGet[Any]
//           val entry   = e.unsafeSet[Any](f(current))
//           map = map + (tvar.id -> entry)
//         } else {
//           val current = tvar.value
//           map = map + (tvar.id -> TLogEntry(tvar, f(current)))
//         }

//       def isDirty: Boolean = values.exists(_.isDirty)

//       def snapshot(): TLog = TLog(map)

//       def delta(tlog: TLog): TLog =
//         TLog(
//           map.foldLeft(tlog.map) { (acc, p) =>
//             val (id, e) = p
//             if (acc.contains(id)) acc else acc + (id -> TLogEntry(e.tvar, e.tvar.value))
//           }
//         )

//       def commit(): Unit = values.foreach(_.commit())

//       def registerRetry(txId: TxId, fiber: RetryFiber): Unit = {
//         fiber.tvars = values.map(_.tvar).toSet
//         values.foreach { e =>
//           e.tvar.registerRetry(txId, fiber)
//         }
//       }

//       def collectPending(): List[RetryFiber] = {
//         var pending: Map[TxId, RetryFiber] = Map.empty
//         values.foreach { e =>
//           val p = e.tvar.unregisterAll()
//           pending = pending ++ p

//         }
//         pending.values.foreach { retry =>
//           retry.tvars.foreach { t =>
//             t.unregisterRetry(retry.txId)
//           }
//         }
//         pending.values.toList
//       }

//     }

//     object TLog {
//       def empty: TLog = TLog(Map.empty)
//     }

//     type Cont = Any => STM[Any]

//     type TxId   = Long
//     type TVarId = Long

//     //Can we replace this with TVar[_] and Any?
//     abstract class TLogEntry { self =>
//       type Repr
//       var current: Repr
//       val initial: Repr
//       val tvar: TVar[Repr]

//       def unsafeGet[A]: A = current.asInstanceOf[A]

//       def unsafeSet[A](a: A): TLogEntry = TLogEntry[Repr](tvar, a.asInstanceOf[Repr])

//       def commit(): Unit = tvar.value = current

//       def isDirty: Boolean = initial != tvar.value.asInstanceOf[Repr]

//       def snapshot(): TLogEntry =
//         new TLogEntry {
//           override type Repr = self.Repr
//           override var current: Repr    = self.current
//           override val initial: Repr    = self.initial
//           override val tvar: TVar[Repr] = self.tvar
//         }

//     }

//     object TLogEntry {

//       def apply[A](tvar0: TVar[A], current0: A): TLogEntry =
//         new TLogEntry {
//           override type Repr = A
//           override var current: A    = current0
//           override val initial: A    = tvar0.value.asInstanceOf[A]
//           override val tvar: TVar[A] = tvar0
//         }

//     }

//   }

// }

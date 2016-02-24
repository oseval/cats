package cats
package std

import cats.syntax.all._
import cats.data.Xor

import scala.util.{Success, Try}
import scala.concurrent.{Promise, ExecutionContext, Future}

trait FutureInstances extends FutureInstances1 {

  implicit def futureInstance(implicit ec: ExecutionContext): MonadError[Future, Throwable] with CoflatMap[Future] =
    new FutureCoflatMap with MonadError[Future, Throwable]{
      def pure[A](x: A): Future[A] = Future.successful(x)

      override def pureEval[A](x: Eval[A]): Future[A] = Future(x.value)

      def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = flatMapTry(fa)(_ map f)

      def handleErrorWith[A](fea: Future[A])(f: Throwable => Future[A]): Future[A] = recoverWith(fea) { case x => f(x) }

      def raiseError[A](e: Throwable): Future[A] = Future.failed(e)

      override def handleError[A](fea: Future[A])(f: Throwable => A): Future[A] = recover(fea) { case x => f(x) }

      override def attempt[A](fa: Future[A]): Future[Throwable Xor A] =
        recover(map(fa)(Xor.right[Throwable, A](_))) { case x => Xor.left(x) }

      override def recover[A](fa: Future[A])(pf: PartialFunction[Throwable, A]): Future[A] =
        mapTry(fa)(_ recover pf)

      override def recoverWith[A](fa: Future[A])(pf: PartialFunction[Throwable, Future[A]]): Future[A] =
        flatMapTry[A, A](fa)(t => if (t.isFailure) t.failed.map(pf.lift(_).getOrElse(fa)) else t.map(_ => fa))

      override def map[A, B](fa: Future[A])(f: A => B): Future[B] = mapTry(fa)(_ map f)

      private def mapTry[A, B](fa: Future[A])(f: Try[A] => Try[B]): Future[B] =
        if (fa.isCompleted && fa.value.isDefined) Future.fromTry(Try(f(fa.value.get)).flatten)
        else {
          val p = Promise[B]
          fa.onComplete(t => p.complete(Try(f(t)).flatten))
          p.future
        }

      private def flatMapTry[A, B](fa: Future[A])(f: Try[A] => Try[Future[B]]): Future[B] = {
        val p = Promise[B]
        mapTry(fa) { case t =>
          Try(f(t)).flatten
            .map { res =>
              if (res.isCompleted && res.value.isDefined) p.complete(res.value.get)
              else p.completeWith(res)
            }
            .recover { case e => p.failure(e) }
        }
        p.future
      }
    }

  implicit def futureGroup[A: Group](implicit ec: ExecutionContext): Group[Future[A]] =
    new FutureGroup[A]
}

private[std] sealed trait FutureInstances1 extends FutureInstances2 {
  implicit def futureMonoid[A: Monoid](implicit ec: ExecutionContext): Monoid[Future[A]] =
    new FutureMonoid[A]
}

private[std] sealed trait FutureInstances2 {
  implicit def futureSemigroup[A: Semigroup](implicit ec: ExecutionContext): Semigroup[Future[A]] =
    new FutureSemigroup[A]
}

private[cats] abstract class FutureCoflatMap(implicit ec: ExecutionContext) extends CoflatMap[Future] {
  def map[A, B](fa: Future[A])(f: A => B): Future[B] = fa.map(f)
  def coflatMap[A, B](fa: Future[A])(f: Future[A] => B): Future[B] = Future(f(fa))
}

private[cats] class FutureSemigroup[A: Semigroup](implicit ec: ExecutionContext) extends Semigroup[Future[A]] {
  def combine(fx: Future[A], fy: Future[A]): Future[A] =
    (fx zip fy).map { case (x, y) => x |+| y }
}

private[cats] class FutureMonoid[A](implicit A: Monoid[A], ec: ExecutionContext) extends FutureSemigroup[A] with Monoid[Future[A]] {
  def empty: Future[A] =
    Future.successful(A.empty)
}

private[cats] class FutureGroup[A](implicit A: Group[A], ec: ExecutionContext) extends FutureMonoid[A] with Group[Future[A]] {
  def inverse(fx: Future[A]): Future[A] =
    fx.map(_.inverse)
  override def remove(fx: Future[A], fy: Future[A]): Future[A] =
    (fx zip fy).map { case (x, y) => x |-| y }
}

package external

import cats.Functor
import cats.syntax.functor.*

object CatsUsage:
  def doubleAll[F[_]: Functor](fa: F[Int]): F[Int] = fa.map(_ * 2)

  val doubled: List[Int] = doubleAll(List(1, 2, 3))
  val optionDoubled: Option[Int] = doubleAll(Some(5))

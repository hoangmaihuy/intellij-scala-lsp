package example

import cats.Monad
import cats.syntax.all.*
import zio.{ZIO, Task}

/** Uses external library types for testing definition/references on external symbols. */
object ExternalDeps:

  def combineOptions[A](a: Option[A], b: Option[A])(using Monad[Option]): Option[A] =
    a.flatMap(_ => b)

  def greetTask(name: String): Task[String] =
    ZIO.succeed(s"Hello, $name")

  val shapes: List[Shape] = List(Circle(1.0), Rectangle(2.0, 3.0))
  val mapped: Option[Int] = Option(42).map(_ + 1)

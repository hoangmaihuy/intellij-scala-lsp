package service

import hierarchy.*
import cats.syntax.show.*

class ShapeRepository extends Repository[Shape]:
  private val shapes: List[Shape] = List(
    Circle(5.0),
    Rectangle(3.0, 4.0),
    Circle(1.0),
    Rectangle(10.0, 10.0)
  )

  override def getAll: List[Shape] = shapes
  override def findBy(predicate: Shape => Boolean): Option[Shape] = shapes.find(predicate)

  def describe(shape: Shape): String = shape.show

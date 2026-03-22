package example

class ShapeService(shapes: List[Shape]):
  def totalArea: Double = shapes.map(_.area).sum

  def findCircles: List[Circle] =
    shapes.collect { case c: Circle => c }

  def findLargest: Option[Shape] =
    shapes.maxByOption(_.area)

  def describeAll: List[String] =
    shapes.map(_.describe)

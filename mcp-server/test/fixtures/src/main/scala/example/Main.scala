package example

import example.*

object Main:
  val shapes: List[Shape] = List(
    Circle(5.0),
    Rectangle(3.0, 4.0),
    Circle(1.0),
    Rectangle(10.0, 10.0)
  )

  val service = ShapeService(shapes)

  def run(): Unit =
    val total = service.totalArea
    val circles = service.findCircles
    println(s"Total area: $total, circles: ${circles.size}")

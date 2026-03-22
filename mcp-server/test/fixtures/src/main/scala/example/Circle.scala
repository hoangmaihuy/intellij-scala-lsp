package example

case class Circle(radius: Double) extends Shape:
  override def area: Double = math.Pi * radius * radius
  override def perimeter: Double = 2 * math.Pi * radius

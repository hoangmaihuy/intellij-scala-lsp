package example

case class Rectangle(width: Double, height: Double) extends Shape:
  override def area: Double = width * height
  override def perimeter: Double = 2 * (width + height)
  def isSquare: Boolean = width == height

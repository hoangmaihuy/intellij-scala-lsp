package hierarchy

sealed trait Shape:
  def area: Double
  def perimeter: Double
  def describe: String = s"${getClass.getSimpleName}: area=$area, perimeter=$perimeter"

object Shape:
  def unit: Shape = Circle(1.0)

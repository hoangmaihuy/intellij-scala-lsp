package example

/** A geometric shape with area and perimeter. */
trait Shape:
  def area: Double
  def perimeter: Double
  def describe: String = s"${getClass.getSimpleName}: area=$area"

object Shape:
  def unit: Shape = Circle(1.0)

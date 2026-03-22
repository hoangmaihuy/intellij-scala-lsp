package example

/** A custom shape wrapper for testing suffix matching. */
class CustomShape(val inner: Shape) extends Shape:
  override def area: Double = inner.area
  override def perimeter: Double = inner.perimeter
  override def describe: String = s"Custom(${inner.describe})"

package hierarchy

import cats.Show

given shapeShow: Show[Shape] with
  def show(s: Shape): String = s.describe

extension (s: Shape)
  def scaled(factor: Double): Shape = s match
    case Circle(r) => Circle(r * factor)
    case Rectangle(w, h) => Rectangle(w * factor, h * factor)

import hierarchy.*
import service.*
import external.CatsUsage

object Main:
  val repo = ShapeRepository()
  val service = ShapeService(repo)

  def run(): Unit =
    val total = service.totalArea
    val circles = service.findCircles
    val largest = service.findLargest
    val descriptions = service.describeAll
    val doubled = CatsUsage.doubled
    println(s"Total area: $total, circles: ${circles.size}, largest: $largest")

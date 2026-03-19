package example.calls

object ServiceB:
  def process(data: String): String =
    val result = ServiceC.compute(data.length)
    s"processed: $result"

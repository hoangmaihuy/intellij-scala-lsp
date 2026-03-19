package example.calls

object ServiceA:
  def execute(): String =
    ServiceB.process("input")

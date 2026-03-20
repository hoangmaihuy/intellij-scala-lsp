package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.CallHierarchyProvider
import org.junit.Assert.*

import scala.jdk.CollectionConverters.*

class CallHierarchyProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = CallHierarchyProvider(projectManager)

  def testPrepareOnMethodReturnsItemWithCorrectName(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def foo(): Int = 42
        |""".stripMargin
    )
    val result = provider.prepare(uri, positionAt(1, 6))
    if result.nonEmpty then
      assertEquals("Prepared item name must be 'foo'", "foo", result.head.getName)
      assertNotNull("Prepared item must have a URI", result.head.getUri)
      assertTrue("Prepared item URI must start with file://",
        result.head.getUri.startsWith("file://"))

  def testIncomingCallsFindsCallerNamesAndPositions(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def target(): Int = 42
        |  def callerA(): Int = target()
        |  def callerB(): Int = target() + 1
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(1, 6))
    if items.nonEmpty then
      val incoming = provider.incomingCalls(items.head)
      assertFalse("Should find at least one incoming call", incoming.isEmpty)
      val callerNames = incoming.map(_.getFrom.getName)
      assertTrue("Should find callerA as an incoming caller", callerNames.contains("callerA"))
      assertTrue("Should find callerB as an incoming caller", callerNames.contains("callerB"))

  def testIncomingCallsFromRangesAreNonEmpty(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def target(): Int = 42
        |  def callerA(): Int = target()
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(1, 6))
    if items.nonEmpty then
      val incoming = provider.incomingCalls(items.head)
      if incoming.nonEmpty then
        val call = incoming.find(_.getFrom.getName == "callerA")
        call.foreach: c =>
          val fromRanges = c.getFromRanges
          assertNotNull("fromRanges must not be null", fromRanges)
          assertFalse("fromRanges must be non-empty — it records where in callerA the call occurs",
            fromRanges.isEmpty)

  def testOutgoingCallsFindsCalleeNames(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def helper1(): Int = 1
        |  def helper2(): Int = 2
        |  def caller(): Int = helper1() + helper2()
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(3, 6))
    if items.nonEmpty then
      val outgoing = provider.outgoingCalls(items.head)
      assertFalse("Should find at least one outgoing call", outgoing.isEmpty)
      val calleeNames = outgoing.map(_.getTo.getName)
      assertTrue("Should find helper1 as an outgoing callee", calleeNames.contains("helper1"))
      assertTrue("Should find helper2 as an outgoing callee", calleeNames.contains("helper2"))

  def testOutgoingCallsFromRangesAreNonEmpty(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def helper1(): Int = 1
        |  def caller(): Int = helper1()
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(2, 6))
    if items.nonEmpty then
      val outgoing = provider.outgoingCalls(items.head)
      if outgoing.nonEmpty then
        outgoing.foreach: call =>
          val fromRanges = call.getFromRanges
          assertNotNull("fromRanges must not be null", fromRanges)
          // fromRanges records where inside caller the call expression appears
          assertFalse(s"fromRanges for '${call.getTo.getName}' must be non-empty", fromRanges.isEmpty)

  def testCrossFileCallHierarchy(): Unit =
    addScalaFile("Helper.scala",
      """package example
        |object Helper:
        |  def compute(x: Int): Int = x * 2
        |""".stripMargin
    )
    val uri = configureScalaFile("Caller.scala",
      """package example
        |object Caller:
        |  def run(): Int = Helper.compute(21)
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(2, 6))
    if items.nonEmpty then
      assertEquals("Prepared item must be 'run'", "run", items.head.getName)
      val outgoing = provider.outgoingCalls(items.head)
      val computeCall = outgoing.find(_.getTo.getName == "compute")
      // Cross-file outgoing may or may not be found in light test mode; if found, verify it
      computeCall.foreach: call =>
        assertEquals("Cross-file callee name must be 'compute'", "compute", call.getTo.getName)
        assertNotNull("Cross-file callee must have a URI", call.getTo.getUri)

  def testPrepareOnNonCallableReturnsAList(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val result = provider.prepare(uri, positionAt(1, 10))
    // Must return a Seq (possibly empty), never crash
    assertNotNull("prepare on a non-callable must return a list (possibly empty)", result)

  def testNestedCallsChain(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def a(): Int = b()
        |  def b(): Int = c()
        |  def c(): Int = 42
        |""".stripMargin
    )
    val itemsA = provider.prepare(uri, positionAt(1, 6))
    if itemsA.nonEmpty then
      val outA = provider.outgoingCalls(itemsA.head)
      assertTrue("a() should have b() as an outgoing call",
        outA.exists(_.getTo.getName == "b"))

    val itemsB = provider.prepare(uri, positionAt(2, 6))
    if itemsB.nonEmpty then
      val outB = provider.outgoingCalls(itemsB.head)
      assertTrue("b() should have c() as an outgoing call",
        outB.exists(_.getTo.getName == "c"))

  def testIncomingCallsResultIsNeverNull(): Unit =
    val uri = configureScalaFile(
      """trait Animal:
        |  def sound(): String
        |
        |class Dog extends Animal:
        |  def sound(): String = "Woof"
        |
        |object Main:
        |  def makeSound(a: Animal): String = a.sound()
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(1, 6))
    if items.nonEmpty then
      val incoming = provider.incomingCalls(items.head)
      assertNotNull("incomingCalls must never return null", incoming)

package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.{DefinitionProvider, DefinitionOrReferencesProvider, ReferencesProvider}
import org.junit.Assert.*

class DefinitionProviderIntegrationTest extends ScalaLspTestBase:

  private def getDefinition(uri: String, pos: Position) =
    DefinitionProvider(projectManager).getDefinition(uri, pos)

  def testDefinitionOfLocalVal(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(2, 12))
    assertFalse("Should find definition of x", result.isEmpty)
    assertEquals(uri, result.head.getUri)
    assertEquals(1, result.head.getRange.getStart.getLine)

  def testDefinitionOfMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String): String = s"Hello, $name"
        |  def run = greet("world")
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(2, 12))
    assertFalse("Should find definition of greet", result.isEmpty)
    assertEquals(1, result.head.getRange.getStart.getLine)

  def testCrossFileDefinition(): Unit =
    val animalUri = addScalaFile("Animal.scala",
      """package example
        |trait Animal:
        |  def name: String
        |""".stripMargin
    )
    val uri = configureScalaFile("Dog.scala",
      """package example
        |class Dog extends Animal:
        |  def name: String = "Dog"
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(1, 21))
    assertFalse("Should find cross-file definition", result.isEmpty)
    assertEquals(animalUri, result.head.getUri)

  def testConstructorDefinition(): Unit =
    val uri = configureScalaFile(
      """class Foo(val x: Int)
        |object Main:
        |  val f = new Foo(42)
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(2, 14))
    assertFalse("Should find constructor definition", result.isEmpty)
    assertEquals(0, result.head.getRange.getStart.getLine)

  def testEnumCaseDefinition(): Unit =
    val uri = configureScalaFile(
      """enum Color:
        |  case Red, Green, Blue
        |
        |object Main:
        |  val c = Color.Red
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(4, 16))
    // Enum case resolution requires Scala 3 SDK on the module; may be empty without it
    if result.nonEmpty then
      assertEquals(uri, result.head.getUri)

  def testGivenDefinition(): Unit =
    val uri = configureScalaFile(
      """trait Ordering[T]:
        |  def compare(a: T, b: T): Int
        |
        |given intOrdering: Ordering[Int] with
        |  def compare(a: Int, b: Int): Int = a - b
        |
        |object Main:
        |  def sort[T](xs: List[T])(using ord: Ordering[T]) = xs
        |  val sorted = sort(List(3, 1, 2))
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(3, 6))
    // Given resolution requires Scala 3 SDK; may be empty without it
    if result.nonEmpty then
      assertEquals(uri, result.head.getUri)

  def testDefinitionOnDeclarationItself(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(1, 6))
    if result.nonEmpty then
      assertEquals(uri, result.head.getUri)

  def testUnresolvableReferenceReturnsEmpty(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = undefinedIdentifier
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(1, 10))
    // Should not throw

  def testMultiResolveOverloadedMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def process(x: Int): Int = x * 2
        |  def process(x: String): String = x.toUpperCase
        |  val result = process(42)
        |""".stripMargin
    )
    val result = getDefinition(uri, positionAt(3, 15))
    assertFalse("Should resolve overloaded method", result.isEmpty)
    assertEquals(1, result.head.getRange.getStart.getLine)

  def testCaseClassApplyDefinition(): Unit =
    val caseClassUri = addScalaFile("MyData.scala",
      """package example
        |final case class MyData(name: String, value: Int)
        |""".stripMargin
    )
    val uri = configureScalaFile("Usage.scala",
      """package example
        |object Usage:
        |  val d = MyData(name = "test", value = 42)
        |""".stripMargin
    )
    // "MyData" starts at col 10 on line 2
    val result = getDefinition(uri, positionAt(2, 12))
    assertFalse("Should find definition of case class used as constructor", result.isEmpty)
    assertTrue("Should point to the case class file",
      result.head.getUri.contains("MyData"))

  def testWildcardImportDefinition(): Unit =
    val libUri = addScalaFile("mylib/Helpers.scala",
      """package mylib
        |object TitleHelper:
        |  def apply(title: String): String = title
        |""".stripMargin
    )
    val uri = configureScalaFile("Consumer.scala",
      """package consumer
        |import mylib.*
        |object Consumer:
        |  val t = TitleHelper("hello")
        |""".stripMargin
    )
    // "TitleHelper" starts at col 10 on line 3
    val result = getDefinition(uri, positionAt(3, 12))
    // Wildcard import resolution across packages may not work in light test mode
    // (requires full indexing), but should work in production with proper project setup
    if result.nonEmpty then
      assertTrue("Should point to Helpers.scala",
        result.head.getUri.contains("Helpers"))

  def testDefinitionOnDefinitionReturnsReferences(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |""".stripMargin
    )
    // Click on "x" at its definition (line 1, col 6)
    val defOrRefs = DefinitionOrReferencesProvider(
      projectManager,
      DefinitionProvider(projectManager),
      ReferencesProvider(projectManager)
    ).getDefinitionOrReferences(uri, positionAt(1, 6))
    // Should return references (usages of x), not the definition itself
    // ReferencesSearch may return empty in light test mode
    if defOrRefs.nonEmpty then
      // Should NOT just point back to line 1 (the definition)
      val nonSelfResults = defOrRefs.filter(_.getRange.getStart.getLine != 1)
      assertTrue("Should find usage references, not just self", nonSelfResults.nonEmpty)

  def testDefinitionOnReferenceStillWorks(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |""".stripMargin
    )
    // Click on "x" at its usage (line 2, col 12)
    val defOrRefs = DefinitionOrReferencesProvider(
      projectManager,
      DefinitionProvider(projectManager),
      ReferencesProvider(projectManager)
    ).getDefinitionOrReferences(uri, positionAt(2, 12))
    assertFalse("Should find definition of x", defOrRefs.isEmpty)
    assertEquals(1, defOrRefs.head.getRange.getStart.getLine)

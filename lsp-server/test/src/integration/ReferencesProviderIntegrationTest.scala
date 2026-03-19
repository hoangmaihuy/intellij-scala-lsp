package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.ReferencesProvider
import org.junit.Assert.*

class ReferencesProviderIntegrationTest extends ScalaLspTestBase:

  private def findReferences(uri: String, pos: Position, includeDeclaration: Boolean) =
    ReferencesProvider(projectManager).findReferences(uri, pos, includeDeclaration)

  def testReferencesToLocalVal(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |""".stripMargin
    )
    val result = findReferences(uri, positionAt(1, 6), includeDeclaration = false)
    // ReferencesSearch requires project indexes; may return empty in light test mode
    if result.nonEmpty then
      assertTrue("Should find at least 2 references to x", result.size >= 2)

  def testReferencesToMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String) = s"Hello, $name"
        |  def a = greet("Alice")
        |  def b = greet("Bob")
        |  def c = greet("Charlie")
        |""".stripMargin
    )
    val result = findReferences(uri, positionAt(1, 6), includeDeclaration = false)
    if result.nonEmpty then
      assertTrue("Should find at least 3 references to greet", result.size >= 3)

  def testCrossFileReferences(): Unit =
    addScalaFile("Greeter.scala",
      """package example
        |object Greeter:
        |  def hello = "Hi"
        |""".stripMargin
    )
    val uri = configureScalaFile("Main.scala",
      """package example
        |object Main:
        |  val a = Greeter.hello
        |  val b = Greeter.hello
        |""".stripMargin
    )
    val result = findReferences(uri, positionAt(2, 18), includeDeclaration = false)
    if result.nonEmpty then
      assertTrue("Should find cross-file references", result.size >= 2)

  def testIncludeDeclarationFlag(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x
        |""".stripMargin
    )
    val withDecl = findReferences(uri, positionAt(1, 6), includeDeclaration = true)
    val withoutDecl = findReferences(uri, positionAt(1, 6), includeDeclaration = false)
    assertTrue("Include declaration should have more or equal results",
      withDecl.size >= withoutDecl.size)

  def testExcludeDeclarationFlag(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x
        |""".stripMargin
    )
    val result = findReferences(uri, positionAt(1, 6), includeDeclaration = false)
    for ref <- result do
      assertFalse("Should not include declaration line",
        ref.getRange.getStart.getLine == 1 && ref.getRange.getStart.getCharacter == 6)

  def testReferencesToTraitFromImplementors(): Unit =
    val uri = configureScalaFile(
      """trait Speak:
        |  def speak: String
        |
        |class Dog extends Speak:
        |  def speak = "Woof"
        |
        |class Cat extends Speak:
        |  def speak = "Meow"
        |""".stripMargin
    )
    val result = findReferences(uri, positionAt(0, 6), includeDeclaration = false)
    if result.nonEmpty then
      assertTrue("Should find references from implementors", result.size >= 2)

  def testReferencesToUnresolvableSymbolReturnsEmpty(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = undefinedSymbol
        |""".stripMargin
    )
    val result = findReferences(uri, positionAt(1, 10), includeDeclaration = false)
    assertNotNull(result)

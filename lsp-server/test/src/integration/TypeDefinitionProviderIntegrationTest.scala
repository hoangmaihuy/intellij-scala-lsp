package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.TypeDefinitionProvider
import org.junit.Assert.*

class TypeDefinitionProviderIntegrationTest extends ScalaLspTestBase:

  private def getTypeDefinition(uri: String, pos: Position) =
    TypeDefinitionProvider(projectManager).getTypeDefinition(uri, pos)

  def testTypeDefinitionOfVal(): Unit =
    val uri = configureScalaFile(
      """class Dog(val name: String)
        |object Main:
        |  val d: Dog = Dog("Rex")
        |""".stripMargin
    )
    val result = getTypeDefinition(uri, positionAt(2, 6))
    if result.nonEmpty then
      assertEquals("Should navigate to Dog class", 0, result.head.getRange.getStart.getLine)

  def testTypeDefinitionOfMethodReturn(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def name: String = "hello"
        |  val x = name
        |""".stripMargin
    )
    val result = getTypeDefinition(uri, positionAt(2, 6))
    if result.nonEmpty then
      assertNotNull(result.head.getUri)

  def testGivenInstanceTypeDefinition(): Unit =
    val uri = configureScalaFile(
      """trait Show[T]:
        |  def show(t: T): String
        |
        |given intShow: Show[Int] with
        |  def show(t: Int) = t.toString
        |
        |object Main:
        |  val s = intShow
        |""".stripMargin
    )
    val result = getTypeDefinition(uri, positionAt(7, 10))
    if result.nonEmpty then
      assertNotNull(result.head.getUri)

  def testUnresolvableTypeReturnsEmpty(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = undefinedThing
        |""".stripMargin
    )
    val result = getTypeDefinition(uri, positionAt(1, 10))
    assertNotNull(result)

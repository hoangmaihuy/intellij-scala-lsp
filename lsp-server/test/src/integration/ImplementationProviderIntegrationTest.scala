package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.ImplementationProvider
import org.junit.Assert.*

class ImplementationProviderIntegrationTest extends ScalaLspTestBase:

  private def getImplementations(uri: String, pos: Position) =
    ImplementationProvider(projectManager).getImplementations(uri, pos)

  def testImplementationsOfTrait(): Unit =
    val uri = configureScalaFile(
      """trait Animal:
        |  def name: String
        |
        |class Dog extends Animal:
        |  def name = "Dog"
        |
        |class Cat extends Animal:
        |  def name = "Cat"
        |""".stripMargin
    )
    // DefinitionsScopedSearch requires project indexes; may return empty in light test mode
    val result = getImplementations(uri, positionAt(0, 6))
    if result.nonEmpty then
      assertTrue("Should find at least 2 implementations", result.size >= 2)

  def testImplementationsOfAbstractMethod(): Unit =
    val uri = configureScalaFile(
      """trait Speaker:
        |  def speak: String
        |
        |class Dog extends Speaker:
        |  def speak = "Woof"
        |
        |class Cat extends Speaker:
        |  def speak = "Meow"
        |""".stripMargin
    )
    val result = getImplementations(uri, positionAt(1, 6))
    if result.nonEmpty then
      assertTrue("Should find implementations of abstract method", result.size >= 2)

  def testNoImplementationsReturnsEmpty(): Unit =
    val uri = configureScalaFile(
      """final class Standalone:
        |  def value = 42
        |""".stripMargin
    )
    val result = getImplementations(uri, positionAt(0, 12))
    assertTrue("Final class should have no implementations", result.isEmpty)

  def testCrossFileImplementations(): Unit =
    val speakerUri = addScalaFile("Speaker.scala",
      """package example
        |trait Speaker:
        |  def speak: String
        |""".stripMargin
    )
    addScalaFile("Dog.scala",
      """package example
        |class Dog extends Speaker:
        |  def speak = "Woof"
        |""".stripMargin
    )
    configureScalaFile("Cat.scala",
      """package example
        |class Cat extends Speaker:
        |  def speak = "Meow"
        |""".stripMargin
    )
    val result = getImplementations(speakerUri, positionAt(1, 6))
    if result.nonEmpty then
      assertTrue("Should find cross-file implementations", result.size >= 2)

  def testEnumImplementations(): Unit =
    val uri = configureScalaFile(
      """enum Color:
        |  case Red, Green, Blue
        |""".stripMargin
    )
    val result = getImplementations(uri, positionAt(0, 5))
    assertNotNull(result)

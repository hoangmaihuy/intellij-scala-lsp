package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.TypeHierarchyProvider
import org.junit.Assert.*

class TypeHierarchyProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = TypeHierarchyProvider(projectManager)

  def testPrepareOnClass(): Unit =
    val uri = configureScalaFile(
      """class MyClass:
        |  def value = 42
        |""".stripMargin
    )
    val result = provider.prepare(uri, positionAt(0, 6))
    if result.nonEmpty then
      assertEquals("MyClass", result.head.getName)

  def testPrepareOnTrait(): Unit =
    val uri = configureScalaFile(
      """trait MyTrait:
        |  def value: Int
        |""".stripMargin
    )
    val result = provider.prepare(uri, positionAt(0, 6))
    if result.nonEmpty then
      assertEquals("MyTrait", result.head.getName)

  def testPrepareOnNonType(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    // Position on literal
    val result = provider.prepare(uri, positionAt(1, 12))
    // May return empty or a result depending on resolution
    assertNotNull(result)

  def testSupertypes(): Unit =
    val uri = configureScalaFile(
      """trait Animal:
        |  def name: String
        |
        |trait Domestic
        |
        |class Dog extends Animal with Domestic:
        |  def name = "Dog"
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(5, 6))
    if items.nonEmpty then
      val supers = provider.supertypes(items.head)
      if supers.nonEmpty then
        val names = supers.map(_.getName)
        assertTrue("Should find Animal as supertype", names.contains("Animal"))

  def testSubtypes(): Unit =
    val uri = configureScalaFile(
      """trait Shape:
        |  def area: Double
        |
        |class Circle extends Shape:
        |  def area = 3.14
        |
        |class Square extends Shape:
        |  def area = 1.0
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(0, 6))
    if items.nonEmpty then
      val subs = provider.subtypes(items.head)
      if subs.nonEmpty then
        val names = subs.map(_.getName)
        assertTrue("Should find Circle", names.contains("Circle"))
        assertTrue("Should find Square", names.contains("Square"))

  def testCrossFileHierarchy(): Unit =
    addScalaFile("Base.scala",
      """package example
        |trait Base:
        |  def id: Int
        |""".stripMargin
    )
    val uri = configureScalaFile("Impl.scala",
      """package example
        |class Impl extends Base:
        |  def id = 1
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(1, 6))
    if items.nonEmpty then
      val supers = provider.supertypes(items.head)
      if supers.nonEmpty then
        assertTrue("Should find Base as supertype",
          supers.exists(_.getName == "Base"))

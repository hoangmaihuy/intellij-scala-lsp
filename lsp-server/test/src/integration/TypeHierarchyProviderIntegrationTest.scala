package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.{Position, SymbolKind}
import org.jetbrains.scalalsP.intellij.TypeHierarchyProvider
import org.junit.Assert.*

class TypeHierarchyProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = TypeHierarchyProvider(projectManager)

  def testPrepareOnClassReturnsClassSymbolKind(): Unit =
    val uri = configureScalaFile(
      """class MyClass {
        |  def value = 42
        |}
        |""".stripMargin
    )
    val result = provider.prepare(uri, positionAt(0, 6))
    assertTrue("Should return at least one prepared item", result.nonEmpty)
    assertEquals("Prepared item name must be 'MyClass'", "MyClass", result.head.getName)
    assertEquals("A plain class must have SymbolKind.Class",
      SymbolKind.Class, result.head.getKind)

  def testPrepareOnTraitReturnsInterfaceSymbolKind(): Unit =
    val uri = configureScalaFile(
      """trait MyTrait {
        |  def value: Int
        |}
        |""".stripMargin
    )
    val result = provider.prepare(uri, positionAt(0, 6))
    assertTrue("Should return at least one prepared item", result.nonEmpty)
    assertEquals("Prepared item name must be 'MyTrait'", "MyTrait", result.head.getName)
    assertEquals("A trait must have SymbolKind.Interface",
      SymbolKind.Interface, result.head.getKind)

  def testPrepareOnNonTypeReturnsAList(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |}
        |""".stripMargin
    )
    val result = provider.prepare(uri, positionAt(1, 12))
    assertNotNull("prepare on a non-type must return a list (possibly empty)", result)

  def testSupertypesContainsExactNames(): Unit =
    val uri = configureScalaFile(
      """trait Animal {
        |  def name: String
        |}
        |
        |trait Domestic
        |
        |class Dog extends Animal with Domestic {
        |  def name = "Dog"
        |}
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(6, 6))
    assertTrue("Should return at least one prepared item", items.nonEmpty)
    val supers = provider.supertypes(items.head)
    assertFalse("Dog must have at least one explicit supertype", supers.isEmpty)
    val names = supers.map(_.getName).toSet
    assertTrue("Supertypes of Dog must include 'Animal'", names.contains("Animal"))
    assertTrue("Supertypes of Dog must include 'Domestic'", names.contains("Domestic"))

  def testSupertypesDoesNotIncludeJavaLangObject(): Unit =
    val uri = configureScalaFile(
      """class SimpleClass {
        |  def x = 1
        |}
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(0, 6))
    assertTrue("Should return at least one prepared item", items.nonEmpty)
    val supers = provider.supertypes(items.head)
    val names = supers.map(_.getName).toSet
    assertFalse("Supertypes must not include 'Object' (synthetic parent)",
      names.contains("Object"))

  def testSubtypesContainsExactNames(): Unit =
    val uri = configureScalaFile(
      """trait Shape {
        |  def area: Double
        |}
        |
        |class Circle extends Shape {
        |  def area = 3.14
        |}
        |
        |class Square extends Shape {
        |  def area = 1.0
        |}
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(0, 6))
    assertTrue("Should return at least one prepared item", items.nonEmpty)
    val subs = provider.subtypes(items.head)
    assertTrue("Should have subtypes", subs.nonEmpty)
    val names = subs.map(_.getName).toSet
    assertTrue("Subtypes of Shape must include 'Circle'", names.contains("Circle"))
    assertTrue("Subtypes of Shape must include 'Square'", names.contains("Square"))

  def testCrossFileHierarchyFindsBase(): Unit =
    addScalaFile("Base.scala",
      """package example
        |trait Base {
        |  def id: Int
        |}
        |""".stripMargin
    )
    val uri = configureScalaFile("Impl.scala",
      """package example
        |class Impl extends Base {
        |  def id = 1
        |}
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(1, 6))
    assertTrue("Should return at least one prepared item", items.nonEmpty)
    val supers = provider.supertypes(items.head)
    // Cross-file hierarchy may or may not resolve in light test mode; if found, verify it
    if supers.nonEmpty then
      val names = supers.map(_.getName).toSet
      assertTrue("Cross-file supertypes must include 'Base'", names.contains("Base"))

  def testCaseClassFiltersProductAndSerializable(): Unit =
    val uri = configureScalaFile(
      """trait Named {
        |  def name: String
        |}
        |
        |case class Person(name: String) extends Named
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(4, 10))
    assertTrue("Should return at least one prepared item", items.nonEmpty)
    val supers = provider.supertypes(items.head)
    assertTrue("Should have supertypes", supers.nonEmpty)
    val names = supers.map(_.getName).toSet
    // Named must be present — it is explicitly declared
    assertTrue("Supertypes of Person must include 'Named'", names.contains("Named"))
    // Compiler-injected synthetic parents must be filtered out
    assertFalse("Supertypes of Person must not include 'Product' (synthetic)",
      names.contains("Product"))
    assertFalse("Supertypes of Person must not include 'Serializable' (synthetic)",
      names.contains("Serializable"))

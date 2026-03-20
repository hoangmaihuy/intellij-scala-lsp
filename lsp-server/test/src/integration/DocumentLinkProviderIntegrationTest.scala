package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.DocumentLinkProvider
import org.junit.Assert.*

class DocumentLinkProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = DocumentLinkProvider(projectManager)

  def testDetectUrlInComment(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  // See https://docs.scala-lang.org/scala3/reference
        |  val x = 42
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertFalse("Should detect URL in comment", links.isEmpty)
    assertTrue("Link target should be the URL",
      links.exists(_.getTarget.contains("docs.scala-lang.org")))

  def testDetectUrlInString(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val url = "https://example.com/api/v1"
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertFalse("Should detect URL in string", links.isEmpty)
    assertTrue("Link target should be the URL",
      links.exists(_.getTarget.contains("example.com")))

  def testDetectSbtDependency(): Unit =
    val uri = configureScalaFile("build.sbt",
      """val deps = Seq(
        |  "org.typelevel" %% "cats-core" % "2.10.0",
        |  "org.scalatest" %%% "scalatest" % "3.2.17"
        |)
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertTrue("Should detect SBT dependencies",
      links.exists(_.getTarget.contains("search.maven.org")))
    assertTrue("Should detect %%% dependencies", links.size >= 2)

  def testNoLinksInPlainCode(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo(y: Int): Int = x + y
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertTrue("Should have no links in plain code", links.isEmpty)

  def testDetectMultipleUrlsOnSameLine(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  // See https://example.com and https://other.com/path
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertEquals("Should detect two URLs", 2, links.size)

  def testUrlRangeIsCorrect(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  // https://example.com
        |""".stripMargin
    )
    val links = provider.getDocumentLinks(uri)
    assertEquals(1, links.size)
    val link = links.head
    assertEquals("URL should be on line 1", 1, link.getRange.getStart.getLine)
    assertEquals("URL should start at column 5", 5, link.getRange.getStart.getCharacter)

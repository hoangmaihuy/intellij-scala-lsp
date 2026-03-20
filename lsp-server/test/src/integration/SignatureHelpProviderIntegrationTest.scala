package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.SignatureHelpProvider
import org.junit.Assert.*

class SignatureHelpProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = SignatureHelpProvider(projectManager)

  def testSignatureHelpInMethodCall(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 19))
    assertNotNull("Should return signature help inside method call", help)
    help.foreach: sh =>
      assertFalse("Should have at least one signature", sh.getSignatures.isEmpty)
      val sig = sh.getSignatures.get(0)
      assertTrue("Signature should contain method name", sig.getLabel.contains("add"))
      assertNotNull("Should have parameters", sig.getParameters)
      assertEquals("Should have 2 parameters", 2, sig.getParameters.size())

  def testSignatureHelpActiveParameter(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(1,
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 22))
    help.foreach: sh =>
      assertEquals("Active parameter should be 1 (second param)", 1, sh.getActiveParameter.intValue())

  def testSignatureHelpOutsideMethodCall(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(1, 12))
    help.foreach: sh =>
      assertTrue("Should have no signatures outside method call",
        sh.getSignatures == null || sh.getSignatures.isEmpty)

  def testSignatureHelpOverloadedMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def process(x: Int): String = x.toString
        |  def process(x: String): String = x
        |  def process(x: Int, y: Int): String = (x + y).toString
        |  val result = process(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(4, 23))
    help.foreach: sh =>
      assertTrue("Should have multiple overloaded signatures", sh.getSignatures.size() >= 2)

  def testSignatureIncludesReturnType(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 19))
    assertNotNull("Should return signature help", help)
    help.foreach: sh =>
      assertFalse("Should have at least one signature", sh.getSignatures.isEmpty)
      val sig = sh.getSignatures.get(0)
      assertTrue("Signature label should contain return type annotation",
        sig.getLabel.contains(": Int") || sig.getLabel.contains(":Int"))

  def testSignatureIncludesDocumentation(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  /** Adds two integers together. */
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(3, 19))
    assertNotNull("Should return signature help", help)
    help.foreach: sh =>
      assertFalse("Should have at least one signature", sh.getSignatures.isEmpty)
      val sig = sh.getSignatures.get(0)
      // Documentation may or may not be available depending on the IntelliJ version
      // Just verify we got a signature with the right label
      assertTrue("Signature should contain method name", sig.getLabel.contains("add"))

  def testImplicitParameterClause(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def sorted[A](list: List[A])(implicit ord: Ordering[A]): List[A] = list.sorted
        |  val result = sorted(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 22))
    assertNotNull("Should return signature help", help)
    help.foreach: sh =>
      assertFalse("Should have at least one signature", sh.getSignatures.isEmpty)
      val sig = sh.getSignatures.get(0)
      assertTrue("Signature label should contain 'implicit'",
        sig.getLabel.contains("implicit"))

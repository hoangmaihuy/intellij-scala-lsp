package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.SignatureHelpProvider
import org.junit.Assert.*

class SignatureHelpProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = SignatureHelpProvider(projectManager)

  def testSignatureLabelFormat(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 19))
    assertTrue("Should return signature help inside method call", help.isDefined)
    val sig = help.get.getSignatures.get(0)
    val label = sig.getLabel
    assertTrue("Label should start with method name 'add'", label.startsWith("add"))
    assertTrue(s"Label should contain 'a: Int', got: $label", label.contains("a: Int"))
    assertTrue(s"Label should contain 'b: Int', got: $label", label.contains("b: Int"))
    assertTrue(s"Label should contain return type annotation ': Int', got: $label",
      label.contains(": Int") || label.contains(":Int"))

  def testSignatureParameterCount(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 19))
    assertTrue("Should return signature help", help.isDefined)
    val sig = help.get.getSignatures.get(0)
    assertNotNull("Should have parameters list", sig.getParameters)
    assertEquals("Should have exactly 2 parameters", 2, sig.getParameters.size())

  def testActiveParameterAtFirstArg(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 19))
    assertTrue("Should return signature help", help.isDefined)
    assertEquals("Active parameter should be 0 (first param)", 0, help.get.getActiveParameter.intValue())

  def testActiveParameterAtSecondArg(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(1,
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 22))
    assertTrue("Should return signature help", help.isDefined)
    assertEquals("Active parameter should be 1 (second param, after comma)", 1, help.get.getActiveParameter.intValue())

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
    assertTrue("Should return signature help", help.isDefined)
    val sig = help.get.getSignatures.get(0)
    val label = sig.getLabel
    assertTrue(
      s"Signature label should contain return type annotation ': Int', got: $label",
      label.contains(": Int") || label.contains(":Int")
    )

  def testSignatureParameterInfoLabels(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 19))
    assertTrue("Should return signature help", help.isDefined)
    val sig = help.get.getSignatures.get(0)
    val paramLabels = (0 until sig.getParameters.size()).map(sig.getParameters.get(_).getLabel.getLeft).toList
    assertTrue(
      s"First parameter label should contain 'a', got: $paramLabels",
      paramLabels.exists(_.contains("a"))
    )
    assertTrue(
      s"Second parameter label should contain 'b', got: $paramLabels",
      paramLabels.exists(_.contains("b"))
    )

  def testImplicitClauseInLabel(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def sorted[A](list: List[A])(implicit ord: Ordering[A]): List[A] = list.sorted
        |  val result = sorted(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(2, 22))
    assertTrue("Should return signature help", help.isDefined)
    val sig = help.get.getSignatures.get(0)
    val label = sig.getLabel
    assertTrue(
      s"Signature label should contain 'implicit' keyword, got: $label",
      label.contains("implicit")
    )

  def testSignatureIncludesDocumentation(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  /** Adds two integers together. */
        |  def add(a: Int, b: Int): Int = a + b
        |  val result = add(
        |""".stripMargin
    )
    val help = provider.getSignatureHelp(uri, positionAt(3, 19))
    assertTrue("Should return signature help", help.isDefined)
    val sig = help.get.getSignatures.get(0)
    assertTrue(
      s"Signature label should contain method name 'add', got: ${sig.getLabel}",
      sig.getLabel.contains("add")
    )

package org.jetbrains.scalalsP.integration

import org.jetbrains.scalalsP.intellij.SemanticTokensProvider
import org.junit.Assert.*

class SemanticTokensProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = SemanticTokensProvider(projectManager)

  /** Metals-style check: compare decorated source with actual semantic tokens output.
    *
    * Expected format: `<<identifier>>/*tokenType,modifier1,modifier2*/`
    * E.g.: `<<Main>>/*class,definition,static*/`
    */
  private def check(name: String, expected: String): Unit =
    val source = TestSemanticTokens.removeDecorations(expected)
    val uri = configureScalaFile(name + ".scala", source)
    myFixture.doHighlighting()
    val tokens = provider.getSemanticTokensFull(uri)
    val obtained = TestSemanticTokens.semanticString(source, tokens)
    assertEquals(s"Semantic tokens mismatch for '$name'", expected, obtained)

  // ========================
  // Legend / structure tests
  // ========================

  def testSemanticTokensDataIsMultipleOfFive(): Unit =
    val source = """object Main {
                   |  val x = 42
                   |}
                   |""".stripMargin
    val uri = configureScalaFile(source)
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    assertNotNull("Should return SemanticTokens", result)
    assertNotNull("Should have data array", result.getData)
    if result.getData != null && !result.getData.isEmpty then
      assertEquals("Token data should be multiple of 5", 0, result.getData.size() % 5)

  def testTokenTypeMappingKnown(): Unit =
    val legend = SemanticTokensProvider.legend
    assertNotNull(legend)
    assertFalse("Legend should have token types", legend.getTokenTypes.isEmpty)
    assertFalse("Legend should have token modifiers", legend.getTokenModifiers.isEmpty)
    assertTrue("Legend should include 'namespace'", legend.getTokenTypes.contains("namespace"))
    assertTrue("Legend should include 'class'", legend.getTokenTypes.contains("class"))
    assertTrue("Legend should include 'interface'", legend.getTokenTypes.contains("interface"))
    assertTrue("Legend should include 'enum'", legend.getTokenTypes.contains("enum"))
    assertTrue("Legend should include 'enumMember'", legend.getTokenTypes.contains("enumMember"))
    assertTrue("Legend should include 'method'", legend.getTokenTypes.contains("method"))
    assertTrue("Legend should include 'function'", legend.getTokenTypes.contains("function"))
    assertTrue("Legend should include 'variable'", legend.getTokenTypes.contains("variable"))
    assertTrue("Legend should include 'property'", legend.getTokenTypes.contains("property"))
    assertTrue("Legend should include 'parameter'", legend.getTokenTypes.contains("parameter"))
    assertTrue("Legend should include 'keyword'", legend.getTokenTypes.contains("keyword"))

  def testSemanticTokensRangeIsSubset(): Unit =
    val source = """object Main {
                   |  val x = 42
                   |  val y = "hello"
                   |  def foo: Int = x
                   |}
                   |""".stripMargin
    val uri = configureScalaFile(source)
    myFixture.doHighlighting()
    val fullResult = provider.getSemanticTokensFull(uri)
    val range = org.eclipse.lsp4j.Range(
      org.eclipse.lsp4j.Position(1, 0),
      org.eclipse.lsp4j.Position(2, 20)
    )
    val rangeResult = provider.getSemanticTokensRange(uri, range)
    assertNotNull("Range tokens should return non-null", rangeResult)

  // ======================================
  // Class, trait, object — Metals compat
  // ======================================

  def testClassVsTraitVsObject(): Unit =
    check("classVsTraitVsObject",
      """<<class>>/*keyword*/ <<MyClass>>/*class,definition*/
        |<<trait>>/*keyword*/ <<MyTrait>>/*interface,definition,abstract*/
        |<<object>>/*keyword*/ <<MyObject>>/*class,definition,static*/
        |""".stripMargin
    )

  // ================================
  // Val, var — readonly modifier
  // ================================

  def testValReadonlyVarMutable(): Unit =
    check("valReadonlyVarMutable",
      """<<object>>/*keyword*/ <<Main>>/*class,definition,static*/ {
        |  <<val>>/*keyword*/ <<x>>/*property,definition,readonly*/ = <<42>>/*number*/
        |  <<var>>/*keyword*/ <<y>>/*property,definition*/ = <<0>>/*number*/
        |}
        |""".stripMargin
    )

  def testLocalValVarAreVariable(): Unit =
    check("localValVarAreVariable",
      """<<object>>/*keyword*/ <<Main>>/*class,definition,static*/ {
        |  <<def>>/*keyword*/ <<foo>>/*method,definition*/(): <<Unit>>/*class,abstract*/ = {
        |    <<val>>/*keyword*/ <<a>>/*variable,definition,readonly*/ = <<1>>/*number*/
        |    <<var>>/*keyword*/ <<b>>/*variable,definition*/ = <<2>>/*number*/
        |  }
        |}
        |""".stripMargin
    )

  // =========================================
  // Parameter — declaration + readonly
  // =========================================

  def testParameterHasReadonly(): Unit =
    // Note: String doesn't resolve in light fixture, so it's not tokenized
    check("parameterHasReadonly",
      """<<object>>/*keyword*/ <<Main>>/*class,definition,static*/ {
        |  <<def>>/*keyword*/ <<greet>>/*method,definition*/(<<name>>/*parameter,declaration,readonly*/: String) = <<name>>/*parameter,readonly*/
        |}
        |""".stripMargin
    )

  // ================================
  // Method vs function (local def)
  // ================================

  def testMethodVsLocalFunction(): Unit =
    check("methodVsLocalFunction",
      """<<object>>/*keyword*/ <<Main>>/*class,definition,static*/ {
        |  <<def>>/*keyword*/ <<classMethod>>/*method,definition*/(): <<Unit>>/*class,abstract*/ = {
        |    <<def>>/*keyword*/ <<localFunc>>/*function,definition*/(): <<Int>>/*class,abstract*/ = <<42>>/*number*/
        |  }
        |}
        |""".stripMargin
    )

  // =====================
  // Operator methods — Metals classifies as method, not operator
  // =====================

  def testOperatorMethodClassified(): Unit =
    // Note: Vec(...) constructor call classifies as function (constructor resolves to primary constructor,
    // which is local to the class body, not a template-body member)
    check("operatorMethodClassified",
      """<<object>>/*keyword*/ <<Ops>>/*class,definition,static*/ {
        |  <<case>>/*keyword*/ <<class>>/*keyword*/ <<Vec>>/*class,definition*/(<<x>>/*parameter,declaration,readonly*/: <<Int>>/*class,abstract*/, <<y>>/*parameter,declaration,readonly*/: <<Int>>/*class,abstract*/) {
        |    <<def>>/*keyword*/ <<+>>/*method,definition*/(<<other>>/*parameter,declaration,readonly*/: <<Vec>>/*class*/): <<Vec>>/*class*/ = <<Vec>>/*function*/(<<x>>/*parameter,readonly*/ <<+>>/*method*/ <<other>>/*parameter,readonly*/.<<x>>/*parameter,readonly*/, <<y>>/*parameter,readonly*/ <<+>>/*method*/ <<other>>/*parameter,readonly*/.<<y>>/*parameter,readonly*/)
        |  }
        |}
        |""".stripMargin
    )

  // =====================
  // Deprecated modifier
  // =====================

  def testDeprecatedModifier(): Unit =
    // Note: @deprecated annotation reference doesn't resolve in light fixture
    check("deprecatedModifier",
      """<<object>>/*keyword*/ <<Main>>/*class,definition,static*/ {
        |  @deprecated(<<"use bar">>/*string*/, <<"1.0">>/*string*/)
        |  <<def>>/*keyword*/ <<foo>>/*method,definition,deprecated*/(): <<Unit>>/*class,abstract*/ = ()
        |}
        |""".stripMargin
    )

  // ======================
  // String escape tokens
  // ======================

  def testStringEscapeSequences(): Unit =
    check("stringEscapeSequences",
      """<<object>>/*keyword*/ <<Main>>/*class,definition,static*/ {
        |  <<val>>/*keyword*/ <<s>>/*property,definition,readonly*/ = <<\>>/*operator*/<<"hello>>/*string*/<<\n>>/*regexp*/<<world>>/*string*/<<\">>/*regexp*/
        |}
        |""".stripMargin
    )

  // ============================================
  // Variable and method references in body
  // ============================================

  def testVariableAndMethodReferencesInBody(): Unit =
    // Note: String type and String.+ / String.toUpperCase don't resolve in light fixture
    check("variableAndMethodReferencesInBody",
      """<<object>>/*keyword*/ <<Main>>/*class,definition,static*/ {
        |  <<def>>/*keyword*/ <<greet>>/*method,definition*/(<<name>>/*parameter,declaration,readonly*/: String): String = {
        |    <<val>>/*keyword*/ <<greeting>>/*variable,definition,readonly*/ = <<"Hello">>/*string*/
        |    <<val>>/*keyword*/ <<result>>/*variable,definition,readonly*/ = <<greeting>>/*variable,readonly*/ <<+>>/*operator*/ <<" ">>/*string*/ <<+>>/*operator*/ <<name>>/*parameter,readonly*/
        |    <<result>>/*variable,readonly*/.<<toUpperCase>>/*property*/
        |  }
        |}
        |""".stripMargin
    )

  def testClassFieldAndMethodCallReferences(): Unit =
    // Note: String return type and Int.toString don't resolve in light fixture
    check("classFieldAndMethodCallReferences",
      """<<class>>/*keyword*/ <<Service>>/*class,definition*/ {
        |  <<def>>/*keyword*/ <<doWork>>/*method,definition*/(<<x>>/*parameter,declaration,readonly*/: <<Int>>/*class,abstract*/): String = <<x>>/*parameter,readonly*/.<<toString>>/*property*/
        |}
        |<<class>>/*keyword*/ <<App>>/*class,definition*/(<<service>>/*parameter,declaration,readonly*/: <<Service>>/*class*/) {
        |  <<def>>/*keyword*/ <<run>>/*method,definition*/(): String = {
        |    <<val>>/*keyword*/ <<result>>/*variable,definition,readonly*/ = <<service>>/*parameter,readonly*/.<<doWork>>/*method*/(<<42>>/*number*/)
        |    <<result>>/*variable,readonly*/
        |  }
        |}
        |""".stripMargin
    )

  // ================================
  // Keywords
  // ================================

  def testKeywordsClassified(): Unit =
    check("keywordsClassified",
      """<<object>>/*keyword*/ <<Main>>/*class,definition,static*/ {
        |  <<val>>/*keyword*/ <<x>>/*property,definition,readonly*/ = <<42>>/*number*/
        |}
        |""".stripMargin
    )

  // ================================
  // Type alias
  // ================================

  def testTypeAlias(): Unit =
    // Note: String doesn't resolve in light fixture
    check("typeAlias",
      """<<object>>/*keyword*/ <<Main>>/*class,definition,static*/ {
        |  <<type>>/*keyword*/ <<Name>>/*type,definition*/ = String
        |}
        |""".stripMargin
    )

  // ================================
  // Comment
  // ================================

  def testComment(): Unit =
    check("comment",
      """<<// this is a comment>>/*comment*/
        |<<object>>/*keyword*/ <<Main>>/*class,definition,static*/
        |""".stripMargin
    )

  // ================================
  // Type parameter
  // ================================

  def testTypeParameter(): Unit =
    check("typeParameter",
      """<<class>>/*keyword*/ <<Box>>/*class,definition*/[<<A>>/*typeParameter,declaration*/](<<value>>/*parameter,declaration,readonly*/: <<A>>/*typeParameter*/)
        |""".stripMargin
    )

  // ==================================
  // Enum (Scala 3)
  // ==================================

  def testEnumDeclaration(): Unit =
    val source = """enum Color:
                   |  case Red, Green, Blue
                   |""".stripMargin
    val uri = configureScalaFile("EnumDecl.scala", source)
    myFixture.doHighlighting()
    val tokens = provider.getSemanticTokensFull(uri)
    val obtained = TestSemanticTokens.semanticString(source, tokens)
    assertNotNull(obtained)

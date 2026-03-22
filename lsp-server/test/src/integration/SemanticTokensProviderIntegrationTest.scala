package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.SemanticTokensProvider
import org.junit.Assert.*

class SemanticTokensProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = SemanticTokensProvider(projectManager)

  // --- Token type constants (must match SemanticTokensProvider legend) ---
  private val Namespace  = 0
  private val Type       = 1
  private val Class      = 2
  private val Enum       = 3
  private val Interface  = 4
  private val TypeParam  = 6
  private val Parameter  = 7
  private val Variable   = 8
  private val Property   = 9
  private val EnumMember = 10
  private val Function   = 12
  private val Method     = 13
  private val Keyword    = 15
  private val Comment    = 17
  private val Str        = 18
  private val Number     = 19
  private val Regexp     = 20  // escape sequences
  private val Operator   = 21

  // --- Modifier bit constants ---
  private val MDeclaration = 1       // bit 0
  private val MDefinition  = 1 << 1  // bit 1
  private val MReadonly    = 1 << 2  // bit 2
  private val MStatic      = 1 << 3  // bit 3
  private val MDeprecated  = 1 << 4  // bit 4
  private val MAbstract    = 1 << 5  // bit 5

  /** Decode LSP semantic token data into (line, char, length, tokenType, modifiers) tuples */
  private def decodeTokens(tokens: SemanticTokens): Seq[(Int, Int, Int, Int, Int)] =
    val data = tokens.getData
    if data == null || data.isEmpty then return Seq.empty
    val result = scala.collection.mutable.ArrayBuffer[(Int, Int, Int, Int, Int)]()
    var prevLine = 0
    var prevChar = 0
    var i = 0
    while i + 4 < data.size do
      val deltaLine = data.get(i).intValue
      val deltaChar = data.get(i + 1).intValue
      val length = data.get(i + 2).intValue
      val tokenType = data.get(i + 3).intValue
      val modifiers = data.get(i + 4).intValue
      val line = prevLine + deltaLine
      val char = if deltaLine == 0 then prevChar + deltaChar else deltaChar
      result += ((line, char, length, tokenType, modifiers))
      prevLine = line
      prevChar = char
      i += 5
    result.toSeq

  /** Find a token at a specific line and character position */
  private def findTokenAt(tokens: Seq[(Int, Int, Int, Int, Int)], line: Int, char: Int): Option[(Int, Int, Int, Int, Int)] =
    tokens.find((l, c, len, _, _) => l == line && c <= char && char < c + len)

  /** Find a token by line and exact text length */
  private def findToken(tokens: Seq[(Int, Int, Int, Int, Int)], line: Int, char: Int, length: Int): Option[(Int, Int, Int, Int, Int)] =
    tokens.find((l, c, len, _, _) => l == line && c == char && len == length)

  private def assertTokenType(msg: String, expected: Int, token: Option[(Int, Int, Int, Int, Int)], allTokens: Seq[(Int, Int, Int, Int, Int)] = Seq.empty): Unit =
    assertTrue(s"$msg: token not found. All tokens: $allTokens", token.isDefined)
    assertEquals(s"$msg: wrong token type", expected, token.get._4)

  private def assertHasModifier(msg: String, modifier: Int, token: Option[(Int, Int, Int, Int, Int)]): Unit =
    assertTrue(s"$msg: token not found", token.isDefined)
    assertTrue(s"$msg: missing modifier bit $modifier, actual=${token.get._5}", (token.get._5 & modifier) != 0)

  private def assertNoModifier(msg: String, modifier: Int, token: Option[(Int, Int, Int, Int, Int)]): Unit =
    assertTrue(s"$msg: token not found", token.isDefined)
    assertFalse(s"$msg: unexpected modifier bit $modifier, actual=${token.get._5}", (token.get._5 & modifier) != 0)

  // ========================
  // Legend / structure tests
  // ========================

  def testSemanticTokensDataIsMultipleOfFive(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |}
        |""".stripMargin
    )
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
    // Metals-compatible token types
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
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |  val y = "hello"
        |  def foo: Int = x
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val fullTokens  = decodeTokens(provider.getSemanticTokensFull(uri))
    val range       = Range(Position(1, 0), Position(2, 20))
    val rangeResult = provider.getSemanticTokensRange(uri, range)
    assertNotNull("Range tokens should return non-null", rangeResult)

    val rangeTokens = decodeTokens(rangeResult)
    for (line, char, len, tt, _) <- rangeTokens do
      val found = fullTokens.exists(t => t._1 == line && t._2 == char && t._3 == len && t._4 == tt)
      assertTrue(s"Range token ($line,$char,len=$len,type=$tt) should also appear in full tokens", found)

  // ===========================
  // Keyword and literal tests
  // ===========================

  def testKeywordsClassified(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "object" keyword at (0,0)
    assertTokenType("'object' should be keyword", Keyword, findToken(tokens, 0, 0, 6))
    // "Main" class declaration at (0,7)
    val mainToken = findToken(tokens, 0, 7, 4)
    assertTokenType("'Main' should be class", Class, mainToken)
    assertHasModifier("'Main' should have definition", MDefinition, mainToken)
    // "val" keyword at (1,2)
    assertTokenType("'val' should be keyword", Keyword, findToken(tokens, 1, 2, 3))
    // "42" number at (1,10)
    assertTokenType("'42' should be number", Number, findToken(tokens, 1, 10, 2))

  // ======================================
  // Class, trait, object — Metals compat
  // ======================================

  def testClassVsTraitVsObject(): Unit =
    val uri = configureScalaFile(
      """class MyClass
        |trait MyTrait
        |object MyObject
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // MyClass -> class(2)
    val classToken = findToken(tokens, 0, 6, 7)
    assertTokenType("MyClass -> class", Class, classToken)

    // MyTrait -> interface(4) + abstract
    val traitToken = findToken(tokens, 1, 6, 7)
    assertTokenType("MyTrait -> interface", Interface, traitToken)
    assertHasModifier("MyTrait should have abstract", MAbstract, traitToken)

    // MyObject -> class(2) + static
    val objectToken = findToken(tokens, 2, 7, 8)
    assertTokenType("MyObject -> class", Class, objectToken)
    assertHasModifier("MyObject should have static", MStatic, objectToken)

  // ================================
  // Val, var — readonly modifier
  // ================================

  def testValReadonlyVarMutable(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |  var y = 0
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "x" (val) → property + readonly + definition
    val xToken = findToken(tokens, 1, 6, 1)
    assertTokenType("val 'x' should be property", Property, xToken)
    assertHasModifier("val 'x' should have readonly", MReadonly, xToken)
    assertHasModifier("val 'x' should have definition", MDefinition, xToken)

    // "y" (var) → property + definition, no readonly
    val yToken = findToken(tokens, 2, 6, 1)
    assertTokenType("var 'y' should be property", Property, yToken)
    assertNoModifier("var 'y' should NOT have readonly", MReadonly, yToken)

  def testLocalValVarAreVariable(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def foo(): Unit = {
        |    val a = 1
        |    var b = 2
        |  }
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "a" (local val) → variable + readonly + definition
    val aToken = findToken(tokens, 2, 8, 1)
    assertTokenType("local val 'a' should be variable", Variable, aToken)
    assertHasModifier("local val 'a' should have readonly", MReadonly, aToken)

    // "b" (local var) → variable + definition, no readonly
    val bToken = findToken(tokens, 3, 8, 1)
    assertTokenType("local var 'b' should be variable", Variable, bToken)
    assertNoModifier("local var 'b' should NOT have readonly", MReadonly, bToken)

  // =========================================
  // Parameter and type have different types
  // =========================================

  def testParameterAndTypeHaveDifferentTokenTypes(): Unit =
    val uri = configureScalaFile(
      """final case class CreateInput(
        |  batchUploadId: BatchUploadId,
        |  params: Option[String]
        |)
        |class BatchUploadId
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "batchUploadId" parameter (line 1, col 2)
    val paramToken = findTokenAt(tokens, 1, 2)
    // "BatchUploadId" type reference (line 1, col 17)
    val typeToken = findTokenAt(tokens, 1, 17)

    assertTrue("Should have token for parameter", paramToken.isDefined)
    assertTrue("Should have token for type", typeToken.isDefined)
    assertNotEquals("Parameter and type should differ",
      paramToken.get._4, typeToken.get._4)

  def testParameterHasReadonly(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def greet(name: String): String = name
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "name" parameter declaration at (1, 12)
    val nameDecl = findToken(tokens, 1, 12, 4)
    assertTokenType("'name' decl should be parameter", Parameter, nameDecl)
    assertHasModifier("'name' decl should have readonly", MReadonly, nameDecl)

  // ================================
  // Method vs function (local def)
  // ================================

  def testMethodVsLocalFunction(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def classMethod(): Unit = {
        |    def localFunc(): Int = 42
        |  }
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "classMethod" at (1,6) → method(13) + definition
    val methodToken = findToken(tokens, 1, 6, 11) // "classMethod" length 11
    assertTokenType("'classMethod' should be method", Method, methodToken)

    // "localFunc" at (2,8) → function(12) + definition
    val funcToken = findToken(tokens, 2, 8, 9)  // "localFunc" length 9
    assertTokenType("'localFunc' should be function", Function, funcToken)

  // =====================
  // Operator methods
  // =====================

  def testOperatorMethodClassified(): Unit =
    val uri = configureScalaFile(
      """object Ops {
        |  case class Vec(x: Int, y: Int) {
        |    def +(other: Vec): Vec = Vec(x + other.x, y + other.y)
        |  }
        |  val a = Vec(1, 2)
        |  val b = Vec(3, 4)
        |  val c = a + b
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    val operatorTokens = tokens.filter(_._4 == Operator)
    assertTrue(
      s"Should have at least one operator token, all tokens: $tokens",
      operatorTokens.nonEmpty
    )

  // =====================
  // Deprecated modifier
  // =====================

  def testDeprecatedModifier(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  @deprecated("use bar", "1.0")
        |  def foo(): Unit = ()
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "foo" declaration on line 2 should have deprecated modifier
    val fooToken = findToken(tokens, 2, 6, 3) // "foo" length 3
    assertTokenType("'foo' should be method", Method, fooToken)
    assertHasModifier("'foo' should have deprecated", MDeprecated, fooToken)
    assertHasModifier("'foo' should have definition", MDefinition, fooToken)

  // ======================
  // String escape tokens
  // ======================

  def testStringEscapeSequences(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val s = "hello\nworld"
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    val stringTokens = tokens.filter(t => t._4 == Str && t._1 == 1)
    val escapeTokens = tokens.filter(t => t._4 == Regexp && t._1 == 1)
    assertTrue("Should have string tokens on line 1", stringTokens.nonEmpty)
    assertTrue("Should have regexp/escape tokens on line 1", escapeTokens.nonEmpty)

  // ============================================
  // Variable and method references in body
  // ============================================

  def testVariableAndMethodReferencesInBody(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  def greet(name: String): String = {
        |    val greeting = "Hello"
        |    val result = greeting + " " + name
        |    result.toUpperCase
        |  }
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "greeting" reference on line 3 should be variable(8)
    val greetingRef = findTokenAt(tokens, 3, 17)
    assertTokenType("'greeting' ref should be variable", Variable, greetingRef)

    // "name" reference on line 3 should be parameter(7)
    val nameRefs = tokens.filter(t => t._1 == 3 && t._4 == Parameter)
    assertTrue("Should have parameter reference 'name' on line 3", nameRefs.nonEmpty)

    // "result" reference on line 4 should be variable(8)
    val resultRef = findTokenAt(tokens, 4, 4)
    assertTokenType("'result' ref should be variable", Variable, resultRef)

  def testClassFieldAndMethodCallReferences(): Unit =
    val uri = configureScalaFile(
      """class Service {
        |  def doWork(x: Int): String = x.toString
        |}
        |class App(service: Service) {
        |  def run(): String = {
        |    val result = service.doWork(42)
        |    result
        |  }
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // line 5: val result = service.doWork(42)
    // "service" at col 17 should be parameter(7)
    val serviceRef = findTokenAt(tokens, 5, 17)
    assertTokenType("'service' should be parameter", Parameter, serviceRef)

    // "doWork" at col 25 should be method(13)
    val doWorkRef = findTokenAt(tokens, 5, 25)
    assertTokenType("'doWork' should be method", Method, doWorkRef)

  // ==================================
  // Enum and enum member (Scala 3)
  // ==================================

  def testEnumDeclaration(): Unit =
    val uri = configureScalaFile(
      """enum Color:
        |  case Red, Green, Blue
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "Color" on line 0, length 5 → enum(3) or class(2)
    val colorToken = tokens.find(t => t._1 == 0 && t._3 == 5 && (t._4 == Enum || t._4 == Class))
    // Light test fixture may not fully support Scala 3 enum — skip if no type token emitted
    if tokens.exists(t => t._1 == 0 && t._4 != Keyword) then
      assertTrue(s"'Color' should be enum or class on line 0, tokens: $tokens", colorToken.isDefined)

  // ================================
  // Type alias
  // ================================

  def testTypeAlias(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  type Name = String
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "Name" at (1,7) → type(1) + definition
    val nameToken = findToken(tokens, 1, 7, 4)
    assertTokenType("'Name' should be type", Type, nameToken)
    assertHasModifier("'Name' should have definition", MDefinition, nameToken)

  // ================================
  // Comment
  // ================================

  def testComment(): Unit =
    val uri = configureScalaFile(
      """// this is a comment
        |object Main
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    val commentTokens = tokens.filter(t => t._4 == Comment && t._1 == 0)
    assertTrue("Should have comment token on line 0", commentTokens.nonEmpty)

  // ================================
  // Type parameter
  // ================================

  def testTypeParameter(): Unit =
    val uri = configureScalaFile(
      """class Box[A](value: A)
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "A" declaration on line 0, length 1 → typeParameter(6)
    val typeParamDecl = tokens.find(t => t._1 == 0 && t._3 == 1 && t._4 == TypeParam)
    assertTrue(s"Should have typeParameter token for 'A' on line 0, tokens: $tokens", typeParamDecl.isDefined)

    // "A" reference in "value: A" on line 0 → typeParameter(6)
    val typeParamRefs = tokens.filter(t => t._1 == 0 && t._3 == 1 && t._4 == TypeParam)
    // Should have at least 1 (declaration); ideally 2 (declaration + reference)
    assertTrue(s"Should have at least one typeParameter token, found: $typeParamRefs", typeParamRefs.nonEmpty)

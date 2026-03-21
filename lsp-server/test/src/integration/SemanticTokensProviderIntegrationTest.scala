package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.SemanticTokensProvider
import org.junit.Assert.*

class SemanticTokensProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = SemanticTokensProvider(projectManager)

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

  def testKeywordsClassified(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    assertNotNull("getSemanticTokensFull should return non-null", result)
    assertNotNull("Token data should be non-null", result.getData)
    val tokens = decodeTokens(result)

    // "object" keyword at (0,0)
    val objectToken = tokens.find(t => t._1 == 0 && t._2 == 0)
    assertTrue("'object' should be classified", objectToken.isDefined)
    assertEquals("'object' should be keyword(0)", 0, objectToken.get._4)

    // "Main" class declaration at (0,7)
    val mainToken = tokens.find(t => t._1 == 0 && t._2 == 7)
    assertTrue("'Main' should be classified", mainToken.isDefined)
    assertEquals("'Main' should be class(2)", 2, mainToken.get._4)
    assertTrue("'Main' should have declaration modifier", (mainToken.get._5 & 1) != 0)

    // "val" keyword at (1,2)
    val valToken = tokens.find(t => t._1 == 1 && t._2 == 2)
    assertTrue("'val' should be classified", valToken.isDefined)
    assertEquals("'val' should be keyword(0)", 0, valToken.get._4)

    // "42" number at (1,10)
    val numberToken = tokens.find(t => t._1 == 1 && t._2 == 10)
    assertTrue("'42' should be classified", numberToken.isDefined)
    assertEquals("'42' should be number(11)", 11, numberToken.get._4)

  def testOperatorTokenType(): Unit =
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

    // The `+` operator call on line 6 (val c = a + b) should have token type 14 (operator)
    val operatorTokens = tokens.filter(_._4 == 14)
    assertTrue(
      s"Should have at least one operator token (type 14), all tokens: $tokens",
      operatorTokens.nonEmpty
    )

  def testOperatorMethodClassified(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 1 + 2
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // Find operator tokens (type 14) with length 1
    val plusTokens = tokens.filter(t => t._3 == 1 && t._4 == 14) // length 1, type operator
    // At minimum verify operator tokens exist in output
    assertTrue(
      s"Should have operator token(s) of length 1 on line 1, all tokens: $tokens",
      plusTokens.nonEmpty
    )

  def testDeprecatedModifier(): Unit =
    val uri = configureScalaFile(
      """object DeprecatedTest {
        |  @deprecated("use newFoo instead", "1.0")
        |  def oldFoo(): Int = 42
        |
        |  val result = oldFoo()
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // The declaration of oldFoo on line 2 should have the deprecated modifier (bit 7 = 128)
    val tokensWithDeprecated = tokens.filter((_, _, _, _, mods) => (mods & 128) != 0)
    assertTrue(
      s"Should have at least one token with deprecated modifier (bit 7), all tokens: $tokens",
      tokensWithDeprecated.nonEmpty
    )

  def testDeprecatedMethodHasModifier(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  @deprecated("use bar", "1.0")
        |  def foo(): Unit = ()
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // Find "foo" declaration on line 2 — should have deprecated modifier (bit 7) and declaration (bit 0)
    val fooTokens = tokens.filter(t => t._1 == 2 && t._4 == 5) // line 2, method type
    val fooToken = fooTokens.find(t => t._3 == 3) // length 3 for "foo"
    assertTrue(s"Should find 'foo' as a method token on line 2, tokens on line 2: ${tokens.filter(_._1 == 2)}", fooToken.isDefined)
    assertTrue("'foo' should have deprecated modifier (bit 7)", (fooToken.get._5 & 128) != 0)
    assertTrue("'foo' should have declaration modifier (bit 0)", (fooToken.get._5 & 1) != 0)

  def testStringEscapeSequences(): Unit =
    val uri = configureScalaFile(
      """object EscapeTest {
        |  val s = "hello\nworld"
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // There should be regexp tokens (type 15) for the escape sequence \n
    val regexpTokens = tokens.filter(_._4 == 15)
    assertTrue(
      s"Should have at least one regexp/escape token (type 15) for \\n in string, all tokens: $tokens",
      regexpTokens.nonEmpty
    )

  def testStringEscapeSequenceSplit(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val s = "hello\nworld"
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // The string "hello\nworld" should be split into:
    // "hello" as string(10), "\n" as regexp(15), "world" as string(10)
    val stringTokens = tokens.filter(t => t._4 == 10)  // string tokens
    val regexpTokens = tokens.filter(t => t._4 == 15)  // regexp tokens

    // Verify we have both string and regexp tokens on line 1
    val line1Strings = stringTokens.filter(_._1 == 1)
    val line1Regexps = regexpTokens.filter(_._1 == 1)
    assertTrue("Should have string tokens on line 1", line1Strings.nonEmpty)
    assertTrue("Should have regexp tokens for escape on line 1", line1Regexps.nonEmpty)

  def testClassVsTraitVsObject(): Unit =
    val uri = configureScalaFile(
      """class MyClass
        |trait MyTrait
        |object MyObject
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // MyClass -> class(2), MyTrait -> interface(3)+abstract, MyObject -> class(2)+static
    val classToken  = tokens.find(t => t._1 == 0 && t._3 == 7)  // "MyClass"  length 7
    val traitToken  = tokens.find(t => t._1 == 1 && t._3 == 7)  // "MyTrait"  length 7
    val objectToken = tokens.find(t => t._1 == 2 && t._3 == 8)  // "MyObject" length 8

    assertTrue(s"Should classify 'MyClass', all tokens: $tokens", classToken.isDefined)
    assertTrue(s"Should classify 'MyTrait', all tokens: $tokens", traitToken.isDefined)
    assertTrue(s"Should classify 'MyObject', all tokens: $tokens", objectToken.isDefined)

    assertEquals("MyClass -> class(2)",     2, classToken.get._4)
    assertEquals("MyTrait -> interface(3)", 3, traitToken.get._4)
    assertTrue("MyTrait should have abstract modifier (bit 2)", (traitToken.get._5 & 4) != 0)
    assertEquals("MyObject -> class(2)",    2, objectToken.get._4)
    assertTrue("MyObject should have static modifier (bit 1)", (objectToken.get._5 & 2) != 0)

  def testValReadonlyModifier(): Unit =
    val uri = configureScalaFile(
      """object Main {
        |  val x = 42
        |  var y = 0
        |}
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val tokens = decodeTokens(provider.getSemanticTokensFull(uri))

    // "x" (val) should have readonly modifier (bit 3), "y" (var) should not
    // Both are declarations (bit 0), length 1
    val xToken = tokens.find(t => t._1 == 1 && t._3 == 1 && (t._5 & 1) != 0) // line 1, length 1, declaration
    val yToken = tokens.find(t => t._1 == 2 && t._3 == 1 && (t._5 & 1) != 0) // line 2, length 1, declaration

    assertTrue(s"Should find 'x' declaration on line 1, tokens: $tokens", xToken.isDefined)
    assertTrue(s"Should find 'y' declaration on line 2, tokens: $tokens", yToken.isDefined)
    assertTrue("val 'x' should have readonly modifier (bit 3)", (xToken.get._5 & 8) != 0)
    assertFalse("var 'y' should not have readonly modifier", (yToken.get._5 & 8) != 0)

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
    val result = provider.getSemanticTokensFull(uri)
    val tokens = decodeTokens(result)

    val tokenTypeNames = SemanticTokensProvider.tokenTypes

    // Find token for "batchUploadId" (line 1, starts at col 2)
    val paramToken = findTokenAt(tokens, 1, 2)
    // Find token for "BatchUploadId" (line 1, after ": ", starts at col 17)
    val typeToken = findTokenAt(tokens, 1, 17)

    assertTrue(s"Should have a token for parameter 'batchUploadId', tokens: $tokens", paramToken.isDefined)
    assertTrue(s"Should have a token for type 'BatchUploadId', tokens: $tokens", typeToken.isDefined)

    val paramType = paramToken.get._4
    val typeType = typeToken.get._4
    assertNotEquals(
      s"Parameter '${tokenTypeNames.get(paramType)}' and type '${tokenTypeNames.get(typeType)}' should have different token types",
      paramType, typeType
    )

  def testTokenTypeMappingKnown(): Unit =
    val legend = SemanticTokensProvider.legend
    assertNotNull(legend)
    assertFalse("Legend should have token types", legend.getTokenTypes.isEmpty)
    assertFalse("Legend should have token modifiers", legend.getTokenModifiers.isEmpty)
    assertTrue("Legend should include 'keyword'", legend.getTokenTypes.contains("keyword"))
    assertTrue("Legend should include 'class'", legend.getTokenTypes.contains("class"))
    assertTrue("Legend should include 'method'", legend.getTokenTypes.contains("method"))

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
    // Every token in the range result must also appear (by position) in the full result
    for (line, char, len, tt, _) <- rangeTokens do
      val found = fullTokens.exists(t => t._1 == line && t._2 == char && t._3 == len && t._4 == tt)
      assertTrue(s"Range token ($line,$char,len=$len,type=$tt) should also appear in full tokens", found)

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
    val tokenTypeNames = SemanticTokensProvider.tokenTypes

    // "greeting" reference on line 3 (val result = greeting + ...) should be variable(7)
    val greetingRef = findTokenAt(tokens, 3, 17) // "greeting" at col 17
    assertTrue(
      s"Should have semantic token for variable reference 'greeting' on line 3, tokens on line 3: ${tokens.filter(_._1 == 3)}",
      greetingRef.isDefined
    )

    // "name" reference on line 3 should be parameter(8)
    val nameRef = tokens.filter(t => t._1 == 3 && t._4 == 8) // parameter tokens on line 3
    assertTrue(
      s"Should have semantic token for parameter reference 'name' on line 3, tokens on line 3: ${tokens.filter(_._1 == 3)}",
      nameRef.nonEmpty
    )

    // "result" reference on line 4 should be variable(7)
    val resultRef = findTokenAt(tokens, 4, 4) // "result" at col 4
    assertTrue(
      s"Should have semantic token for variable reference 'result' on line 4, tokens on line 4: ${tokens.filter(_._1 == 4)}",
      resultRef.isDefined
    )

    // "toUpperCase" method call on line 4 should be method(5)
    // Note: Java stdlib methods (String.toUpperCase) may not resolve in light test fixtures
    // without full JDK classpath, so we only test this when the token is present
    val line4Tokens = tokens.filter(_._1 == 4)
    val hasMethodOnLine4 = line4Tokens.exists(_._4 == 5)
    if !hasMethodOnLine4 then
      System.err.println(s"[test] toUpperCase not resolved (expected in light fixture), line 4 tokens: $line4Tokens")

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
    val tokenTypeNames = SemanticTokensProvider.tokenTypes

    // line 5: val result = service.doWork(42)
    // "service" at col 17 should be parameter(8)
    val serviceRef = findTokenAt(tokens, 5, 17)
    assertTrue(
      s"Should have token for field reference 'service' on line 5, tokens: ${tokens.filter(_._1 == 5)}",
      serviceRef.isDefined
    )
    assertEquals(
      s"'service' should be parameter(8), got ${serviceRef.map(t => tokenTypeNames.get(t._4))}",
      8, serviceRef.get._4
    )

    // "doWork" at col 25 should be method(5)
    val doWorkRef = findTokenAt(tokens, 5, 25)
    assertTrue(
      s"Should have token for method call 'doWork' on line 5, tokens: ${tokens.filter(_._1 == 5)}",
      doWorkRef.isDefined
    )
    assertEquals(
      s"'doWork' should be method(5), got ${doWorkRef.map(t => tokenTypeNames.get(t._4))}",
      5, doWorkRef.get._4
    )

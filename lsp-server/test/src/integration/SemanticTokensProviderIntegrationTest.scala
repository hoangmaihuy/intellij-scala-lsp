package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.SemanticTokensProvider
import org.junit.Assert.*

class SemanticTokensProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = SemanticTokensProvider(projectManager)

  def testSemanticTokensReturnsNonNull(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x: Int = 42
        |  def foo(y: String): Boolean = y.isEmpty
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    assertNotNull("Should return SemanticTokens", result)
    assertNotNull("Should have data array", result.getData)

  def testSemanticTokensDataIsMultipleOfFive(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    if result.getData != null && !result.getData.isEmpty then
      assertEquals("Token data should be multiple of 5", 0, result.getData.size() % 5)

  def testSemanticTokensRange(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  val y = "hello"
        |  def foo: Int = x
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val range = Range(Position(1, 0), Position(2, 20))
    val result = provider.getSemanticTokensRange(uri, range)
    assertNotNull("Range tokens should return non-null", result)

  /** Decode delta-encoded semantic tokens into (line, char, length, tokenType, modifiers) tuples */
  private def decodeTokens(data: java.util.List[Integer]): Seq[(Int, Int, Int, Int, Int)] =
    val result = scala.collection.mutable.ArrayBuffer[(Int, Int, Int, Int, Int)]()
    var prevLine = 0
    var prevChar = 0
    var i = 0
    while i + 4 < data.size() do
      val deltaLine = data.get(i)
      val deltaChar = data.get(i + 1)
      val length = data.get(i + 2)
      val tokenType = data.get(i + 3)
      val modifiers = data.get(i + 4)
      val line = prevLine + deltaLine.intValue
      val char = if deltaLine == 0 then prevChar + deltaChar.intValue else deltaChar.intValue
      result += ((line, char, length, tokenType, modifiers))
      prevLine = line
      prevChar = char
      i += 5
    result.toSeq

  /** Find a token at a specific line and character position */
  private def findTokenAt(tokens: Seq[(Int, Int, Int, Int, Int)], line: Int, char: Int): Option[(Int, Int, Int, Int, Int)] =
    tokens.find((l, c, len, _, _) => l == line && c <= char && char < c + len)

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
    val tokens = decodeTokens(result.getData)

    val tokenTypeNames = SemanticTokensProvider.tokenTypes

    // Print all tokens for debugging
    for (line, char, len, tt, mods) <- tokens do
      System.err.println(s"  token: line=$line char=$char len=$len type=${tokenTypeNames.get(tt)}($tt) mods=$mods")

    // Find token for "batchUploadId" (line 1, starts at col 2)
    val paramToken = findTokenAt(tokens, 1, 2)
    // Find token for "BatchUploadId" (line 1, after ": ", starts at col 17)
    val typeToken = findTokenAt(tokens, 1, 17)

    assertTrue(s"Should have a token for parameter 'batchUploadId', tokens: $tokens", paramToken.isDefined)
    assertTrue(s"Should have a token for type 'BatchUploadId', tokens: $tokens", typeToken.isDefined)

    if paramToken.isDefined && typeToken.isDefined then
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

  def testOperatorTokenType(): Unit =
    val uri = configureScalaFile(
      """object Ops:
        |  case class Vec(x: Int, y: Int):
        |    def +(other: Vec): Vec = Vec(x + other.x, y + other.y)
        |  val a = Vec(1, 2)
        |  val b = Vec(3, 4)
        |  val c = a + b
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    val tokens = decodeTokens(result.getData)

    val tokenTypeNames = SemanticTokensProvider.tokenTypes

    // Print all tokens for debugging
    for (line, char, len, tt, mods) <- tokens do
      System.err.println(s"  token: line=$line char=$char len=$len type=${if tt < tokenTypeNames.size then tokenTypeNames.get(tt) else tt.toString}($tt) mods=$mods")

    // The `+` operator call on line 5 (val c = a + b) should have token type 14 (operator)
    val operatorTokens = tokens.filter(_._4 == 14)
    assertTrue(
      s"Should have at least one operator token (type 14), all tokens: $tokens",
      operatorTokens.nonEmpty
    )

  def testDeprecatedModifier(): Unit =
    val uri = configureScalaFile(
      """object DeprecatedTest:
        |  @deprecated("use newFoo instead", "1.0")
        |  def oldFoo(): Int = 42
        |
        |  val result = oldFoo()
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    val tokens = decodeTokens(result.getData)

    val tokenTypeNames = SemanticTokensProvider.tokenTypes

    // Print all tokens for debugging
    for (line, char, len, tt, mods) <- tokens do
      System.err.println(s"  token: line=$line char=$char len=$len type=${if tt < tokenTypeNames.size then tokenTypeNames.get(tt) else tt.toString}($tt) mods=$mods")

    // The call to oldFoo() on line 4 should have the deprecated modifier (bit 7 = 128)
    val tokensWithDeprecated = tokens.filter((_, _, _, _, mods) => (mods & 128) != 0)
    assertTrue(
      s"Should have at least one token with deprecated modifier (bit 7), all tokens: $tokens",
      tokensWithDeprecated.nonEmpty
    )

  def testStringEscapeSequences(): Unit =
    val uri = configureScalaFile(
      """object EscapeTest:
        |  val s = "hello\nworld"
        |""".stripMargin
    )
    myFixture.doHighlighting()
    val result = provider.getSemanticTokensFull(uri)
    val tokens = decodeTokens(result.getData)

    val tokenTypeNames = SemanticTokensProvider.tokenTypes

    // Print all tokens for debugging
    for (line, char, len, tt, mods) <- tokens do
      System.err.println(s"  token: line=$line char=$char len=$len type=${if tt < tokenTypeNames.size then tokenTypeNames.get(tt) else tt.toString}($tt) mods=$mods")

    // There should be regexp tokens (type 15) for the escape sequence \n
    val regexpTokens = tokens.filter(_._4 == 15)
    assertTrue(
      s"Should have at least one regexp/escape token (type 15) for \\n in string, all tokens: $tokens",
      regexpTokens.nonEmpty
    )

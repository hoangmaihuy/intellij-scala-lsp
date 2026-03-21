package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiClass, PsiElement, PsiFile, PsiMethod, PsiNamedElement, PsiPolyVariantReference}
import org.eclipse.lsp4j.*

import java.util.{List as JList}
import scala.jdk.CollectionConverters.*

object SemanticTokensProvider:

  val tokenTypes: JList[String] = JList.of(
    "keyword",       // 0
    "type",          // 1
    "class",         // 2
    "interface",     // 3
    "enum",          // 4
    "method",        // 5
    "property",      // 6
    "variable",      // 7
    "parameter",     // 8
    "typeParameter", // 9
    "string",        // 10
    "number",        // 11
    "comment",       // 12
    "function",      // 13
    "operator",      // 14
    "regexp"         // 15 (for escape sequences in strings)
  )

  val tokenModifiers: JList[String] = JList.of(
    "declaration",   // bit 0
    "static",        // bit 1
    "abstract",      // bit 2
    "readonly",      // bit 3
    "modification",  // bit 4
    "documentation", // bit 5
    "lazy",          // bit 6
    "deprecated"     // bit 7
  )

  val legend: SemanticTokensLegend = SemanticTokensLegend(tokenTypes, tokenModifiers)

  /** Returns true if the name looks like a symbolic operator (not starting with a letter or underscore). */
  private def isOperatorName(name: String): Boolean =
    name.nonEmpty && !name.head.isLetter && name.head != '_'

  /** Classify a resolved PSI element into a semantic token type index. */
  def classifyElement(element: PsiElement): Option[Int] =
    if ScalaTypes.isParameter(element) then Some(8)       // parameter (covers ScClassParameter too)
    else if ScalaTypes.isEnum(element) then Some(4)       // enum (before ScClass — ScEnum extends ScClass)
    else if ScalaTypes.isClass(element) then Some(2)      // class
    else if ScalaTypes.isTrait(element) then Some(3)      // interface
    else if ScalaTypes.isObject(element) then Some(2)     // class (object)
    else if ScalaTypes.isTypeAlias(element) then Some(1)  // type
    else if ScalaTypes.isTypeParam(element) then Some(9)  // typeParameter
    else if ScalaTypes.isFunction(element) || element.isInstanceOf[PsiMethod] then
      // Check if it's an operator method (symbolic name)
      val methodName = try element.asInstanceOf[PsiNamedElement].getName catch case _: Exception => null
      if methodName != null && isOperatorName(methodName) then Some(14) // operator
      else
        // Synthetic accessors (e.g. case class param getters) should classify as their original element
        val navElement = element.getNavigationElement
        if navElement != null && (navElement ne element) then
          if ScalaTypes.isParameter(navElement) then Some(8)       // parameter accessor
          else if ScalaTypes.isBindingPattern(navElement) then Some(6) // property accessor
          else if ScalaTypes.isFieldId(navElement) then Some(6)    // property accessor
          else Some(5)                                              // method
        else Some(5) // method
    else if ScalaTypes.isBindingPattern(element) then classifyBinding(element)
    else if ScalaTypes.isFieldId(element) then classifyFieldId(element)
    else if element.isInstanceOf[PsiClass] then Some(2) // Java class fallback
    else
      System.err.println(s"[SemanticTokens] Unclassified resolved element: ${element.getClass.getName}")
      None

  /** Classify a binding pattern as property (class member) or variable (local). */
  private def classifyBinding(element: PsiElement): Option[Int] =
    if ScalaTypes.isBindingPattern(element) then
      if ScalaTypes.isClassMember(element) then Some(6)  // property
      else Some(7)                                        // variable (local val/var/pattern)
    else
      val parent = element.getParent
      if ScalaTypes.isValue(parent) || ScalaTypes.isPatternDefinition(parent) then
        if ScalaTypes.isTemplateBody(parent.getParent) then Some(6) else Some(7)
      else if ScalaTypes.isVariable(parent) then
        if ScalaTypes.isTemplateBody(parent.getParent) then Some(6) else Some(7)
      else Some(7)

  /** Classify a field identifier based on parent context. */
  private def classifyFieldId(element: PsiElement): Option[Int] =
    val parent = element.getParent
    if ScalaTypes.isValue(parent) || ScalaTypes.isPatternDefinition(parent) || ScalaTypes.isVariable(parent) then
      if ScalaTypes.isTemplateBody(parent.getParent) then Some(6) // property
      else Some(7) // variable
    else Some(7)

  /** Get modifier bits for a resolved element */
  def classifyModifiers(element: PsiElement): Int =
    var mods =
      if ScalaTypes.isTrait(element) then 4       // abstract
      else if ScalaTypes.isObject(element) then 2  // static
      else 0
    // Check for deprecated annotation via ScAnnotationsHolder (Scala) or PsiModifierListOwner (Java)
    val isDeprecated =
      if ScalaTypes.isAnnotationsHolder(element) then
        try
          // First try qualified name lookup (works when stdlib is available)
          val byQName = ScalaTypes.hasAnnotation(element, "scala.deprecated") || ScalaTypes.hasAnnotation(element, "java.lang.Deprecated")
          if byQName then true
          else
            // Fallback: check annotation text for "deprecated" (works without stdlib)
            ScalaTypes.getAnnotations(element).exists: ann =>
              val typeText = try
                val typeElem = ann.getClass.getMethod("typeElement").invoke(ann)
                typeElem.asInstanceOf[PsiElement].getText
              catch case _: Exception => ""
              typeText == "deprecated" || typeText.endsWith(".deprecated") || typeText == "Deprecated"
        catch case _: Exception => false
      else
        element match
          case mod: com.intellij.psi.PsiModifierListOwner =>
            try mod.hasAnnotation("java.lang.Deprecated")
            catch case _: Exception => false
          case _ => false
    if isDeprecated then mods |= 128 // bit 7 = deprecated
    mods

class SemanticTokensProvider(projectManager: IntellijProjectManager):

  import SemanticTokensProvider.*

  def getSemanticTokensFull(uri: String): SemanticTokens =
    try
      computeTokens(uri, None)
    catch
      case e: Exception =>
        System.err.println(s"[SemanticTokens] Error computing full tokens: ${e.getMessage}")
        e.printStackTrace(System.err)
        SemanticTokens(java.util.Collections.emptyList())

  def getSemanticTokensRange(uri: String, range: Range): SemanticTokens =
    try
      computeTokens(uri, Some(range))
    catch
      case e: Exception =>
        System.err.println(s"[SemanticTokens] Error computing range tokens: ${e.getMessage}")
        e.printStackTrace(System.err)
        SemanticTokens(java.util.Collections.emptyList())

  private def computeTokens(uri: String, rangeOpt: Option[Range]): SemanticTokens =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val rangeStartOffset = rangeOpt.map(r => PsiUtils.positionToOffset(document, r.getStart)).getOrElse(0)
        val rangeEndOffset = rangeOpt.map(r => PsiUtils.positionToOffset(document, r.getEnd)).getOrElse(document.getTextLength)

        // Collect semantic tokens by walking the PSI tree
        val tokens = scala.collection.mutable.ArrayBuffer[(Int, Int, Int, Int)]() // (offset, length, tokenType, modifiers)

        collectTokens(psiFile, rangeStartOffset, rangeEndOffset, tokens)

        // Sort by position and delta-encode
        val sorted = tokens.sortBy(_._1)
        var prevLine = 0
        var prevChar = 0
        val data = new java.util.ArrayList[Integer]()

        for (offset, length, tokenType, tokenModifiers) <- sorted do
          val startPos = PsiUtils.offsetToPosition(document, offset)
          val deltaLine = startPos.getLine - prevLine
          val deltaStartChar =
            if deltaLine == 0 then startPos.getCharacter - prevChar
            else startPos.getCharacter

          data.add(deltaLine)
          data.add(deltaStartChar)
          data.add(length)
          data.add(tokenType)
          data.add(tokenModifiers)

          prevLine = startPos.getLine
          prevChar = startPos.getCharacter

        SemanticTokens(data)

      result.getOrElse(SemanticTokens(java.util.Collections.emptyList()))

  /** Recursively walk the PSI tree and collect semantic tokens */
  private def collectTokens(
    element: PsiElement,
    rangeStart: Int,
    rangeEnd: Int,
    tokens: scala.collection.mutable.ArrayBuffer[(Int, Int, Int, Int)]
  ): Unit =
    val textRange = element.getTextRange
    if textRange == null || textRange.getEndOffset < rangeStart || textRange.getStartOffset > rangeEnd then
      return // Skip elements outside the range

    // Check if this is a reference that can be resolved
    val ref = element.getReference
    if ref != null && isIdentifierLike(element) then
      val resolved = try
        ref match
          case poly: PsiPolyVariantReference =>
            poly.multiResolve(false).flatMap(rr => Option(rr.getElement)).headOption
          case _ =>
            Option(ref.resolve())
      catch
        case e: Exception =>
          System.err.println(s"[SemanticTokens] Exception resolving '${element.getText.take(30)}': ${e.getClass.getSimpleName}: ${e.getMessage}")
          None

      resolved match
        case Some(res) =>
          val classified = classifyElement(res)
          classified match
            case Some(tokenType) =>
              val modifiers = classifyModifiers(res)
              val nameRange = getNameRange(element)
              tokens += ((nameRange._1, nameRange._2, tokenType, modifiers))
            case None => ()
        case None => ()

    // Check for keyword tokens (leaf elements with keyword token type)
    if element.getChildren.isEmpty then
      val elementType = element.getNode.getElementType.toString
      val text = element.getText
      if isStringToken(elementType) then
        // Check if this is a raw interpolated string (raw"..." or r"...") — no escape splitting
        val prevSiblingText = Option(element.getPrevSibling).map(_.getText).getOrElse("")
        val isRawString = prevSiblingText == "raw" || prevSiblingText == "r"
        if isRawString then
          // Raw strings: emit the whole token as a plain string (no escape highlighting)
          tokens += ((textRange.getStartOffset, textRange.getLength, 10, 0))
        else
          // Split string into segments: non-escape parts (type 10) and escape sequences (type 15)
          val subTokens = splitStringEscapes(textRange.getStartOffset, text)
          tokens ++= subTokens
      else
        classifyLeafToken(elementType, text) match
          case Some(tokenType) =>
            tokens += ((textRange.getStartOffset, textRange.getLength, tokenType, 0))
          case None =>
            // Classify symbolic identifiers as operator (only for actual identifier tokens)
            if (elementType == "identifier" || elementType == "tIDENTIFIER") && isOperatorName(text) then
              tokens += ((textRange.getStartOffset, textRange.getLength, 14, 0)) // operator

    // Check for declarations (definitions that introduce names)
    if isDeclaration(element) then
      classifyDeclaration(element).foreach: (tokenType, baseModifiers) =>
        getNameIdentifier(element).foreach: (offset, length) =>
          val elementModifiers = classifyModifiers(element)
          tokens += ((offset, length, tokenType, baseModifiers | elementModifiers | 1)) // bit 0 = declaration

    // Recurse into children
    var child = element.getFirstChild
    while child != null do
      collectTokens(child, rangeStart, rangeEnd, tokens)
      child = child.getNextSibling

  private def isIdentifierLike(element: PsiElement): Boolean =
    ScalaTypes.isReference(element)

  private def isDeclaration(element: PsiElement): Boolean =
    ScalaTypes.isClass(element) || ScalaTypes.isTrait(element) || ScalaTypes.isObject(element) || ScalaTypes.isEnum(element) ||
    ScalaTypes.isFunctionDefinition(element) || ScalaTypes.isFunctionDeclaration(element) ||
    ScalaTypes.isTypeAlias(element) || ScalaTypes.isGiven(element) ||
    ScalaTypes.isParameter(element) || ScalaTypes.isBindingPattern(element)

  private def classifyDeclaration(element: PsiElement): Option[(Int, Int)] =
    if ScalaTypes.isParameter(element) then Some((8, 0))       // parameter
    else if ScalaTypes.isEnum(element) then Some((4, 0))       // enum (before ScClass)
    else if ScalaTypes.isClass(element) then Some((2, 0))      // class
    else if ScalaTypes.isTrait(element) then Some((3, 4))      // interface + abstract
    else if ScalaTypes.isObject(element) then Some((2, 2))     // class + static
    else if ScalaTypes.isFunction(element) then
      val name = try element.asInstanceOf[PsiNamedElement].getName catch case _: Exception => null
      if name != null && isOperatorName(name) then Some((14, 0)) // operator
      else Some((5, 0)) // method
    else if ScalaTypes.isTypeAlias(element) then Some((1, 0))  // type
    else if ScalaTypes.isGiven(element) then Some((5, 0))      // method (given)
    else if ScalaTypes.isBindingPattern(element) then Some(classifyBindingDeclaration(element))
    else None

  private def classifyBindingDeclaration(element: PsiElement): (Int, Int) =
    if ScalaTypes.isBindingPattern(element) then
      val isMember = ScalaTypes.isClassMember(element)
      if ScalaTypes.isVal(element) then
        if isMember then (6, 8) // property + readonly
        else (7, 8)             // variable + readonly (local val)
      else if ScalaTypes.isVar(element) then
        if isMember then (6, 0) // property (mutable)
        else (7, 0)             // variable (local var)
      else (7, 0)               // pattern/generator, no readonly
    else
      val parent = element.getParent
      if ScalaTypes.isValue(parent) || ScalaTypes.isPatternDefinition(parent) then
        if ScalaTypes.isTemplateBody(parent.getParent) then (6, 8) else (7, 8)
      else if ScalaTypes.isVariable(parent) then
        if ScalaTypes.isTemplateBody(parent.getParent) then (6, 0) else (7, 0)
      else (7, 0)

  private def getNameIdentifier(element: PsiElement): Option[(Int, Int)] =
    element match
      case named: PsiNamedElement =>
        try
          val nameId = named.getClass.getMethod("getNameIdentifier").invoke(named)
          nameId match
            case e: PsiElement =>
              val r = e.getTextRange
              if r != null then Some((r.getStartOffset, r.getLength)) else None
            case _ => None
        catch
          case _: Exception => None
      case _ => None

  private def getNameRange(element: PsiElement): (Int, Int) =
    // For references, try to get just the name part
    if ScalaTypes.isReference(element) then
      ScalaTypes.getNameId(element) match
        case Some(nameId) =>
          val r = nameId.getTextRange
          if r != null then return (r.getStartOffset, r.getLength)
        case None => ()
    (element.getTextRange.getStartOffset, element.getTextRange.getLength)

  private def isStringToken(elementType: String): Boolean =
    // Match actual toString() values from ScalaTokenTypes:
    // tSTRING → "string content", tMULTILINE_STRING → "multiline string"
    // Also keep legacy string checks for forward compatibility
    elementType.contains("string") || elementType.contains("STRING") ||
    elementType == "tSTRING" || elementType == "tMULTILINE_STRING"

  /**
   * Split a string literal into sub-tokens:
   * - Non-escape parts → token type 10 (string)
   * - Escape sequences (\n, \t, \r, \b, \f, \\, \", \', \uXXXX) → token type 15 (regexp)
   * Returns a sequence of (offset, length, tokenType, modifiers).
   */
  private def splitStringEscapes(startOffset: Int, text: String): Seq[(Int, Int, Int, Int)] =
    val result = scala.collection.mutable.ArrayBuffer[(Int, Int, Int, Int)]()
    var pos = 0
    var segStart = 0

    def flushNonEscape(end: Int): Unit =
      if end > segStart then
        result += ((startOffset + segStart, end - segStart, 10, 0))

    while pos < text.length do
      if text(pos) == '\\' && pos + 1 < text.length then
        flushNonEscape(pos)
        val escLen = if text(pos + 1) == 'u' && pos + 5 < text.length then 6 // \uXXXX
                     else 2
        result += ((startOffset + pos, escLen, 15, 0))
        pos += escLen
        segStart = pos
      else
        pos += 1

    flushNonEscape(pos)
    result.toSeq

  /**
   * The Scala plugin represents keyword token types as ScalaKeywordTokenType(text) where text is the
   * keyword itself (e.g. "object", "val", "def"). ScalaModifierTokenType also uses modifier text
   * (e.g. "abstract", "final"). So elementType.toString() equals the keyword text directly.
   * We match either the keyword text (modern Scala plugin) or the legacy "kXXX" names.
   */
  private val scalaKeywordTexts = Set(
    // Declarations / structure
    "def", "val", "var", "class", "trait", "object", "enum", "type", "given", "using",
    "extension", "export",
    // Imports/packages
    "import", "package",
    // Control flow
    "if", "else", "match", "case", "for", "while", "do", "return", "throw", "try", "catch",
    "finally", "yield", "then",
    // Object-oriented
    "extends", "with", "new", "this", "super",
    // Literals
    "null", "true", "false",
    // Modifiers
    "abstract", "final", "implicit", "lazy", "override", "private", "protected", "sealed",
    "inline", "opaque", "open", "transparent", "infix",
    // Misc Scala 3
    "derives", "end",
    // Legacy uppercase "k" names (older plugin versions)
    "kDEF", "kVAL", "kVAR", "kCLASS", "kTRAIT", "kOBJECT", "kIMPORT", "kPACKAGE",
    "kEXTENDS", "kWITH", "kIF", "kELSE", "kMATCH", "kCASE", "kFOR", "kWHILE", "kDO",
    "kRETURN", "kTHROW", "kTRY", "kCATCH", "kFINALLY", "kYIELD", "kNEW", "kTHIS",
    "kSUPER", "kNULL", "kTRUE", "kFALSE", "kTYPE", "kABSTRACT", "kFINAL", "kIMPLICIT",
    "kLAZY", "kOVERRIDE", "kPRIVATE", "kPROTECTED", "kSEALED", "kGIVEN", "kUSING",
    "kENUM", "kEXPORT", "kTHEN", "kEND"
  )

  /** Classify leaf token types (keywords, literals, comments) from element type names */
  private def classifyLeafToken(elementType: String, text: String): Option[Int] =
    if scalaKeywordTexts.contains(elementType) || elementType.contains("KEYWORD") then
      Some(0) // keyword
    // "identifier" element type — match Scala 3 soft keywords by text (fallback for old plugin)
    else if (elementType == "identifier" || elementType == "tIDENTIFIER") &&
            scalaKeywordTexts.contains(text) then
      Some(0) // keyword
    else if elementType == "tINTEGER" || elementType == "tFLOAT" ||
            elementType.contains("integer") || elementType.contains("float") then
      Some(11) // number
    else if elementType.contains("COMMENT") || elementType.contains("comment") ||
            elementType == "DocComment" || elementType == "BlockComment" then
      Some(12) // comment
    else None

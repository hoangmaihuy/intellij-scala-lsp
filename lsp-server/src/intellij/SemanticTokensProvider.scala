package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiClass, PsiElement, PsiFile, PsiMethod, PsiNamedElement, PsiPolyVariantReference}
import org.eclipse.lsp4j.*
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScAnnotationsHolder, ScFieldId, ScReference, ScStableCodeReference}
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScParameter, ScTypeParam}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.*
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateBody

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
  def classifyElement(element: PsiElement): Option[Int] = element match
    case _: ScParameter => Some(8)       // parameter (covers ScClassParameter too)
    case _: ScEnum      => Some(4)       // enum (before ScClass — ScEnum extends ScClass)
    case _: ScClass     => Some(2)       // class
    case _: ScTrait     => Some(3)       // interface
    case _: ScObject    => Some(2)       // class (object)
    case _: ScTypeAlias => Some(1)       // type
    case _: ScTypeParam => Some(9)       // typeParameter
    case _: ScFunction | _: PsiMethod =>
      // Check if it's an operator method (symbolic name)
      val methodName = try element.asInstanceOf[PsiNamedElement].getName catch case _: Exception => null
      if methodName != null && isOperatorName(methodName) then Some(14) // operator
      else
        // Synthetic accessors (e.g. case class param getters) should classify as their original element
        val navElement = element.getNavigationElement
        if navElement != null && (navElement ne element) then navElement match
          case _: ScParameter      => Some(8) // parameter accessor
          case _: ScBindingPattern => Some(6) // property accessor
          case _: ScFieldId        => Some(6) // property accessor
          case _                   => Some(5) // method
        else Some(5) // method
    case bp: ScBindingPattern => classifyBinding(bp)
    case fi: ScFieldId        => classifyFieldId(fi)
    case _: PsiClass          => Some(2) // Java class fallback
    case _ =>
      System.err.println(s"[SemanticTokens] Unclassified resolved element: ${element.getClass.getName}")
      None

  /** Classify a binding pattern as property (class member) or variable (local). */
  private def classifyBinding(element: PsiElement): Option[Int] =
    element.getParent match
      case _: ScValue | _: ScPatternDefinition =>
        if element.getParent.getParent.isInstanceOf[ScTemplateBody] then Some(6) // property
        else Some(7) // variable (local val)
      case _: ScVariable =>
        if element.getParent.getParent.isInstanceOf[ScTemplateBody] then Some(6) // property
        else Some(7) // variable (local var)
      case _ => Some(7) // variable (pattern, generator, etc.)

  /** Classify a field identifier based on parent context. */
  private def classifyFieldId(element: PsiElement): Option[Int] =
    element.getParent match
      case _: ScValue | _: ScPatternDefinition | _: ScVariable =>
        if element.getParent.getParent.isInstanceOf[ScTemplateBody] then Some(6) // property
        else Some(7) // variable
      case _ => Some(7)

  /** Get modifier bits for a resolved element */
  def classifyModifiers(element: PsiElement): Int =
    var mods = element match
      case _: ScTrait  => 4 // abstract
      case _: ScObject => 2 // static
      case _           => 0
    // Check for deprecated annotation via ScAnnotationsHolder (Scala) or PsiModifierListOwner (Java)
    val isDeprecated = element match
      case holder: ScAnnotationsHolder =>
        try
          // First try qualified name lookup (works when stdlib is available)
          val byQName = holder.hasAnnotation("scala.deprecated") || holder.hasAnnotation("java.lang.Deprecated")
          if byQName then true
          else
            // Fallback: check annotation text for "deprecated" (works without stdlib)
            holder.annotations.exists: ann =>
              val typeText = ann.typeElement.getText
              typeText == "deprecated" || typeText.endsWith(".deprecated") || typeText == "Deprecated"
        catch case _: Exception => false
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
        SemanticTokens(java.util.Collections.emptyList())

  def getSemanticTokensRange(uri: String, range: Range): SemanticTokens =
    try
      computeTokens(uri, Some(range))
    catch
      case e: Exception =>
        System.err.println(s"[SemanticTokens] Error computing range tokens: ${e.getMessage}")
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
        case _: Exception => None

      resolved match
        case Some(res) =>
          classifyElement(res).foreach: tokenType =>
            val modifiers = classifyModifiers(res)
            val nameRange = getNameRange(element)
            tokens += ((nameRange._1, nameRange._2, tokenType, modifiers))
        case None =>
          val nameRange = getNameRange(element)
          System.err.println(s"[SemanticTokens] Unresolved reference: ${element.getText.take(50)} at offset ${nameRange._1}")

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

  private def isIdentifierLike(element: PsiElement): Boolean = element match
    case _: ScReference | _: ScStableCodeReference | _: ScReferenceExpression => true
    case _ => false

  private def isDeclaration(element: PsiElement): Boolean = element match
    case _: ScClass | _: ScTrait | _: ScObject | _: ScEnum => true
    case _: ScFunctionDefinition | _: ScFunctionDeclaration => true
    case _: ScTypeAlias | _: ScGiven                        => true
    case _: ScParameter | _: ScBindingPattern               => true
    case _                                                  => false

  private def classifyDeclaration(element: PsiElement): Option[(Int, Int)] = element match
    case _: ScParameter       => Some((8, 0)) // parameter
    case _: ScEnum            => Some((4, 0)) // enum (before ScClass)
    case _: ScClass           => Some((2, 0)) // class
    case _: ScTrait           => Some((3, 4)) // interface + abstract
    case _: ScObject          => Some((2, 2)) // class + static
    case f: ScFunction =>
      val name = try f.getName catch case _: Exception => null
      if name != null && isOperatorName(name) then Some((14, 0)) // operator
      else Some((5, 0)) // method
    case _: ScTypeAlias       => Some((1, 0)) // type
    case _: ScGiven           => Some((5, 0)) // method (given)
    case bp: ScBindingPattern => Some(classifyBindingDeclaration(bp))
    case _                    => None

  private def classifyBindingDeclaration(element: PsiElement): (Int, Int) =
    element.getParent match
      case _: ScValue | _: ScPatternDefinition =>
        if element.getParent.getParent.isInstanceOf[ScTemplateBody] then (6, 8) // property + readonly
        else (7, 8) // variable + readonly (local val)
      case _: ScVariable =>
        if element.getParent.getParent.isInstanceOf[ScTemplateBody] then (6, 0) // property
        else (7, 0) // variable (local var)
      case _ => (7, 0) // variable (pattern, generator, etc.)

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
    element match
      case ref: ScReference =>
        val nameId = ref.nameId
        if nameId != null then
          val r = nameId.getTextRange
          if r != null then return (r.getStartOffset, r.getLength)
      case _ => ()
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

  private val scala3SoftKeywords = Set(
    "using", "given", "extension", "derives", "end",
    "inline", "opaque", "open", "transparent", "infix"
  )

  /** Classify leaf token types (keywords, literals, comments) from element type names */
  private def classifyLeafToken(elementType: String, text: String): Option[Int] =
    if elementType.contains("KEYWORD") || elementType == "kDEF" || elementType == "kVAL" ||
       elementType == "kVAR" || elementType == "kCLASS" || elementType == "kTRAIT" ||
       elementType == "kOBJECT" || elementType == "kIMPORT" || elementType == "kPACKAGE" ||
       elementType == "kEXTENDS" || elementType == "kWITH" || elementType == "kIF" ||
       elementType == "kELSE" || elementType == "kMATCH" || elementType == "kCASE" ||
       elementType == "kFOR" || elementType == "kWHILE" || elementType == "kDO" ||
       elementType == "kRETURN" || elementType == "kTHROW" || elementType == "kTRY" ||
       elementType == "kCATCH" || elementType == "kFINALLY" || elementType == "kYIELD" ||
       elementType == "kNEW" || elementType == "kTHIS" || elementType == "kSUPER" ||
       elementType == "kNULL" || elementType == "kTRUE" || elementType == "kFALSE" ||
       elementType == "kTYPE" || elementType == "kABSTRACT" || elementType == "kFINAL" ||
       elementType == "kIMPLICIT" || elementType == "kLAZY" || elementType == "kOVERRIDE" ||
       elementType == "kPRIVATE" || elementType == "kPROTECTED" || elementType == "kSEALED" ||
       elementType == "kGIVEN" || elementType == "kUSING" || elementType == "kENUM" ||
       elementType == "kEXPORT" || elementType == "kTHEN" || elementType == "kEND" then
      Some(0) // keyword
    // Scala 3 soft keywords have element type "identifier" (tIDENTIFIER.toString) — match by text
    else if (elementType == "identifier" || elementType == "tIDENTIFIER") && scala3SoftKeywords.contains(text) then
      Some(0) // keyword
    else if elementType == "tINTEGER" || elementType == "tFLOAT" ||
            elementType.contains("integer") || elementType.contains("float") then
      Some(11) // number
    else if elementType.contains("COMMENT") || elementType.contains("comment") then
      Some(12) // comment
    else None

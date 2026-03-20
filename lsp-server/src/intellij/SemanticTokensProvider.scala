package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiFile, PsiNamedElement, PsiPolyVariantReference}
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
    "function"       // 13
  )

  val tokenModifiers: JList[String] = JList.of(
    "declaration",   // bit 0
    "static",        // bit 1
    "abstract",      // bit 2
    "readonly",      // bit 3
    "modification",  // bit 4
    "documentation", // bit 5
    "lazy"           // bit 6
  )

  val legend: SemanticTokensLegend = SemanticTokensLegend(tokenTypes, tokenModifiers)

  /** Classify a resolved PSI element into a semantic token type index.
    * Uses class name matching to avoid compile-time dependency on Scala plugin classes. */
  def classifyElement(element: PsiElement): Option[Int] =
    val cls = element.getClass.getName
    // ScClassParameter/ScParameter must be checked before ScClass (substring match)
    if cls.contains("ScParameter") then Some(8)                                  // parameter (covers ScClassParameter too)
    else if cls.contains("ScClass") || cls.contains("PsiClass") then Some(2)     // class
    else if cls.contains("ScTrait") then Some(3)                                 // interface
    else if cls.contains("ScObject") then Some(2)                                // class (object)
    else if cls.contains("ScEnum") then Some(4)                                  // enum
    else if cls.contains("ScTypeAlias") then Some(1)                             // type
    else if cls.contains("ScTypeParam") then Some(9)                             // typeParameter
    else if cls.contains("ScFunction") || cls.contains("PsiMethod") then Some(5) // method
    else if cls.contains("ScBindingPattern") then
      // Distinguish field vs local variable by checking parent context
      val ctx = element.getParent
      if ctx != null then
        val ctxCls = ctx.getClass.getName
        if ctxCls.contains("ScValue") || ctxCls.contains("ScPatternDefinition") then
          val grandParent = ctx.getParent
          if grandParent != null && grandParent.getClass.getName.contains("ScTemplateBody") then Some(6) // property
          else Some(7) // variable (local val)
        else if ctxCls.contains("ScVariable") then
          val grandParent = ctx.getParent
          if grandParent != null && grandParent.getClass.getName.contains("ScTemplateBody") then Some(6) // property
          else Some(7) // variable (local var)
        else Some(7) // variable (pattern, generator, etc.)
      else Some(7)
    else None

  /** Get modifier bits for a resolved element */
  def classifyModifiers(element: PsiElement): Int =
    val cls = element.getClass.getName
    if cls.contains("ScTrait") then 4          // abstract
    else if cls.contains("ScObject") then 2    // static
    else 0

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

    // Process leaf elements and references
    val cls = element.getClass.getName

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

      resolved.flatMap(classifyElement).foreach: tokenType =>
        val modifiers = resolved.map(classifyModifiers).getOrElse(0)
        val nameRange = getNameRange(element)
        tokens += ((nameRange._1, nameRange._2, tokenType, modifiers))

    // Check for keyword tokens (leaf elements with keyword token type)
    if element.getChildren.isEmpty then
      val elementType = element.getNode.getElementType.toString
      classifyLeafToken(elementType, element.getText).foreach: tokenType =>
        tokens += ((textRange.getStartOffset, textRange.getLength, tokenType, 0))

    // Check for declarations (definitions that introduce names)
    if isDeclaration(element) then
      classifyDeclaration(element).foreach: (tokenType, modifiers) =>
        getNameIdentifier(element).foreach: (offset, length) =>
          tokens += ((offset, length, tokenType, modifiers | 1)) // bit 0 = declaration

    // Recurse into children
    var child = element.getFirstChild
    while child != null do
      collectTokens(child, rangeStart, rangeEnd, tokens)
      child = child.getNextSibling

  private def isIdentifierLike(element: PsiElement): Boolean =
    val cls = element.getClass.getName
    cls.contains("ScReference") || cls.contains("ScStableCodeReference")

  private def isDeclaration(element: PsiElement): Boolean =
    val cls = element.getClass.getName
    cls.contains("ScClass") || cls.contains("ScTrait") || cls.contains("ScObject") ||
    cls.contains("ScEnum") || cls.contains("ScFunctionDefinition") || cls.contains("ScFunctionDeclaration") ||
    cls.contains("ScTypeAlias") || cls.contains("ScGiven") ||
    cls.contains("ScClassParameter") || cls.contains("ScParameter") ||
    cls.contains("ScBindingPattern")

  private def classifyDeclaration(element: PsiElement): Option[(Int, Int)] =
    val cls = element.getClass.getName
    // ScClassParameter must be checked before ScClass (substring match)
    if cls.contains("ScClassParameter") || cls.contains("ScParameter") then Some((8, 0)) // parameter
    else if cls.contains("ScClass") then Some((2, 0))       // class
    else if cls.contains("ScTrait") then Some((3, 4))   // interface + abstract
    else if cls.contains("ScObject") then Some((2, 2))  // class + static
    else if cls.contains("ScEnum") then Some((4, 0))    // enum
    else if cls.contains("ScFunction") then Some((5, 0)) // method
    else if cls.contains("ScTypeAlias") then Some((1, 0)) // type
    else if cls.contains("ScGiven") then Some((5, 0))   // method (given)
    else if cls.contains("ScBindingPattern") then
      // Classify based on parent context (same logic as classifyElement)
      val ctx = element.getParent
      if ctx != null then
        val ctxCls = ctx.getClass.getName
        if ctxCls.contains("ScValue") || ctxCls.contains("ScPatternDefinition") then
          val grandParent = ctx.getParent
          if grandParent != null && grandParent.getClass.getName.contains("ScTemplateBody") then Some((6, 8)) // property + readonly
          else Some((7, 8)) // variable + readonly (local val)
        else if ctxCls.contains("ScVariable") then
          val grandParent = ctx.getParent
          if grandParent != null && grandParent.getClass.getName.contains("ScTemplateBody") then Some((6, 0)) // property
          else Some((7, 0)) // variable (local var)
        else Some((7, 0)) // variable (pattern, generator, etc.)
      else Some((7, 0))
    else None

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
    try
      val nameId = element.getClass.getMethod("nameId").invoke(element)
      nameId match
        case e: PsiElement =>
          val r = e.getTextRange
          if r != null then (r.getStartOffset, r.getLength)
          else (element.getTextRange.getStartOffset, element.getTextRange.getLength)
        case _ => (element.getTextRange.getStartOffset, element.getTextRange.getLength)
    catch
      case _: Exception =>
        (element.getTextRange.getStartOffset, element.getTextRange.getLength)

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
    // Scala 3 soft keywords have element type tIDENTIFIER — match by text
    else if elementType == "tIDENTIFIER" && scala3SoftKeywords.contains(text) then
      Some(0) // keyword
    else if elementType.contains("string") || elementType.contains("STRING") ||
            elementType == "tSTRING" || elementType == "tMULTILINE_STRING" then
      Some(10) // string
    else if elementType == "tINTEGER" || elementType == "tFLOAT" ||
            elementType.contains("integer") || elementType.contains("float") then
      Some(11) // number
    else if elementType.contains("COMMENT") || elementType.contains("comment") then
      Some(12) // comment
    else None

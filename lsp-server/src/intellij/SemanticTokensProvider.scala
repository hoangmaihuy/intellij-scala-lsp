package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiClass, PsiElement, PsiFile, PsiMethod, PsiNamedElement, PsiPolyVariantReference}
import org.eclipse.lsp4j.*
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScFieldId, ScReference, ScStableCodeReference}
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScBindingPattern, ScReferencePattern}
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.*
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScClassParameter, ScParameter, ScTypeParam}
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
  def classifyModifiers(element: PsiElement): Int = element match
    case _: ScTrait  => 4 // abstract
    case _: ScObject => 2 // static
    case _           => 0

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
    case _: ScFunction        => Some((5, 0)) // method
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

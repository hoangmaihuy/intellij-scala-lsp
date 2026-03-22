package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiClass, PsiElement, PsiFile, PsiMethod, PsiNamedElement, PsiPolyVariantReference}
import org.eclipse.lsp4j.*

import java.util.{List as JList}
import scala.jdk.CollectionConverters.*

object SemanticTokensProvider:

  // Token types aligned with Metals for consistent Scala highlighting across LSP servers
  val tokenTypes: JList[String] = JList.of(
    "namespace",     // 0  — packages
    "type",          // 1  — type aliases
    "class",         // 2  — classes, objects
    "enum",          // 3  — enum declarations
    "interface",     // 4  — traits
    "struct",        // 5  — (unused, kept for Metals compat)
    "typeParameter", // 6  — type parameters
    "parameter",     // 7  — method/function parameters
    "variable",      // 8  — val, var, given instances
    "property",      // 9  — class member vals/vars
    "enumMember",    // 10 — enum cases
    "event",         // 11 — (unused, kept for Metals compat)
    "function",      // 12 — local defs
    "method",        // 13 — class methods
    "macro",         // 14 — (unused, kept for Metals compat)
    "keyword",       // 15 — keywords
    "modifier",      // 16 — (unused, kept for Metals compat)
    "comment",       // 17 — comments
    "string",        // 18 — string literals
    "number",        // 19 — numeric literals
    "regexp",        // 20 — escape sequences in strings
    "operator",      // 21 — symbolic operators
    "decorator"      // 22 — (unused, kept for Metals compat)
  )

  val tokenModifiers: JList[String] = JList.of(
    "declaration",   // bit 0
    "definition",    // bit 1
    "readonly",      // bit 2
    "static",        // bit 3
    "deprecated",    // bit 4
    "abstract",      // bit 5
    "async",         // bit 6 (unused)
    "modification",  // bit 7 (unused)
    "documentation", // bit 8 (unused)
    "defaultLibrary" // bit 9 (unused)
  )

  // Token type constants (matching indices above)
  private val TNamespace   = 0
  private val TType        = 1
  private val TClass       = 2
  private val TEnum        = 3
  private val TInterface   = 4
  private val TTypeParam   = 6
  private val TParameter   = 7
  private val TVariable    = 8
  private val TProperty    = 9
  private val TEnumMember  = 10
  private val TFunction    = 12
  private val TMethod      = 13
  private val TKeyword     = 15
  private val TComment     = 17
  private val TString      = 18
  private val TNumber      = 19
  private val TRegexp      = 20
  private val TOperator    = 21

  // Modifier bit constants (matching indices above)
  private val MDeclaration = 1       // bit 0
  private val MDefinition  = 1 << 1  // bit 1
  private val MReadonly    = 1 << 2  // bit 2
  private val MStatic      = 1 << 3  // bit 3
  private val MDeprecated  = 1 << 4  // bit 4
  private val MAbstract    = 1 << 5  // bit 5

  val legend: SemanticTokensLegend = SemanticTokensLegend(tokenTypes, tokenModifiers)

  /** Returns true if the name looks like a symbolic operator (not starting with a letter or underscore). */
  private def isOperatorName(name: String): Boolean =
    name.nonEmpty && !name.head.isLetter && name.head != '_'

  /** Classify a resolved PSI element into a semantic token type index (Metals-compatible).
    *
    * Handles all Scala plugin PSI types:
    *   ScParameter, ScClassParameter          → parameter
    *   ScEnumCase (ScEnumClassCase/Singleton) → enumMember
    *   ScEnum                                 → enum
    *   ScClass                                → class (or enumMember for enum cases)
    *   ScTrait                                → interface
    *   ScObject                               → class + static
    *   ScGivenDefinition                      → class (structural given is a type def)
    *   ScGivenAlias, ScGiven                  → variable (given alias is a value)
    *   ScTypeAlias                            → type
    *   ScTypeParam                            → typeParameter
    *   ScExtension                            → method
    *   ScMacroDefinition                      → method (Metals maps macros to method)
    *   ScFunction, ScFunctionDefinition/Decl  → method / function / operator
    *   PsiMethod (Java)                       → method
    *   ScNamedTupleComponent                  → property
    *   ScBindingPattern                       → variable / property
    *   ScFieldId                              → variable / property
    *   PsiField (Java)                        → property
    *   ScSyntheticClass                       → class
    *   ScSyntheticFunction                    → method
    *   PsiClass (Java)                        → class
    *   ScPackaging, PsiPackage                → namespace
    *   ScPrimaryConstructor                   → method
    */
  def classifyElement(element: PsiElement): Option[Int] =
    if ScalaTypes.isParameter(element) then Some(TParameter)
    else if ScalaTypes.isEnumCase(element) then Some(TEnumMember) // before isClass/isObject
    else if ScalaTypes.isEnum(element) then Some(TEnum)
    else if ScalaTypes.isGivenDefinition(element) then Some(TClass) // structural given is a type def
    else if ScalaTypes.isClass(element) then Some(TClass)
    else if ScalaTypes.isTrait(element) then Some(TInterface)
    else if ScalaTypes.isObject(element) then Some(TClass) // gets static modifier via classifyModifiers
    else if ScalaTypes.isTypeAlias(element) then Some(TType)
    else if ScalaTypes.isTypeParam(element) then Some(TTypeParam)
    else if ScalaTypes.isExtension(element) then Some(TMethod) // extension block → method
    else if ScalaTypes.isGiven(element) then Some(TVariable) // given alias is a value
    else if ScalaTypes.isFunction(element) || ScalaTypes.isMethod(element) then
      val methodName = try element.asInstanceOf[PsiNamedElement].getName catch case _: Exception => null
      if methodName != null && isOperatorName(methodName) then Some(TOperator)
      else
        // Synthetic accessors → classify as their original element
        val navElement = element.getNavigationElement
        if navElement != null && (navElement ne element) then
          if ScalaTypes.isParameter(navElement) then Some(TParameter)
          else if ScalaTypes.isBindingPattern(navElement) then Some(TProperty)
          else if ScalaTypes.isFieldId(navElement) then Some(TProperty)
          else Some(classifyMethodOrFunction(element))
        else Some(classifyMethodOrFunction(element))
    else if ScalaTypes.isNamedTupleComponent(element) then Some(TProperty)
    else if ScalaTypes.isBindingPattern(element) then classifyBinding(element)
    else if ScalaTypes.isFieldId(element) then classifyFieldId(element)
    else if ScalaTypes.isField(element) then Some(TProperty)
    else if ScalaTypes.isSyntheticClass(element) then Some(TClass)
    else if ScalaTypes.isSyntheticFunction(element) then Some(TMethod)
    else if ScalaTypes.isClassLike(element) then Some(TClass) // Java class fallback
    else if ScalaTypes.isPackaging(element) || ScalaTypes.isPackage(element) then Some(TNamespace)
    else if ScalaTypes.isPrimaryConstructor(element) then Some(TMethod)
    else
      System.err.println(s"[SemanticTokens] Unclassified resolved element: ${element.getClass.getName}")
      None

  /** Distinguish local functions from class methods. */
  private def classifyMethodOrFunction(element: PsiElement): Int =
    val parent = element.getParent
    if parent != null && ScalaTypes.isTemplateBody(parent) then TMethod
    else TFunction

  /** Classify a binding pattern as property (class member) or variable (local). */
  private def classifyBinding(element: PsiElement): Option[Int] =
    if ScalaTypes.isBindingPattern(element) then
      if ScalaTypes.isClassMember(element) then Some(TProperty)
      else Some(TVariable)
    else
      val parent = element.getParent
      if ScalaTypes.isValue(parent) || ScalaTypes.isPatternDefinition(parent) then
        if ScalaTypes.isTemplateBody(parent.getParent) then Some(TProperty) else Some(TVariable)
      else if ScalaTypes.isVariable(parent) then
        if ScalaTypes.isTemplateBody(parent.getParent) then Some(TProperty) else Some(TVariable)
      else Some(TVariable)

  /** Classify a field identifier based on parent context. */
  private def classifyFieldId(element: PsiElement): Option[Int] =
    val parent = element.getParent
    if ScalaTypes.isValue(parent) || ScalaTypes.isPatternDefinition(parent) || ScalaTypes.isVariable(parent) then
      if ScalaTypes.isTemplateBody(parent.getParent) then Some(TProperty) else Some(TVariable)
    else Some(TVariable)

  /** Get modifier bits for a resolved element (Metals-compatible bit positions). */
  def classifyModifiers(element: PsiElement): Int =
    var mods = 0
    if ScalaTypes.isTrait(element) then mods |= MAbstract
    if ScalaTypes.isObject(element) then mods |= MStatic
    // Check for deprecated annotation via ScAnnotationsHolder (Scala) or PsiModifierListOwner (Java)
    val isDeprecated =
      if ScalaTypes.isAnnotationsHolder(element) then
        try
          val byQName = ScalaTypes.hasAnnotation(element, "scala.deprecated") || ScalaTypes.hasAnnotation(element, "java.lang.Deprecated")
          if byQName then true
          else
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
    if isDeprecated then mods |= MDeprecated
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
          tokens += ((textRange.getStartOffset, textRange.getLength, TString, 0))
        else
          val subTokens = splitStringEscapes(textRange.getStartOffset, text)
          tokens ++= subTokens
      else
        classifyLeafToken(elementType, text) match
          case Some(tokenType) =>
            tokens += ((textRange.getStartOffset, textRange.getLength, tokenType, 0))
          case None =>
            if (elementType == "identifier" || elementType == "tIDENTIFIER") && isOperatorName(text) then
              tokens += ((textRange.getStartOffset, textRange.getLength, TOperator, 0))

    // Check for declarations (definitions that introduce names)
    if isDeclaration(element) then
      classifyDeclaration(element).foreach: (tokenType, baseModifiers) =>
        getNameIdentifier(element).foreach: (offset, length) =>
          val elementModifiers = classifyModifiers(element)
          tokens += ((offset, length, tokenType, baseModifiers | elementModifiers | MDefinition))

    // Recurse into children
    var child = element.getFirstChild
    while child != null do
      collectTokens(child, rangeStart, rangeEnd, tokens)
      child = child.getNextSibling

  private def isIdentifierLike(element: PsiElement): Boolean =
    ScalaTypes.isReference(element)

  private def isDeclaration(element: PsiElement): Boolean =
    ScalaTypes.isEnumCase(element) ||
    ScalaTypes.isClass(element) || ScalaTypes.isTrait(element) || ScalaTypes.isObject(element) || ScalaTypes.isEnum(element) ||
    ScalaTypes.isGivenDefinition(element) ||
    ScalaTypes.isFunctionDefinition(element) || ScalaTypes.isFunctionDeclaration(element) ||
    ScalaTypes.isExtension(element) ||
    ScalaTypes.isTypeAlias(element) || ScalaTypes.isGiven(element) ||
    ScalaTypes.isParameter(element) || ScalaTypes.isBindingPattern(element)

  private def classifyDeclaration(element: PsiElement): Option[(Int, Int)] =
    if ScalaTypes.isParameter(element) then Some((TParameter, MReadonly))
    else if ScalaTypes.isEnumCase(element) then Some((TEnumMember, 0)) // before isClass/isObject
    else if ScalaTypes.isEnum(element) then Some((TEnum, 0))
    else if ScalaTypes.isGivenDefinition(element) then Some((TClass, 0)) // structural given
    else if ScalaTypes.isClass(element) then Some((TClass, 0))
    else if ScalaTypes.isTrait(element) then Some((TInterface, MAbstract))
    else if ScalaTypes.isObject(element) then Some((TClass, MStatic))
    else if ScalaTypes.isExtension(element) then Some((TMethod, 0))
    else if ScalaTypes.isFunction(element) then
      val name = try element.asInstanceOf[PsiNamedElement].getName catch case _: Exception => null
      if name != null && isOperatorName(name) then Some((TOperator, 0))
      else Some((classifyMethodOrFunction(element), 0))
    else if ScalaTypes.isTypeAlias(element) then Some((TType, 0))
    else if ScalaTypes.isGiven(element) then Some((TVariable, MReadonly)) // given alias
    else if ScalaTypes.isBindingPattern(element) then Some(classifyBindingDeclaration(element))
    else None

  private def classifyBindingDeclaration(element: PsiElement): (Int, Int) =
    if ScalaTypes.isBindingPattern(element) then
      val isMember = ScalaTypes.isClassMember(element)
      if ScalaTypes.isVal(element) then
        if isMember then (TProperty, MReadonly)
        else (TVariable, MReadonly)
      else if ScalaTypes.isVar(element) then
        if isMember then (TProperty, 0)
        else (TVariable, 0)
      else (TVariable, 0) // pattern/generator
    else
      val parent = element.getParent
      if ScalaTypes.isValue(parent) || ScalaTypes.isPatternDefinition(parent) then
        if ScalaTypes.isTemplateBody(parent.getParent) then (TProperty, MReadonly) else (TVariable, MReadonly)
      else if ScalaTypes.isVariable(parent) then
        if ScalaTypes.isTemplateBody(parent.getParent) then (TProperty, 0) else (TVariable, 0)
      else (TVariable, 0)

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
        result += ((startOffset + segStart, end - segStart, TString, 0))

    while pos < text.length do
      if text(pos) == '\\' && pos + 1 < text.length then
        flushNonEscape(pos)
        val escLen = if text(pos + 1) == 'u' && pos + 5 < text.length then 6 // \uXXXX
                     else 2
        result += ((startOffset + pos, escLen, TRegexp, 0))
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
      Some(TKeyword)
    else if (elementType == "identifier" || elementType == "tIDENTIFIER") &&
            scalaKeywordTexts.contains(text) then
      Some(TKeyword)
    else if elementType == "tINTEGER" || elementType == "tFLOAT" ||
            elementType.contains("integer") || elementType.contains("float") then
      Some(TNumber)
    else if elementType.contains("COMMENT") || elementType.contains("comment") ||
            elementType == "DocComment" || elementType == "BlockComment" then
      Some(TComment)
    else None

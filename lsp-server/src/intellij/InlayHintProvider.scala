package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNameIdentifierOwner, PsiNamedElement}
import org.eclipse.lsp4j.{InlayHint, InlayHintKind, Position, Range}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}

import scala.jdk.CollectionConverters.*

// Implements textDocument/inlayHint by walking the PSI tree and extracting
// type information from Scala typed definitions that lack explicit type annotations.
// Also extracts parameter name hints at call sites, implicit argument hints,
// implicit conversion hints, and type parameter hints.
class InlayHintProvider(projectManager: IntellijProjectManager):

  def getInlayHints(uri: String, range: Range): Seq[InlayHint] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val startOffset = PsiUtils.positionToOffset(document, range.getStart)
        val endOffset = PsiUtils.positionToOffset(document, range.getEnd)
        val hints = Seq.newBuilder[InlayHint]
        visitElements(psiFile, startOffset, endOffset): elem =>
          collectTypeHint(elem, document).foreach(hints += _)
          collectParameterNameHints(elem, document).foreach(hints += _)
          collectImplicitArgumentHints(elem, document).foreach(hints += _)
          collectImplicitConversionHints(elem, document).foreach(hints += _)
          collectTypeParameterHints(elem, document).foreach(hints += _)
        hints.result()
      result.getOrElse(Seq.empty)

  /** Resolve an inlay hint — currently returns the hint as-is (infrastructure for future enrichment). */
  def resolveInlayHint(hint: InlayHint): InlayHint = hint

  // --- Type Hints ---

  private def collectTypeHint(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Option[InlayHint] =
    if !isInferredTypeDefinition(element) then return None
    ScalaTypes.getTypeText(element).map: typeText =>
      val nameElem = element match
        case owner: PsiNameIdentifierOwner => Option(owner.getNameIdentifier)
        case _ => None
      val offset = nameElem.map(_.getTextRange.getEndOffset)
        .getOrElse(element.getTextRange.getEndOffset)
      val pos = PsiUtils.offsetToPosition(document, offset)
      val hint = InlayHint()
      hint.setPosition(pos)
      hint.setLabel(LspEither.forLeft(s": $typeText"))
      hint.setKind(InlayHintKind.Type)
      hint.setPaddingLeft(false)
      hint

  private def isInferredTypeDefinition(element: PsiElement): Boolean =
    if ScalaTypes.isPatternDefinition(element) || ScalaTypes.isVariableDefinition(element) || ScalaTypes.isFunctionDefinition(element) then
      !hasExplicitTypeAnnotation(element)
    else false

  private def hasExplicitTypeAnnotation(element: PsiElement): Boolean =
    element.getChildren.exists: child =>
      ScalaTypes.isSimpleTypeElement(child) || ScalaTypes.isParameterizedTypeElement(child) ||
        ScalaTypes.isFunctionalTypeElement(child) || ScalaTypes.isTupleTypeElement(child) ||
        ScalaTypes.isInfixTypeElement(child) || ScalaTypes.isCompoundTypeElement(child)

  // --- Parameter Name Hints ---

  private def collectParameterNameHints(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Seq[InlayHint] =
    if !ScalaTypes.isArgumentExprList(element) then return Seq.empty
    try
      val call = element.getParent
      if call == null then return Seq.empty

      // Resolve the called method via standard PsiElement.getReference
      val paramNames = resolveParameterNames(call)
      paramNames match
        case Some(names) =>
          val args = element.getChildren.filter: child =>
            val cn = child.getClass.getName
            !cn.contains("PsiWhiteSpace") && !cn.contains("LeafPsiElement")
          names.zip(args).flatMap: (name, arg) =>
            if name.nonEmpty && !arg.getText.startsWith(s"$name =") then
              val offset = arg.getTextRange.getStartOffset
              val pos = PsiUtils.offsetToPosition(document, offset)
              val hint = InlayHint()
              hint.setPosition(pos)
              hint.setLabel(LspEither.forLeft(s"$name = "))
              hint.setKind(InlayHintKind.Parameter)
              hint.setPaddingRight(false)
              Some(hint)
            else None
        case None => Seq.empty
    catch
      case ex: Exception =>
        System.err.println(s"InlayHintProvider.collectParameterNameHints: $ex")
        Seq.empty

  private def resolveParameterNames(callElement: PsiElement): Option[Seq[String]] =
    // Find the invoked expression (first child that is a reference-like element)
    val invokedExpr = callElement.getChildren.find: child =>
      ScalaTypes.isReferenceExpression(child) || ScalaTypes.isMethodCall(child)
    invokedExpr.flatMap: ref =>
      val resolved = Option(ref.getReference).flatMap(r => Option(r.resolve()))
      resolved.flatMap(getParameterNames)

  private def getParameterNames(element: PsiElement): Option[Seq[String]] =
    if ScalaTypes.isFunction(element) || ScalaTypes.isPrimaryConstructor(element) then
      ScalaTypes.invokeMethod(element, "parameters") match
        case Some(params: scala.collection.Seq[?]) =>
          val names = params.collect { case p: PsiNamedElement => p.getName }.toSeq
          if names.nonEmpty then Some(names) else None
        case _ => None
    else None

  // --- Implicit Argument Hints ---

  private def collectImplicitArgumentHints(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Seq[InlayHint] =
    // Implicit argument hints: uses reflection to call findImplicitArguments on ImplicitArgumentsOwner.
    // The reflection chain is deep (findImplicitArguments -> Seq[ImplicitArgumentsOwner.Clause] -> args -> element),
    // so we wrap in try/catch for graceful degradation.
    try
      if !ScalaTypes.isImplicitArgumentsOwner(element) then return Seq.empty
      // Skip argument lists themselves — we only want the call expression
      if ScalaTypes.isArgumentExprList(element) then return Seq.empty

      ScalaTypes.invokeMethod(element, "findImplicitArguments") match
        case Some(clauses: scala.collection.Seq[?]) if clauses.nonEmpty =>
          val argNames = clauses.flatMap: clause =>
            // clause.args returns Seq[ImplicitArgument]
            try
              val argsMethod = clause.getClass.getMethod("args")
              val args = argsMethod.invoke(clause).asInstanceOf[scala.collection.Seq[?]]
              args.flatMap: arg =>
                try
                  val elemMethod = arg.getClass.getMethod("element")
                  val resolved = elemMethod.invoke(arg)
                  resolved match
                    case named: PsiNamedElement => Option(named.getName)
                    case _ => None
                catch case _: Exception => None
            catch case _: Exception => Seq.empty
          if argNames.isEmpty then return Seq.empty

          // Show implicit args after the expression
          val offset = element.getTextRange.getEndOffset
          val pos = PsiUtils.offsetToPosition(document, offset)
          val label = argNames.mkString("(", ", ", ")")
          val hint = InlayHint()
          hint.setPosition(pos)
          hint.setLabel(LspEither.forLeft(label))
          hint.setKind(InlayHintKind.Parameter)
          hint.setPaddingLeft(false)
          Seq(hint)
        case _ => Seq.empty
    catch
      case ex: Exception =>
        System.err.println(s"InlayHintProvider.collectImplicitArgumentHints: $ex")
        Seq.empty

  // --- Implicit Conversion Hints ---

  private def collectImplicitConversionHints(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Seq[InlayHint] =
    // Implicit conversion hints: calls ScExpression.implicitConversion() via reflection.
    // Returns Option[ScalaResolveResult], from which we extract element.getName.
    try
      if !ScalaTypes.isExpression(element) then return Seq.empty

      ScalaTypes.invokeOptionMethod(element, "implicitConversion") match
        case Some(result) =>
          // result is a ScalaResolveResult — get its .element (PsiNamedElement)
          val resolvedOpt =
            try
              val elemMethod = result.getClass.getMethod("element")
              Option(elemMethod.invoke(result))
            catch case _: Exception => None

          resolvedOpt match
            case Some(named: PsiNamedElement) =>
              val name = named.getName
              if name == null || name.isEmpty then return Seq.empty

              // "conversionName(" before the expression
              val startOffset = element.getTextRange.getStartOffset
              val startPos = PsiUtils.offsetToPosition(document, startOffset)
              val beforeHint = InlayHint()
              beforeHint.setPosition(startPos)
              beforeHint.setLabel(LspEither.forLeft(s"$name("))
              beforeHint.setKind(InlayHintKind.Parameter)
              beforeHint.setPaddingRight(false)

              // ")" after the expression
              val endOffset = element.getTextRange.getEndOffset
              val endPos = PsiUtils.offsetToPosition(document, endOffset)
              val afterHint = InlayHint()
              afterHint.setPosition(endPos)
              afterHint.setLabel(LspEither.forLeft(")"))
              afterHint.setKind(InlayHintKind.Parameter)
              afterHint.setPaddingLeft(false)

              Seq(beforeHint, afterHint)
            case _ => Seq.empty
        case None => Seq.empty
    catch
      case ex: Exception =>
        System.err.println(s"InlayHintProvider.collectImplicitConversionHints: $ex")
        Seq.empty

  // --- Type Parameter Hints ---

  private def collectTypeParameterHints(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Seq[InlayHint] =
    // Type parameter hints: calls ScReference.bind() -> ScalaResolveResult, then extracts
    // type parameters and uses TypeParameterType + substitutor to get inferred type args.
    // The reflection chain is very deep (bind -> resolveResult -> element -> typeParameters,
    // TypeParameterType companion -> apply, substitutor -> apply, presentableText), so we
    // wrap the entire block in try/catch for graceful degradation.
    try
      if !ScalaTypes.isReferenceExpression(element) then return Seq.empty

      // Only show type param hints if there are no explicit type arguments
      if hasExplicitTypeArguments(element) then return Seq.empty

      // ScReference.bind() returns Option[ScalaResolveResult]
      ScalaTypes.invokeOptionMethod(element, "bind") match
        case Some(resolveResult) =>
          // resolveResult.element
          val resolved =
            try resolveResult.getClass.getMethod("element").invoke(resolveResult)
            catch case _: Exception => return Seq.empty

          // Get type parameters from the resolved element
          val typeParams = resolved match
            case r: PsiElement if ScalaTypes.isFunction(r) =>
              ScalaTypes.invokeMethod(r, "typeParameters") match
                case Some(params: scala.collection.Seq[?]) => params
                case _ => Seq.empty
            case _ => Seq.empty

          if typeParams.isEmpty then return Seq.empty

          // Get the substitutor from the resolve result
          val substitutor =
            try resolveResult.getClass.getMethod("substitutor").invoke(resolveResult)
            catch case _: Exception => return Seq.empty

          val cl = element.getClass.getClassLoader

          // Load TypeParameterType companion for creating type parameter types
          val tptCompanion = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.api.TypeParameterType$")
          val tptModule = tptCompanion.getField("MODULE$").get(null)

          // Find the apply method on TypeParameterType companion
          val scTypeParamClass = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScTypeParam")
          val tptApply = tptCompanion.getMethod("apply", scTypeParamClass)

          // Get the substitutor's apply method — takes a ScType
          val scTypeClass = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.ScType")
          val substApply = substitutor.getClass.getMethod("apply", scTypeClass)

          val typeTexts = typeParams.flatMap: tp =>
            try
              val tpType = tptApply.invoke(tptModule, tp)
              val substituted = substApply.invoke(substitutor, tpType)
              ScalaTypes.getScTypePresentableText(substituted, element) match
                case Some(text) if !text.contains("Nothing") && !text.contains("Any") &&
                  !text.contains("?") && text != tp.asInstanceOf[PsiNamedElement].getName =>
                  Some(text)
                case _ => None
            catch
              case ex: Exception =>
                System.err.println(s"InlayHintProvider.collectTypeParameterHints: type param reflection failed: $ex")
                None

          if typeTexts.isEmpty then return Seq.empty

          val offset = element.getTextRange.getEndOffset
          val pos = PsiUtils.offsetToPosition(document, offset)
          val label = typeTexts.mkString("[", ", ", "]")
          val hint = InlayHint()
          hint.setPosition(pos)
          hint.setLabel(LspEither.forLeft(label))
          hint.setKind(InlayHintKind.Type)
          hint.setPaddingLeft(false)
          Seq(hint)
        case None => Seq.empty
    catch
      case ex: Exception =>
        System.err.println(s"InlayHintProvider.collectTypeParameterHints: $ex")
        Seq.empty

  /** Check if a reference expression already has explicit type arguments (e.g. foo[Int](...)) */
  private def hasExplicitTypeArguments(ref: PsiElement): Boolean =
    try
      // If the parent is a generic call with type args, skip
      val parent = ref.getParent
      if parent == null then return false
      val parentName = parent.getClass.getName
      parentName.contains("ScGenericCall")
    catch
      case _: Exception => false

  // --- PSI tree walking ---

  private def visitElements(psiFile: com.intellij.psi.PsiFile, startOffset: Int, endOffset: Int)(
    visitor: PsiElement => Unit
  ): Unit =
    def visit(element: PsiElement): Unit =
      val range = element.getTextRange
      if range != null && range.getStartOffset <= endOffset && range.getEndOffset >= startOffset then
        visitor(element)
        var child = element.getFirstChild
        while child != null do
          visit(child)
          child = child.getNextSibling
    visit(psiFile)

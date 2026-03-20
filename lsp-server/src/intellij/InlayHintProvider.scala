package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNameIdentifierOwner, PsiNamedElement}
import org.eclipse.lsp4j.{InlayHint, InlayHintKind, Position, Range}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}
import org.jetbrains.plugins.scala.lang.psi.api.base.ScPrimaryConstructor
import org.jetbrains.plugins.scala.lang.psi.api.base.types.*
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScArgumentExprList, ScMethodCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScFunctionDefinition, ScPatternDefinition, ScVariableDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScParameter, ScParameters}

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
    getTypeText(element).map: typeText =>
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

  private def isInferredTypeDefinition(element: PsiElement): Boolean = element match
    case _: ScPatternDefinition | _: ScVariableDefinition | _: ScFunctionDefinition =>
      !hasExplicitTypeAnnotation(element)
    case _ => false

  private def hasExplicitTypeAnnotation(element: PsiElement): Boolean =
    element.getChildren.exists:
      case _: ScSimpleTypeElement | _: ScParameterizedTypeElement | _: ScFunctionalTypeElement |
           _: ScTupleTypeElement | _: ScInfixTypeElement | _: ScCompoundTypeElement => true
      case _ => false

  private def getTypeText(element: PsiElement): Option[String] =
    // Use NavigationItem.getPresentation for type display text
    try
      element match
        case nav: com.intellij.navigation.NavigationItem =>
          Option(nav.getPresentation)
            .flatMap(p => Option(p.getLocationString))
            .filter(_.nonEmpty)
        case _ => None
    catch
      case _: Exception => None

  // --- Parameter Name Hints ---

  private def collectParameterNameHints(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Seq[InlayHint] =
    if !element.isInstanceOf[ScArgumentExprList] then return Seq.empty
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
      case _: Exception => Seq.empty

  private def resolveParameterNames(callElement: PsiElement): Option[Seq[String]] =
    // Find the invoked expression (first child that is a reference-like element)
    val invokedExpr = callElement.getChildren.find:
      case _: ScReferenceExpression | _: ScMethodCall => true
      case _ => false
    invokedExpr.flatMap: ref =>
      val resolved = Option(ref.getReference).flatMap(r => Option(r.resolve()))
      resolved.flatMap(getParameterNames)

  private def getParameterNames(element: PsiElement): Option[Seq[String]] = element match
    case fn: ScFunction =>
      val params = fn.parameters
      if params.nonEmpty then Some(params.map(_.name).toSeq)
      else None
    case ctor: ScPrimaryConstructor =>
      val params = ctor.parameters
      if params.nonEmpty then Some(params.map(_.name).toSeq)
      else None
    case _ => None

  // --- Implicit Argument Hints ---

  private def collectImplicitArgumentHints(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Seq[InlayHint] =
    try
      import org.jetbrains.plugins.scala.lang.psi.api.ImplicitArgumentsOwner
      element match
        case owner: ImplicitArgumentsOwner =>
          // Skip argument lists themselves — we only want the call expression
          if element.isInstanceOf[ScArgumentExprList] then return Seq.empty
          val clauses = owner.findImplicitArguments
          if clauses.isEmpty then return Seq.empty

          val argNames = clauses.flatMap: clause =>
            clause.args.flatMap: arg =>
              Option(arg.element).map(_.getName)
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
      case _: Exception => Seq.empty

  // --- Implicit Conversion Hints ---

  private def collectImplicitConversionHints(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Seq[InlayHint] =
    try
      import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
      element match
        case expr: ScExpression =>
          expr.implicitConversion() match
            case Some(result) =>
              val name = result.element.getName
              if name == null || name.isEmpty then return Seq.empty

              // "conversionName(" before the expression
              val startOffset = expr.getTextRange.getStartOffset
              val startPos = PsiUtils.offsetToPosition(document, startOffset)
              val beforeHint = InlayHint()
              beforeHint.setPosition(startPos)
              beforeHint.setLabel(LspEither.forLeft(s"$name("))
              beforeHint.setKind(InlayHintKind.Parameter)
              beforeHint.setPaddingRight(false)

              // ")" after the expression
              val endOffset = expr.getTextRange.getEndOffset
              val endPos = PsiUtils.offsetToPosition(document, endOffset)
              val afterHint = InlayHint()
              afterHint.setPosition(endPos)
              afterHint.setLabel(LspEither.forLeft(")"))
              afterHint.setKind(InlayHintKind.Parameter)
              afterHint.setPaddingLeft(false)

              Seq(beforeHint, afterHint)
            case None => Seq.empty
        case _ => Seq.empty
    catch
      case _: Exception => Seq.empty

  // --- Type Parameter Hints ---

  private def collectTypeParameterHints(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Seq[InlayHint] =
    try
      element match
        case ref: ScReferenceExpression =>
          // Only show type param hints if there are no explicit type arguments
          if hasExplicitTypeArguments(ref) then return Seq.empty

          import org.jetbrains.plugins.scala.lang.psi.api.base.ScReference
          val refAsScRef = ref.asInstanceOf[ScReference]
          refAsScRef.bind() match
            case Some(resolveResult) =>
              val resolved = resolveResult.element
              val typeParams = resolved match
                case fn: ScFunction => fn.typeParameters
                case _ => Seq.empty

              if typeParams.isEmpty then return Seq.empty

              val substitutor = resolveResult.substitutor
              val typeTexts = typeParams.flatMap: tp =>
                try
                  import org.jetbrains.plugins.scala.lang.psi.types.api.TypeParameterType
                  val tpType = TypeParameterType(tp)
                  val substituted = substitutor(tpType)
                  // Don't show if the type parameter was not substituted (still abstract/undefined)
                  import org.jetbrains.plugins.scala.lang.psi.types.{TypePresentationContext, Context}
                  implicit val tpc: TypePresentationContext = TypePresentationContext(element)
                  implicit val ctx: Context = Context(element)
                  val text = substituted.presentableText
                  if text.contains("Nothing") || text.contains("Any") ||
                     text.contains("?") || text == tp.name then None
                  else Some(text)
                catch
                  case _: Exception => None

              if typeTexts.isEmpty then return Seq.empty

              val offset = ref.getTextRange.getEndOffset
              val pos = PsiUtils.offsetToPosition(document, offset)
              val label = typeTexts.mkString("[", ", ", "]")
              val hint = InlayHint()
              hint.setPosition(pos)
              hint.setLabel(LspEither.forLeft(label))
              hint.setKind(InlayHintKind.Type)
              hint.setPaddingLeft(false)
              Seq(hint)
            case None => Seq.empty
        case _ => Seq.empty
    catch
      case _: Exception => Seq.empty

  /** Check if a reference expression already has explicit type arguments (e.g. foo[Int](...)) */
  private def hasExplicitTypeArguments(ref: ScReferenceExpression): Boolean =
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

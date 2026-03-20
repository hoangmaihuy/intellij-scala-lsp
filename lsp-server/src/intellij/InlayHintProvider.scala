package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNameIdentifierOwner, PsiNamedElement}
import org.eclipse.lsp4j.{InlayHint, InlayHintKind, Position, Range}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => LspEither}

import scala.jdk.CollectionConverters.*

// Implements textDocument/inlayHint by walking the PSI tree and extracting
// type information from Scala typed definitions that lack explicit type annotations.
// Also extracts parameter name hints at call sites.
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
        hints.result()
      result.getOrElse(Seq.empty)

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

  private def isInferredTypeDefinition(element: PsiElement): Boolean =
    val cls = element.getClass.getName
    (cls.contains("ScPatternDefinition") || cls.contains("ScVariableDefinition") ||
      cls.contains("ScFunctionDefinition")) && !hasExplicitTypeAnnotation(element)

  private def hasExplicitTypeAnnotation(element: PsiElement): Boolean =
    // If the element has a child whose class name contains "TypeElement",
    // it has an explicit type annotation
    element.getChildren.exists: child =>
      val cn = child.getClass.getName
      cn.contains("ScSimpleTypeElement") || cn.contains("ScParameterizedTypeElement") ||
        cn.contains("ScFunctionalTypeElement") || cn.contains("ScTupleTypeElement") ||
        cn.contains("ScInfixTypeElement") || cn.contains("ScCompoundTypeElement")

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
    val className = element.getClass.getName
    if !className.contains("ScArgumentExprList") then return Seq.empty
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
    val invokedExpr = callElement.getChildren.find: child =>
      val cn = child.getClass.getName
      cn.contains("ScReferenceExpression") || cn.contains("ScMethodCall")
    invokedExpr.flatMap: ref =>
      val resolved = Option(ref.getReference).flatMap(r => Option(r.resolve()))
      resolved.flatMap(getParameterNames)

  private def getParameterNames(element: PsiElement): Option[Seq[String]] =
    val className = element.getClass.getName
    if className.contains("ScFunction") || className.contains("ScPrimaryConstructor") then
      // Find the parameter clause children and extract named parameters
      val paramClauses = element.getChildren.filter(_.getClass.getName.contains("ScParameter"))
      if paramClauses.nonEmpty then
        Some(paramClauses.collect { case named: PsiNamedElement => named.getName }.toSeq)
      else
        // Look for parameter clause wrapper, then find parameters inside
        val clauseWrapper = element.getChildren.find(_.getClass.getName.contains("ScParameters"))
        clauseWrapper.map: wrapper =>
          findDescendants(wrapper, _.getClass.getName.contains("ScParameter"))
            .collect { case named: PsiNamedElement => named.getName }
    else None

  private def findDescendants(root: PsiElement, predicate: PsiElement => Boolean): Seq[PsiElement] =
    val result = Seq.newBuilder[PsiElement]
    def visit(elem: PsiElement): Unit =
      if predicate(elem) then result += elem
      else
        var child = elem.getFirstChild
        while child != null do
          visit(child)
          child = child.getNextSibling
    visit(root)
    result.result()

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

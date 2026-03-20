package org.jetbrains.scalalsP.intellij

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{Hover, MarkupContent, MarkupKind, Position}

class HoverProvider(projectManager: IntellijProjectManager):

  def getHover(uri: String, position: Position): Option[Hover] =
    projectManager.smartReadAction: () =>
      for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        hover <- computeHover(psiFile, document, position)
      yield hover

  private def computeHover(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    position: Position
  ): Option[Hover] =
    val offset = PsiUtils.positionToOffset(document, position)
    val element = PsiUtils.findReferenceElementAt(psiFile, offset)

    element.flatMap: elem =>
      val resolvedElement = resolveElement(elem)
      val doc = getDocumentation(resolvedElement, elem)

      doc match
        case Some(html) if html.nonEmpty =>
          // Strip HTML tags for markdown
          val clean = html.replaceAll("<[^>]+>", "").trim
          if clean.nonEmpty then
            val markup = MarkupContent(MarkupKind.MARKDOWN, clean)
            val range = PsiUtils.elementToRange(document, elem)
            Some(Hover(markup, range))
          else None
        case _ =>
          // Fallback: use element presentation text
          getPresentation(resolvedElement).map: text =>
            val markup = MarkupContent(MarkupKind.MARKDOWN, s"```scala\n$text\n```")
            val range = PsiUtils.elementToRange(document, elem)
            Hover(markup, range)

  private def resolveElement(element: PsiElement): PsiElement =
    val ref = element.getReference
    if ref != null then
      Option(ref.resolve()).getOrElse(element)
    else
      element

  private def getDocumentation(element: PsiElement, originalElement: PsiElement): Option[String] =
    // Use IntelliJ's LanguageDocumentation to find the correct provider
    try
      val lang = element.getLanguage
      val provider = LanguageDocumentation.INSTANCE.forLanguage(lang)
      if provider != null then
        Option(provider.generateDoc(element, originalElement)).filter(_.nonEmpty)
      else
        // Try composite provider as fallback
        val composite = com.intellij.lang.documentation.CompositeDocumentationProvider.wrapProviders(
          DocumentationProvider.EP_NAME.getExtensionList)
        Option(composite.generateDoc(element, originalElement)).filter(_.nonEmpty)
    catch
      case _: Exception => None

  private def getPresentation(element: PsiElement): Option[String] =
    element match
      case nav: com.intellij.navigation.NavigationItem =>
        Option(nav.getPresentation).map: pres =>
          val name = Option(pres.getPresentableText).getOrElse("")
          val loc = Option(pres.getLocationString).filter(_.nonEmpty).map(l => s" $l").getOrElse("")
          s"$name$loc"
      case _ => None

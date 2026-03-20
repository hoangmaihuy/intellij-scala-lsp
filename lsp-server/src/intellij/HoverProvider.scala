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
          val markdown = HoverProvider.htmlToMarkdown(html)
          if markdown.nonEmpty then
            val markup = MarkupContent(MarkupKind.MARKDOWN, markdown)
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

object HoverProvider:
  /** Convert IntelliJ's documentation HTML to clean markdown.
    * IntelliJ's generateDoc returns full HTML with style blocks, head, etc. */
  def htmlToMarkdown(html: String): String =
    var s = html
    // Remove entire <style>...</style> blocks (including content)
    s = s.replaceAll("(?si)<style[^>]*>.*?</style>", "")
    // Remove entire <head>...</head> blocks
    s = s.replaceAll("(?si)<head[^>]*>.*?</head>", "")
    // Remove <script> blocks
    s = s.replaceAll("(?si)<script[^>]*>.*?</script>", "")

    // Convert <pre><code> blocks to markdown code blocks
    s = s.replaceAll("(?si)<pre[^>]*><code[^>]*>(.*?)</code></pre>", "\n```scala\n$1\n```\n")
    s = s.replaceAll("(?si)<pre[^>]*>(.*?)</pre>", "\n```\n$1\n```\n")
    // Convert inline <code> to backticks
    s = s.replaceAll("(?si)<code[^>]*>(.*?)</code>", "`$1`")

    // Convert <b>/<strong> to bold
    s = s.replaceAll("(?si)<(?:b|strong)>(.*?)</(?:b|strong)>", "**$1**")
    // Convert <i>/<em> to italic
    s = s.replaceAll("(?si)<(?:i|em)>(.*?)</(?:i|em)>", "*$1*")

    // Convert <p> to double newline
    s = s.replaceAll("(?si)<p[^>]*>", "\n\n")
    s = s.replaceAll("(?si)</p>", "")
    // Convert <br> to newline
    s = s.replaceAll("(?si)<br\\s*/?>", "\n")
    // Convert <hr> to horizontal rule
    s = s.replaceAll("(?si)<hr\\s*/?>", "\n---\n")

    // Convert list items
    s = s.replaceAll("(?si)<li[^>]*>", "\n- ")
    s = s.replaceAll("(?si)</li>", "")
    s = s.replaceAll("(?si)</?[ou]l[^>]*>", "")

    // Convert definition terms (IntelliJ uses <dt>/<dd> for param docs)
    s = s.replaceAll("(?si)<dt>(.*?)</dt>", "\n**$1**")
    s = s.replaceAll("(?si)<dd>(.*?)</dd>", " — $1")

    // Strip all remaining HTML tags
    s = s.replaceAll("<[^>]+>", "")

    // Decode HTML entities
    s = s.replace("&lt;", "<")
    s = s.replace("&gt;", ">")
    s = s.replace("&amp;", "&")
    s = s.replace("&quot;", "\"")
    s = s.replace("&apos;", "'")
    s = s.replace("&#39;", "'")
    s = s.replace("&nbsp;", " ")

    // Clean up whitespace: collapse multiple blank lines, trim
    s = s.replaceAll("\n{3,}", "\n\n")
    s = s.trim
    s

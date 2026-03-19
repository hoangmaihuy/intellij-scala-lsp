package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{Hover, MarkupContent, MarkupKind, Position}

/**
 * Implements textDocument/hover using IntelliJ's type inference and documentation.
 */
class HoverProvider(projectManager: IntellijProjectManager):

  def getHover(uri: String, position: Position): Option[Hover] =
    ReadAction.compute[Option[Hover], RuntimeException]: () =>
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
      val typeInfo = getTypeInfo(resolvedElement)
      val docInfo = getDocumentation(resolvedElement, elem)

      val content = buildHoverContent(typeInfo, docInfo)
      if content.nonEmpty then
        val markup = MarkupContent(MarkupKind.MARKDOWN, content)
        val range = PsiUtils.elementToRange(document, elem)
        Some(Hover(markup, range))
      else
        None

  private def resolveElement(element: PsiElement): PsiElement =
    val ref = element.getReference
    if ref != null then
      Option(ref.resolve()).getOrElse(element)
    else
      element

  private def getTypeInfo(element: PsiElement): Option[String] =
    try
      // Try Scala-specific type retrieval via reflection to avoid compile-time dependency issues
      val clazz = element.getClass
      val getTypeMethod = try
        Some(clazz.getMethod("getType"))
      catch
        case _: NoSuchMethodException =>
          try Some(clazz.getMethod("type"))
          catch case _: NoSuchMethodException => None

      getTypeMethod.flatMap: method =>
        val typeResult = method.invoke(element)
        if typeResult != null then
          Some(typeResult.toString)
        else
          None
    catch
      case _: Exception => None

  private def getDocumentation(element: PsiElement, originalElement: PsiElement): Option[String] =
    try
      val providerClass = Class.forName(
        "org.jetbrains.plugins.scala.editor.documentationProvider.ScalaDocumentationProvider"
      )
      val provider = providerClass.getDeclaredConstructor().newInstance()
      val generateDocMethod = providerClass.getMethod(
        "generateDoc",
        classOf[PsiElement],
        classOf[PsiElement]
      )
      val doc = generateDocMethod.invoke(provider, element, originalElement)
      Option(doc).map(_.toString).filter(_.nonEmpty)
    catch
      case _: Exception => None

  private def buildHoverContent(typeInfo: Option[String], docInfo: Option[String]): String =
    val parts = Seq.newBuilder[String]

    typeInfo.foreach: t =>
      parts += s"```scala\n$t\n```"

    docInfo.foreach: d =>
      // Strip HTML tags for markdown display
      val clean = d.replaceAll("<[^>]+>", "").trim
      if clean.nonEmpty then
        parts += clean

    parts.result().mkString("\n\n---\n\n")

package org.jetbrains.scalalsP.intellij

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{Hover, MarkupContent, MarkupKind, Position}

// Implements textDocument/hover using IntelliJ's type inference and documentation.
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
      val docInfo = getDocumentation(psiFile, resolvedElement, elem)

      val content = buildHoverContent(typeInfo, docInfo)
      if content.nonEmpty then
        val markup = new MarkupContent(MarkupKind.MARKDOWN, content)
        val range = PsiUtils.elementToRange(document, elem)
        Some(new Hover(markup, range))
      else
        None

  private def resolveElement(element: PsiElement): PsiElement =
    val ref = element.getReference
    if ref != null then
      Option(ref.resolve()).getOrElse(element)
    else
      element

  private def getTypeInfo(element: PsiElement): Option[String] =
    // Strategy 1: Try Scala-specific getType() via reflection
    getTypeViaReflection(element)
      // Strategy 2: Fall back to presentation text (works for all languages)
      .orElse(getTypeViaPresentation(element))

  private def getTypeViaReflection(element: PsiElement): Option[String] =
    try
      val clazz = element.getClass

      // Scala plugin: ScTypedDefinition.`type`() returns TypeResult
      // ScExpression.`type`() returns TypeResult
      // TypeResult.get returns ScType, ScType.presentableText gives readable form
      val getTypeMethod = try
        Some(clazz.getMethod("getType"))
      catch
        case _: NoSuchMethodException =>
          try Some(clazz.getMethod("type"))
          catch case _: NoSuchMethodException => None

      getTypeMethod.flatMap: method =>
        val typeResult = method.invoke(element)
        if typeResult == null then None
        else
          // Try to extract a presentable type string
          extractPresentableType(typeResult)
            .orElse(Some(typeResult.toString))
    catch
      case _: Exception => None

  private def extractPresentableType(typeObj: Any): Option[String] =
    try
      val cls = typeObj.getClass

      // TypeResult wraps ScType — try .get first
      val scType = try
        val getMethod = cls.getMethod("get")
        getMethod.invoke(typeObj)
      catch
        case _: NoSuchMethodException => typeObj

      if scType == null then return None

      // ScType.presentableText(TypePresentationContext) — preferred
      val scTypeCls = scType.getClass
      try
        val presentableTextMethod = scTypeCls.getMethod("presentableText",
          Class.forName("org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext"))
        // Get a default TypePresentationContext
        val ctxClass = Class.forName("org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext$")
        val ctxModule = ctxClass.getField("MODULE$").get(null)
        val emptyCtx = ctxClass.getMethod("emptyContext").invoke(ctxModule)
        val result = presentableTextMethod.invoke(scType, emptyCtx)
        Option(result).map(_.toString).filter(_.nonEmpty)
      catch
        case _: Exception =>
          // Fallback: just toString the ScType
          val str = scType.toString
          if str.nonEmpty && !str.startsWith("class ") then Some(str)
          else None
    catch
      case _: Exception => None

  private def getTypeViaPresentation(element: PsiElement): Option[String] =
    try
      element match
        case nav: com.intellij.navigation.NavigationItem =>
          Option(nav.getPresentation).flatMap: pres =>
            Option(pres.getLocationString).filter(_.nonEmpty)
        case _ => None
    catch
      case _: Exception => None

  // Use IntelliJ's LanguageDocumentation to find the correct DocumentationProvider
  // for the element's language, then call generateDoc.
  private def getDocumentation(psiFile: com.intellij.psi.PsiFile, element: PsiElement, originalElement: PsiElement): Option[String] =
    // Strategy 1: Use LanguageDocumentation (the standard IntelliJ way)
    getDocViaLanguageDocumentation(element, originalElement)
      // Strategy 2: Try Scala-specific provider via reflection
      .orElse(getDocViaScalaProvider(element, originalElement))
      // Strategy 3: Try all registered providers via extension point
      .orElse(getDocViaExtensionPoint(element, originalElement))

  private def getDocViaLanguageDocumentation(element: PsiElement, originalElement: PsiElement): Option[String] =
    try
      val lang = element.getLanguage
      val provider = LanguageDocumentation.INSTANCE.forLanguage(lang)
      if provider != null then
        Option(provider.generateDoc(element, originalElement)).filter(_.nonEmpty)
      else None
    catch
      case _: Exception => None

  private def getDocViaScalaProvider(element: PsiElement, originalElement: PsiElement): Option[String] =
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

  private def getDocViaExtensionPoint(element: PsiElement, originalElement: PsiElement): Option[String] =
    try
      val providers = DocumentationProvider.EP_NAME.getExtensionList
      val it = providers.iterator()
      while it.hasNext do
        val provider = it.next()
        try
          val doc = provider.generateDoc(element, originalElement)
          if doc != null && doc.nonEmpty then return Some(doc)
        catch
          case _: Exception => ()
      None
    catch
      case _: Exception => None

  private[intellij] def buildHoverContent(typeInfo: Option[String], docInfo: Option[String]): String =
    val parts = Seq.newBuilder[String]

    typeInfo.foreach: t =>
      parts += s"```scala\n$t\n```"

    docInfo.foreach: d =>
      // Strip HTML tags for markdown display
      val clean = d.replaceAll("<[^>]+>", "").trim
      if clean.nonEmpty then
        parts += clean

    parts.result().mkString("\n\n---\n\n")

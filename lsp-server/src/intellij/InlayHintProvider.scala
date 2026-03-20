package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{InlayHint, InlayHintKind, Position, Range}

import scala.jdk.CollectionConverters.*

// Implements textDocument/inlayHint by walking the PSI tree and extracting
// type information from Scala typed definitions that lack explicit type annotations.
// Also extracts implicit parameter hints and parameter name hints.
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

        collectTypeHints(psiFile, document, startOffset, endOffset, hints)
        collectParameterHints(psiFile, document, startOffset, endOffset, hints)

        hints.result()

      result.getOrElse(Seq.empty)

  // --- Type Hints (inferred types on vals/vars/defs without explicit annotation) ---

  private def collectTypeHints(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    startOffset: Int,
    endOffset: Int,
    hints: scala.collection.mutable.Builder[InlayHint, Seq[InlayHint]]
  ): Unit =
    visitElements(psiFile, startOffset, endOffset): elem =>
      val className = elem.getClass.getName
      if className.contains("ScPatternDefinition") || className.contains("ScVariableDefinition") ||
         className.contains("ScFunctionDefinition") then
        extractTypeHint(elem, document).foreach(hints += _)
      true

  private def extractTypeHint(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Option[InlayHint] =
    try
      // Check if there's already an explicit type annotation
      val hasExplicitType = try
        val method = element.getClass.getMethod("hasExplicitType")
        method.invoke(element).asInstanceOf[Boolean]
      catch
        case _: Exception => false

      if hasExplicitType then return None

      // Get the inferred type
      val typeStr = getTypeString(element)
      typeStr.flatMap: t =>
        // Place hint after the name identifier
        val nameElement = try
          val method = element.getClass.getMethod("nameId")
          Option(method.invoke(element)).map(_.asInstanceOf[PsiElement])
        catch
          case _: Exception => None

        nameElement.map: nameElem =>
          val offset = nameElem.getTextRange.getEndOffset
          val pos = PsiUtils.offsetToPosition(document, offset)
          val hint = InlayHint()
          hint.setPosition(pos)
          hint.setLabel(s": $t")
          hint.setKind(InlayHintKind.Type)
          hint.setPaddingLeft(false)
          hint
    catch
      case _: Exception => None

  // --- Parameter Name Hints (at call sites) ---

  private def collectParameterHints(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    startOffset: Int,
    endOffset: Int,
    hints: scala.collection.mutable.Builder[InlayHint, Seq[InlayHint]]
  ): Unit =
    visitElements(psiFile, startOffset, endOffset): elem =>
      val className = elem.getClass.getName
      // ScArgumentExprList contains the arguments of a method call
      if className.contains("ScArgumentExprList") then
        extractArgumentHints(elem, document).foreach(hints += _)
      true

  private def extractArgumentHints(
    argList: PsiElement,
    document: com.intellij.openapi.editor.Document
  ): Seq[InlayHint] =
    try
      // Get the parent call expression to resolve the method
      val call = argList.getParent
      if call == null then return Seq.empty

      // Try to resolve the called method to get parameter names
      val methodRef = try
        val method = call.getClass.getMethod("getInvokedExpr")
        Option(method.invoke(call)).map(_.asInstanceOf[PsiElement])
      catch
        case _: Exception => None

      val paramNames = methodRef.flatMap: ref =>
        val resolved = Option(ref.getReference).flatMap(r => Option(r.resolve()))
        resolved.flatMap(getParameterNames)

      paramNames match
        case Some(names) =>
          val args = argList.getChildren.filter: child =>
            val cn = child.getClass.getName
            !cn.contains("PsiWhiteSpace") && !cn.contains("LeafPsiElement")

          names.zip(args).flatMap: (name, arg) =>
            if name.nonEmpty && !arg.getText.startsWith(s"$name =") then
              val offset = arg.getTextRange.getStartOffset
              val pos = PsiUtils.offsetToPosition(document, offset)
              val hint = InlayHint()
              hint.setPosition(pos)
              hint.setLabel(s"$name = ")
              hint.setKind(InlayHintKind.Parameter)
              hint.setPaddingRight(false)
              Some(hint)
            else None
        case None => Seq.empty
    catch
      case _: Exception => Seq.empty

  private def getParameterNames(element: PsiElement): Option[Seq[String]] =
    try
      val className = element.getClass.getName
      if className.contains("ScFunction") || className.contains("PsiMethod") then
        // Try ScFunction.parameters
        try
          val paramsMethod = element.getClass.getMethod("parameters")
          val params = paramsMethod.invoke(element)
          params match
            case seq: scala.collection.immutable.Seq[?] =>
              val names = seq.map: p =>
                try
                  val nameMethod = p.getClass.getMethod("name")
                  nameMethod.invoke(p).toString
                catch
                  case _: Exception => ""
              Some(names.map(_.toString))
            case _ => None
        catch
          case _: Exception =>
            // Fallback: try PsiMethod.getParameterList
            try
              val paramList = element.getClass.getMethod("getParameterList").invoke(element)
              val params = paramList.getClass.getMethod("getParameters").invoke(paramList)
                .asInstanceOf[Array[?]]
              Some(params.map: p =>
                try p.getClass.getMethod("getName").invoke(p).toString
                catch case _: Exception => ""
              .toSeq)
            catch
              case _: Exception => None
      else None
    catch
      case _: Exception => None

  // --- Type extraction (same approach as HoverProvider) ---

  private def getTypeString(element: PsiElement): Option[String] =
    try
      val clazz = element.getClass
      val getTypeMethod = try Some(clazz.getMethod("getType"))
      catch case _: NoSuchMethodException =>
        try Some(clazz.getMethod("type"))
        catch case _: NoSuchMethodException => None

      getTypeMethod.flatMap: method =>
        val typeResult = method.invoke(element)
        if typeResult == null then None
        else extractPresentableType(typeResult)
    catch
      case _: Exception => None

  private def extractPresentableType(typeObj: Any): Option[String] =
    try
      val cls = typeObj.getClass
      val scType = try
        val getMethod = cls.getMethod("get")
        getMethod.invoke(typeObj)
      catch
        case _: NoSuchMethodException => typeObj

      if scType == null then return None

      val scTypeCls = scType.getClass
      try
        val presentableTextMethod = scTypeCls.getMethod("presentableText",
          Class.forName("org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext"))
        val ctxClass = Class.forName("org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext$")
        val ctxModule = ctxClass.getField("MODULE$").get(null)
        val emptyCtx = ctxClass.getMethod("emptyContext").invoke(ctxModule)
        val result = presentableTextMethod.invoke(scType, emptyCtx)
        Option(result).map(_.toString).filter(_.nonEmpty)
      catch
        case _: Exception =>
          val str = scType.toString
          if str.nonEmpty && !str.startsWith("class ") then Some(str)
          else None
    catch
      case _: Exception => None

  // --- PSI tree walking ---

  private def visitElements(psiFile: com.intellij.psi.PsiFile, startOffset: Int, endOffset: Int)(
    visitor: PsiElement => Boolean
  ): Unit =
    def visit(element: PsiElement): Unit =
      val range = element.getTextRange
      if range != null && range.getStartOffset <= endOffset && range.getEndOffset >= startOffset then
        visitor(element)
        for child <- element.getChildren do
          visit(child)
    visit(psiFile)

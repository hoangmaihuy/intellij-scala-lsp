package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{Location, Position}

// Implements textDocument/typeDefinition.
// Given a position, resolves the element's type and navigates to the type's definition.
class TypeDefinitionProvider(projectManager: IntellijProjectManager):

  def getTypeDefinition(uri: String, position: Position): Seq[Location] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        resolveTypeAtOffset(psiFile, offset)

      result.getOrElse(Seq.empty)

  private def resolveTypeAtOffset(psiFile: com.intellij.psi.PsiFile, offset: Int): Seq[Location] =
    PsiUtils.findReferenceElementAt(psiFile, offset) match
      case Some(element) =>
        val resolved = resolveElement(element)
        getTypeElement(resolved)
          .flatMap(PsiUtils.elementToLocation)
          .toSeq
      case None =>
        Seq.empty

  private def resolveElement(element: PsiElement): PsiElement =
    val ref = element.getReference
    if ref != null then
      Option(ref.resolve()).getOrElse(element)
    else
      element

  private def getTypeElement(element: PsiElement): Option[PsiElement] =
    try
      // Try Scala-specific: ScTypedDefinition.getType -> ScType.extractClass
      val clazz = element.getClass
      // Try getType() which returns a ScType (or TypeResult wrapping it)
      val typeResultOpt = try
        val getTypeMethod = clazz.getMethod("getType")
        Some(getTypeMethod.invoke(element))
      catch
        case _: NoSuchMethodException =>
          // Try `type` method (Scala 3 accessor name)
          try Some(clazz.getMethod("type").invoke(element))
          catch case _: NoSuchMethodException => None

      typeResultOpt.flatMap: typeResult =>
        if typeResult == null then None
        else extractClassFromType(typeResult)
    catch
      case _: Exception => None

  private def extractClassFromType(typeObj: Any): Option[PsiElement] =
    try
      val typeClass = typeObj.getClass

      // TypeResult wraps ScType — try to get the inner type first
      val scType = try
        val getMethod = typeClass.getMethod("get")
        getMethod.invoke(typeObj)
      catch
        case _: NoSuchMethodException =>
          // Maybe it's already a ScType
          typeObj

      if scType == null then return None

      // ScType.extractClass returns Option[PsiClass]
      val scTypeClass = scType.getClass
      val extractClassMethod = try
        Some(scTypeClass.getMethod("extractClass"))
      catch
        case _: NoSuchMethodException => None

      extractClassMethod.flatMap: method =>
        val result = method.invoke(scType)
        // Result is scala.Option[PsiClass]
        result match
          case opt: Option[?] => opt.map(_.asInstanceOf[PsiElement])
          case _ => None
    catch
      case _: Exception => None

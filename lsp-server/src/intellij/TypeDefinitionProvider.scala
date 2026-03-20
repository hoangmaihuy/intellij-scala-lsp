package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{Location, Position}

import scala.jdk.CollectionConverters.*

// Implements textDocument/typeDefinition.
// Given a position, resolves the element's type and navigates to the type's declaration.
class TypeDefinitionProvider(projectManager: IntellijProjectManager):

  def getTypeDefinition(uri: String, position: Position): Seq[Location] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        findTypeDefinitionAtOffset(psiFile, offset)

      result.getOrElse(Seq.empty)

  private def findTypeDefinitionAtOffset(psiFile: com.intellij.psi.PsiFile, offset: Int): Seq[Location] =
    PsiUtils.findReferenceElementAt(psiFile, offset) match
      case Some(element) =>
        val resolved = resolveElement(element)
        getTypeDeclarations(resolved)
          .flatMap(PsiUtils.elementToLocation)
      case None =>
        Seq.empty

  private def resolveElement(element: PsiElement): PsiElement =
    val ref = element.getReference
    if ref != null then
      Option(ref.resolve()).getOrElse(element)
    else
      element

  private def getTypeDeclarations(element: PsiElement): Seq[PsiElement] =
    val providers = TypeDeclarationProvider.EP_NAME.getExtensionList.asScala
    for
      provider <- providers.toSeq
      results <- Option(provider.getSymbolTypeDeclarations(element)).toSeq
      target <- results
      if target != null
    yield target

package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiNamedElement
import org.eclipse.lsp4j.{Location, Position}

/**
 * Wraps DefinitionProvider and ReferencesProvider to provide Metals-like navigation:
 * when the cursor is on a definition, returns references instead of the definition itself.
 */
class DefinitionOrReferencesProvider(
  projectManager: IntellijProjectManager,
  definitionProvider: DefinitionProvider,
  referencesProvider: ReferencesProvider
):

  def getDefinitionOrReferences(uri: String, position: Position): Seq[Location] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        val element = PsiUtils.findReferenceElementAt(psiFile, offset)

        element match
          case Some(elem) if elem.getReference == null =>
            // Element is a definition — find references instead
            elem match
              case _: PsiNamedElement =>
                val refs = referencesProvider.findReferences(uri, position, includeDeclaration = false)
                if refs.nonEmpty then refs
                else PsiUtils.elementToLocation(elem).toSeq // fallback: return self
              case _ =>
                Seq.empty
          case _ =>
            // Element is a reference — delegate to normal definition resolution
            definitionProvider.getDefinition(uri, position)

      result.getOrElse(Seq.empty)

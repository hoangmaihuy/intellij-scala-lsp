package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNamedElement}
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

  /** Find the declaration PsiNamedElement at the cursor, or None if cursor is on a reference */
  private def findDeclarationAt(psiFile: com.intellij.psi.PsiFile, offset: Int): Option[PsiNamedElement] =
    PsiUtils.findReferenceElementAt(psiFile, offset).flatMap: elem =>
      if elem.getReference != null then None // it's a reference, not a declaration
      else elem match
        case named: PsiNamedElement => Some(named)
        case _ =>
          // Leaf element — check if it's the name identifier of a parent declaration
          val parent = elem.getParent
          if parent == null then None
          else parent match
            case named: PsiNamedElement =>
              try
                val nameId = named.getClass.getMethod("getNameIdentifier").invoke(named)
                nameId match
                  case e: PsiElement =>
                    val elemRange = elem.getTextRange
                    val nameRange = e.getTextRange
                    if elemRange != null && nameRange != null && elemRange == nameRange then Some(named)
                    else None
                  case _ => None
              catch
                case _: Exception => None
            case _ => None

  def getDefinitionOrReferences(uri: String, position: Position): Seq[Location] =
    // Check if cursor is on a definition
    val isOnDefinition: Option[Boolean] = projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        findDeclarationAt(psiFile, offset).isDefined
      result

    isOnDefinition match
      case Some(true) =>
        // Cursor is on a declaration — return references
        val refs = referencesProvider.findReferences(uri, position, includeDeclaration = false)
        if refs.nonEmpty then refs
        else
          // No references found — fall back to returning self-location
          projectManager.smartReadAction: () =>
            val result = for
              psiFile <- projectManager.findPsiFile(uri)
              vf <- projectManager.findVirtualFile(uri)
              document <- Option(FileDocumentManager.getInstance().getDocument(vf))
              offset = PsiUtils.positionToOffset(document, position)
              decl <- findDeclarationAt(psiFile, offset)
              loc <- PsiUtils.elementToLocation(decl)
            yield Seq(loc)
            result.getOrElse(Seq.empty)
      case _ =>
        // Cursor is on a reference or detection failed — normal definition flow
        definitionProvider.getDefinition(uri, position)

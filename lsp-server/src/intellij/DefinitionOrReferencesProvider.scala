package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNamedElement}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.eclipse.lsp4j.{Location, Position}

import scala.jdk.CollectionConverters.*

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
    // Check if cursor is on a definition and search for references in one read action
    val refsFromDefinition: Option[Seq[Location]] = projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        findDeclarationAt(psiFile, offset).map: declaration =>
          // Found a declaration — search for references directly
          // Use forEach instead of findAll to collect partial results if search crashes partway
          val project = projectManager.getProject
          val scope = GlobalSearchScope.projectScope(project)
          val refsBuffer = scala.collection.mutable.ArrayBuffer[Location]()
          try
            ReferencesSearch.search(declaration, scope, false).forEach: (ref: com.intellij.psi.PsiReference) =>
              PsiUtils.elementToLocation(ref.getElement).foreach(refsBuffer += _)
              true
          catch
            case e: Exception =>
              System.err.println(s"[DefOrRefs] ReferencesSearch partially failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
          if refsBuffer.nonEmpty then refsBuffer.toSeq
          else PsiUtils.elementToLocation(declaration).toSeq // fallback: return self
      result.flatten // Option[Option[Seq]] -> Option[Seq]

    refsFromDefinition match
      case Some(locations) => locations
      case None => definitionProvider.getDefinition(uri, position) // not a definition — normal flow

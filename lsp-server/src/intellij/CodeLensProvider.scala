package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.CodeLens

import scala.jdk.CollectionConverters.*

// Contributor trait for code lens entries.
// Each contributor knows how to collect lenses and resolve them.
trait CodeLensContributor:
  def collectLenses(psiFile: PsiFile, document: Document): Seq[CodeLens]
  def resolve(codeLens: CodeLens): CodeLens
  def id: String

// Registry that collects code lenses from all registered contributors.
class CodeLensProvider(projectManager: IntellijProjectManager, contributors: List[CodeLensContributor]):

  def getCodeLenses(uri: String): Seq[CodeLens] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        contributors.flatMap: contributor =>
          try contributor.collectLenses(psiFile, document)
          catch
            case e: Exception =>
              System.err.println(s"[CodeLensProvider] Contributor ${contributor.id} error: ${e.getMessage}")
              Seq.empty
      result.getOrElse(Seq.empty)

  def resolveCodeLens(codeLens: CodeLens): CodeLens =
    // Find which contributor owns this lens by checking the contributorId stored in data
    val contributorId = extractContributorId(codeLens)
    contributors.find(_.id == contributorId) match
      case Some(contributor) =>
        try contributor.resolve(codeLens)
        catch
          case e: Exception =>
            System.err.println(s"[CodeLensProvider] Resolve error for $contributorId: ${e.getMessage}")
            codeLens
      case None => codeLens

  private def extractContributorId(codeLens: CodeLens): String =
    Option(codeLens.getData) match
      case Some(data: com.google.gson.JsonObject) =>
        Option(data.get("contributorId")).map(_.getAsString).getOrElse("")
      case Some(data: com.google.gson.JsonElement) =>
        try
          val obj = data.getAsJsonObject
          Option(obj.get("contributorId")).map(_.getAsString).getOrElse("")
        catch case _: Exception => ""
      case _ => ""

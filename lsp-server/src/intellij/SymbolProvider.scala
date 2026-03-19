package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.*
import com.intellij.psi.search.{GlobalSearchScope, PsiSearchHelper}
import org.eclipse.lsp4j.{DocumentSymbol, SymbolInformation, SymbolKind, Location as LspLocation}

import scala.jdk.CollectionConverters.*

/** Implements textDocument/documentSymbol and workspace/symbol. */
class SymbolProvider(projectManager: IntellijProjectManager):

  // --- textDocument/documentSymbol ---

  def documentSymbols(uri: String): Seq[DocumentSymbol] =
    ReadAction.compute[Seq[DocumentSymbol], RuntimeException]: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        collectSymbols(psiFile, document)

      result.getOrElse(Seq.empty)

  private def collectSymbols(element: PsiElement, document: com.intellij.openapi.editor.Document): Seq[DocumentSymbol] =
    val children = element.getChildren
    val result = Seq.newBuilder[DocumentSymbol]
    for child <- children do
      elementToSymbol(child, document) match
        case Some(sym) => result += sym
        case None =>
          // Even if this element isn't significant, its children might be
          result ++= collectSymbols(child, document)
    result.result()

  private def elementToSymbol(element: PsiElement, document: com.intellij.openapi.editor.Document): Option[DocumentSymbol] =
    element match
      case named: PsiNamedElement if isSignificantElement(named) =>
        val name = Option(named.getName).getOrElse("<anonymous>")
        val kind = getSymbolKind(named)
        val range = PsiUtils.elementToRange(document, element)
        val selectionRange = PsiUtils.nameElementToRange(document, element)
        val symbol = new DocumentSymbol(name, kind, range, selectionRange)

        val childSymbols = collectSymbols(element, document)
        if childSymbols.nonEmpty then
          symbol.setChildren(childSymbols.asJava)

        Some(symbol)

      case _ =>
        None

  private def isSignificantElement(element: PsiNamedElement): Boolean =
    val name = element.getName
    if name == null || name.isEmpty then return false

    val className = element.getClass.getName
    className.contains("ScTypeDefinition") ||
    className.contains("ScClass") ||
    className.contains("ScTrait") ||
    className.contains("ScObject") ||
    className.contains("ScFunction") ||
    className.contains("ScValue") ||
    className.contains("ScVariable") ||
    className.contains("ScTypeAlias") ||
    className.contains("ScPackaging") ||
    className.contains("PsiClass") ||
    className.contains("PsiMethod")

  private def getSymbolKind(element: PsiNamedElement): SymbolKind =
    val className = element.getClass.getName
    if className.contains("ScClass") || className.contains("PsiClass") then SymbolKind.Class
    else if className.contains("ScTrait") then SymbolKind.Interface
    else if className.contains("ScObject") then SymbolKind.Module
    else if className.contains("ScFunction") || className.contains("PsiMethod") then SymbolKind.Method
    else if className.contains("ScVariable") then SymbolKind.Variable
    else if className.contains("ScValue") then SymbolKind.Field
    else if className.contains("ScTypeAlias") then SymbolKind.TypeParameter
    else if className.contains("ScPackaging") then SymbolKind.Package
    else SymbolKind.Variable

  // --- workspace/symbol ---

  def workspaceSymbols(query: String): Seq[SymbolInformation] =
    if query.isEmpty then return Seq.empty

    ReadAction.compute[Seq[SymbolInformation], RuntimeException]: () =>
      val project = projectManager.getProject
      searchViaStubs(project, query)

  private def searchViaStubs(project: com.intellij.openapi.project.Project, query: String): Seq[SymbolInformation] =
    try
      val results = Seq.newBuilder[SymbolInformation]
      val scope = GlobalSearchScope.projectScope(project)

      val helper = PsiSearchHelper.getInstance(project)

      // Use word index to find files containing the query
      val matchingFiles = scala.collection.mutable.Set[PsiFile]()
      helper.processAllFilesWithWord(
        query,
        scope,
        ((file: PsiFile) => { matchingFiles += file; true }): com.intellij.util.Processor[PsiFile],
        true
      )

      for file <- matchingFiles do
        collectNamedElements(file, query).foreach: (elem, kind) =>
          PsiUtils.elementToLocation(elem).foreach: loc =>
            val containerName = getContainerName(elem)
            results += new SymbolInformation(elem.asInstanceOf[PsiNamedElement].getName, kind, loc, containerName)

      results.result()
    catch
      case e: Exception =>
        System.err.println(s"[SymbolProvider] Error searching workspace symbols: ${e.getMessage}")
        Seq.empty

  private def collectNamedElements(element: PsiElement, query: String): Seq[(PsiElement, SymbolKind)] =
    val results = Seq.newBuilder[(PsiElement, SymbolKind)]

    def visit(elem: PsiElement): Unit =
      elem match
        case named: PsiNamedElement if isSignificantElement(named) && named.getName == query =>
          results += ((named, getSymbolKind(named)))
        case _ => ()
      elem.getChildren.foreach(visit)

    visit(element)
    results.result()

  private def getContainerName(element: PsiElement): String =
    var parent = element.getParent
    while parent != null do
      parent match
        case named: PsiNamedElement if isSignificantElement(named) =>
          return named.getName
        case _ =>
          parent = parent.getParent
    null

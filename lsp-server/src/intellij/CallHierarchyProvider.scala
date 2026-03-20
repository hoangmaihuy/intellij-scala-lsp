package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiFile, PsiNamedElement, PsiReference}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.lsp4j.*

import scala.jdk.CollectionConverters.*

// Implements callHierarchy/prepare, callHierarchy/incomingCalls, and callHierarchy/outgoingCalls.
class CallHierarchyProvider(projectManager: IntellijProjectManager):

  // --- prepareCallHierarchy ---
  // Returns CallHierarchyItem for the element at the given position.

  def prepare(uri: String, position: Position): Seq[CallHierarchyItem] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        val element = resolveToDeclaration(psiFile, offset)
        element.flatMap(toCallHierarchyItem).toSeq

      result.getOrElse(Seq.empty)

  // --- callHierarchy/incomingCalls ---
  // Finds all functions that call the given item.

  def incomingCalls(item: CallHierarchyItem): Seq[CallHierarchyIncomingCall] =
    projectManager.smartReadAction: () =>
      val target = findElementFromItem(item)
      target match
        case Some(element) =>
          val project = projectManager.getProject
          val scope = GlobalSearchScope.projectScope(project)

          // Find all references to this element
          val refs = ReferencesSearch.search(element, scope, false).findAll().asScala

          // Group references by their enclosing function/method
          val callerMap = scala.collection.mutable.LinkedHashMap[PsiElement, scala.collection.mutable.ArrayBuffer[Range]]()

          for ref <- refs do
            val refElement = ref.getElement
            findEnclosingCallable(refElement).foreach: caller =>
              val ranges = callerMap.getOrElseUpdate(caller, scala.collection.mutable.ArrayBuffer())
              // The fromRange is where the call occurs within the caller
              val refDoc = Option(refElement.getContainingFile).flatMap(f => Option(f.getVirtualFile))
                .flatMap(vf => Option(FileDocumentManager.getInstance().getDocument(vf)))
              refDoc.foreach: doc =>
                ranges += PsiUtils.elementToRange(doc, refElement)

          callerMap.flatMap: (caller, ranges) =>
            toCallHierarchyItem(caller).map: callerItem =>
              new CallHierarchyIncomingCall(callerItem, ranges.asJava)
          .toSeq

        case None =>
          Seq.empty

  // --- callHierarchy/outgoingCalls ---
  // Finds all functions called from within the given item.

  def outgoingCalls(item: CallHierarchyItem): Seq[CallHierarchyOutgoingCall] =
    projectManager.smartReadAction: () =>
      val target = findElementFromItem(item)
      target match
        case Some(element) =>
          // Walk the PSI subtree of this element and find all references to other callables
          val calleeMap = scala.collection.mutable.LinkedHashMap[PsiElement, scala.collection.mutable.ArrayBuffer[Range]]()
          val document = Option(element.getContainingFile).flatMap(f => Option(f.getVirtualFile))
            .flatMap(vf => Option(FileDocumentManager.getInstance().getDocument(vf)))

          document match
            case Some(doc) =>
              collectOutgoingRefs(element, doc, calleeMap)
              calleeMap.flatMap: (callee, ranges) =>
                toCallHierarchyItem(callee).map: calleeItem =>
                  new CallHierarchyOutgoingCall(calleeItem, ranges.asJava)
              .toSeq
            case None =>
              Seq.empty

        case None =>
          Seq.empty

  // --- Helpers ---

  private def resolveToDeclaration(psiFile: PsiFile, offset: Int): Option[PsiElement] =
    PsiUtils.findReferenceElementAt(psiFile, offset).flatMap: element =>
      val ref = element.getReference
      if ref != null then
        Option(ref.resolve()).orElse(Some(element))
      else
        Some(element)

  private def toCallHierarchyItem(element: PsiElement): Option[CallHierarchyItem] =
    element match
      case named: PsiNamedElement =>
        val name = Option(named.getName).getOrElse("<anonymous>")
        for
          file <- Option(element.getContainingFile)
          vf <- Option(file.getVirtualFile)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val uri = PsiUtils.vfToUri(vf)
          val range = PsiUtils.elementToRange(document, element)
          val selectionRange = PsiUtils.nameElementToRange(document, element)
          val kind = PsiUtils.getSymbolKind(named)
          val detail = getContainerName(element)
          val item = new CallHierarchyItem(name, kind, uri, range, selectionRange)
          if detail != null then item.setDetail(detail)
          // Store location info in data for later retrieval
          item.setData(s"${uri}#${selectionRange.getStart.getLine}:${selectionRange.getStart.getCharacter}")
          item
      case _ => None

  private def findElementFromItem(item: CallHierarchyItem): Option[PsiElement] =
    val uri = item.getUri
    val selRange = item.getSelectionRange
    for
      psiFile <- projectManager.findPsiFile(uri)
      vf <- projectManager.findVirtualFile(uri)
      document <- Option(FileDocumentManager.getInstance().getDocument(vf))
    yield
      val offset = PsiUtils.positionToOffset(document, selRange.getStart)
      // Find the element at the selection range start and walk up to find a named element
      var elem = psiFile.findElementAt(offset)
      while elem != null && !isCallable(elem) do
        elem = elem.getParent
      if elem != null then elem else psiFile.findElementAt(offset)

  private def findEnclosingCallable(element: PsiElement): Option[PsiElement] =
    var current = element.getParent
    while current != null do
      if isCallable(current) then return Some(current)
      current = current.getParent
    None

  private def isCallable(element: PsiElement): Boolean =
    val className = element.getClass.getName
    className.contains("ScFunction") ||
    className.contains("ScPatternDefinition") ||  // val = ...
    className.contains("ScVariableDefinition") || // var = ...
    className.contains("PsiMethod") ||
    // Class/object bodies can also be "callers" for constructor-level code
    className.contains("ScClass") ||
    className.contains("ScObject") ||
    className.contains("ScTrait")

  private def collectOutgoingRefs(
    element: PsiElement,
    document: com.intellij.openapi.editor.Document,
    calleeMap: scala.collection.mutable.LinkedHashMap[PsiElement, scala.collection.mutable.ArrayBuffer[Range]]
  ): Unit =
    // Recursively visit children looking for references
    val children = element.getChildren
    for child <- children do
      val ref = child.getReference
      if ref != null then
        val resolved = ref.resolve()
        if resolved != null && isCallable(resolved) then
          val ranges = calleeMap.getOrElseUpdate(resolved, scala.collection.mutable.ArrayBuffer())
          ranges += PsiUtils.elementToRange(document, child)
      collectOutgoingRefs(child, document, calleeMap)

  private def getContainerName(element: PsiElement): String =
    var parent = element.getParent
    while parent != null do
      parent match
        case named: PsiNamedElement if isCallable(parent) =>
          return named.getName
        case _ =>
          parent = parent.getParent
    null

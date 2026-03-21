package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiClass, PsiElement, PsiNamedElement}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import org.eclipse.lsp4j.{Position, SymbolKind, TypeHierarchyItem}

import scala.jdk.CollectionConverters.*

/**
 * Implements typeHierarchy/prepare, typeHierarchy/supertypes, and typeHierarchy/subtypes.
 */
class TypeHierarchyProvider(projectManager: IntellijProjectManager):

  def prepare(uri: String, position: Position): Seq[TypeHierarchyItem] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        val element = resolveToTypeElement(psiFile, offset)
        element.flatMap(toTypeHierarchyItem).toSeq

      result.getOrElse(Seq.empty)

  def supertypes(item: TypeHierarchyItem): Seq[TypeHierarchyItem] =
    projectManager.smartReadAction: () =>
      findElementFromItem(item) match
        case Some(element) =>
          getSupertypes(element).flatMap(toTypeHierarchyItem)
        case None => Seq.empty

  def subtypes(item: TypeHierarchyItem): Seq[TypeHierarchyItem] =
    projectManager.smartReadAction: () =>
      findElementFromItem(item) match
        case Some(element) =>
          try
            val project = projectManager.getProject
            val scope = GlobalSearchScope.projectScope(project)
            DefinitionsScopedSearch.search(element, scope)
              .findAll()
              .asScala
              .flatMap(toTypeHierarchyItem)
              .toSeq
          catch
            case e: Exception =>
              System.err.println(s"[TypeHierarchyProvider] Error finding subtypes: ${e.getMessage}")
              Seq.empty
        case None => Seq.empty

  private def resolveToTypeElement(psiFile: com.intellij.psi.PsiFile, offset: Int): Option[PsiElement] =
    PsiUtils.findReferenceElementAt(psiFile, offset).flatMap: element =>
      val ref = element.getReference
      val resolved = if ref != null then Option(ref.resolve()).orElse(Some(element)) else Some(element)
      resolved.flatMap: el =>
        // Walk up to find the nearest type (class/trait/object)
        var current = el
        while current != null && !isTypeElement(current) do
          current = current.getParent
        Option(current)

  private def isTypeElement(element: PsiElement): Boolean =
    ScalaTypes.isClass(element) || ScalaTypes.isTrait(element) ||
      ScalaTypes.isObject(element) || element.isInstanceOf[PsiClass]

  private def getSupertypes(element: PsiElement): Seq[PsiElement] =
    val syntheticTypes = Set("java.lang.Object", "scala.Any", "scala.AnyRef")
    val caseClassSynthetics = Set("scala.Product", "scala.Serializable", "java.io.Serializable")

    val isCaseClass = ScalaTypes.isClass(element) && ScalaTypes.isCase(element)

    def fallbackToGetSupers(el: PsiElement): Seq[PsiElement] = el match
      case psiClass: PsiClass =>
        psiClass.getSupers
          .filter: sup =>
            val qn = Option(sup.getQualifiedName)
            !qn.exists(syntheticTypes.contains) &&
              !(isCaseClass && qn.exists(caseClassSynthetics.contains))
          .toSeq
      case _ => Seq.empty

    if ScalaTypes.isTemplateDefinition(element) then
      // Try signature-based parent resolution first for more accurate results
      val signatureParents: Option[Seq[PsiElement]] =
        try
          for
            eb <- ScalaTypes.invokeMethod(element, "extendsBlock").map(_.asInstanceOf[PsiElement])
            tpRaw <- { try { val r = eb.getClass.getMethod("templateParents").invoke(eb); r match { case opt: Option[?] => opt; case other => Option(other) } } catch { case _: Exception => None } }
          yield
            try
              val teRaw = tpRaw.getClass.getMethod("typeElements").invoke(tpRaw)
              teRaw.asInstanceOf[scala.collection.Seq[?]].flatMap: te =>
                val tePsi = te.asInstanceOf[PsiElement]
                Option(tePsi.getReference).flatMap(r => Option(r.resolve()))
              .toSeq
            catch case _: Exception => Seq.empty
        catch case _: Exception => None

      signatureParents match
        case Some(parents) if parents.nonEmpty =>
          parents.filter: parent =>
            val qn = parent match
              case pc: PsiClass => Option(pc.getQualifiedName)
              case _ => None
            !qn.exists(syntheticTypes.contains) &&
              !(isCaseClass && qn.exists(caseClassSynthetics.contains))
        case _ => fallbackToGetSupers(element)
    else fallbackToGetSupers(element)

  private def toTypeHierarchyItem(element: PsiElement): Option[TypeHierarchyItem] =
    element match
      case named: PsiNamedElement =>
        val name = Option(named.getName).getOrElse("<anonymous>")
        val kind = getSymbolKind(element)
        // Try direct document access first (project sources)
        val directResult = for
          file <- Option(element.getContainingFile)
          vf <- Option(file.getVirtualFile)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val uri = PsiUtils.vfToUri(vf)
          val range = PsiUtils.elementToRange(document, element)
          val selectionRange = PsiUtils.nameElementToRange(document, element)
          val item = TypeHierarchyItem(name, kind, uri, range, selectionRange)
          // Encode location for later resolution (same pattern as CallHierarchyProvider)
          item.setData(s"${uri}#${selectionRange.getStart.getLine}:${selectionRange.getStart.getCharacter}")
          item
        // Fall back to PsiUtils.elementToLocation for library types (JAR source caching)
        directResult.orElse:
          PsiUtils.elementToLocation(element).map: location =>
            val uri = location.getUri
            val range = location.getRange
            val item = TypeHierarchyItem(name, kind, uri, range, range)
            item.setData(s"${uri}#${range.getStart.getLine}:${range.getStart.getCharacter}")
            item
      case _ => None

  private def findElementFromItem(item: TypeHierarchyItem): Option[PsiElement] =
    val uri = item.getUri
    val selRange = item.getSelectionRange
    val result = for
      psiFile <- projectManager.findPsiFile(uri)
      vf <- projectManager.findVirtualFile(uri)
      document <- Option(FileDocumentManager.getInstance().getDocument(vf))
    yield
      val offset = PsiUtils.positionToOffset(document, selRange.getStart)
      var elem = psiFile.findElementAt(offset)
      while elem != null && !isTypeElement(elem) do
        elem = elem.getParent
      elem

    result.flatMap(Option(_))

  private def getSymbolKind(element: PsiElement): SymbolKind =
    if ScalaTypes.isTrait(element) then SymbolKind.Interface
    else if ScalaTypes.isObject(element) then SymbolKind.Module
    else SymbolKind.Class

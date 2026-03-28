package org.jetbrains.scalalsP.intellij

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{Location, Position}
import scala.jdk.CollectionConverters.*

/**
 * Implements textDocument/definition by delegating to IntelliJ's GotoDeclarationHandler
 * extension point (which includes ScalaGoToDeclarationHandler from the Scala plugin).
 * This ensures all Scala-specific resolution logic (synthetic elements, companion objects,
 * function vals, etc.) is handled correctly.
 */
class DefinitionProvider(projectManager: IntellijProjectManager):

  def getDefinition(uri: String, position: Position): Seq[Location] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        resolveAtOffset(psiFile, offset)

      result.getOrElse(Seq.empty)

  private def resolveAtOffset(psiFile: com.intellij.psi.PsiFile, offset: Int): Seq[Location] =
    val sourceElement = psiFile.findElementAt(offset) match
      case ws: com.intellij.psi.PsiWhiteSpace =>
        com.intellij.psi.util.PsiTreeUtil.prevLeaf(ws)
      case e => e

    if sourceElement == null then return Seq.empty

    // Delegate to registered GotoDeclarationHandlers (includes ScalaGoToDeclarationHandler)
    val targets = resolveViaHandlers(sourceElement, offset)
    System.err.println(s"[Definition] GotoDeclarationHandlers returned ${targets.length} targets")

    if targets.nonEmpty then
      val locations = targets.flatMap(targetToLocation)
      if locations.nonEmpty then return locations

    // Fallback: try generic PSI reference resolution
    System.err.println(s"[Definition] Falling back to PSI reference resolution")
    resolveViaReference(psiFile, offset)

  /** Delegate to IntelliJ's GotoDeclarationHandler extension point. */
  private def resolveViaHandlers(sourceElement: PsiElement, offset: Int): Seq[PsiElement] =
    val handlers = GotoDeclarationHandler.EP_NAME.getExtensionList.asScala
    handlers.iterator.map: handler =>
      try
        val targets = handler.getGotoDeclarationTargets(sourceElement, offset, null)
        if targets != null && targets.nonEmpty then
          System.err.println(s"[Definition] ${handler.getClass.getSimpleName} returned ${targets.length} targets: ${targets.map(t => s"${t.getClass.getSimpleName}(${nameOf(t)})").mkString(", ")}")
          targets.toSeq
        else Seq.empty
      catch case e: Exception =>
        System.err.println(s"[Definition] ${handler.getClass.getSimpleName} threw: ${e.getMessage}")
        Seq.empty
    .find(_.nonEmpty).getOrElse(Seq.empty)

  /** Fallback: resolve via PSI reference (for non-Scala files or when handlers return nothing). */
  private def resolveViaReference(psiFile: com.intellij.psi.PsiFile, offset: Int): Seq[Location] =
    PsiUtils.findReferenceElementAt(psiFile, offset) match
      case Some(element) =>
        val ref = element.getReference
        if ref != null then
          val polyRefOpt: Option[com.intellij.psi.PsiPolyVariantReference] = ref match
            case pr: com.intellij.psi.PsiPolyVariantReference => Some(pr)
            case _ => element match
              case pr: com.intellij.psi.PsiPolyVariantReference => Some(pr)
              case _ => None

          val resolvedTargets: Seq[PsiElement] = polyRefOpt match
            case Some(polyRef) =>
              val strict = polyRef.multiResolve(false)
              val results = if strict.nonEmpty then strict else polyRef.multiResolve(true)
              results.flatMap(rr => Option(rr.getElement)).toSeq match
                case Seq() => Option(ref.resolve()).toSeq
                case targets => targets
            case None =>
              Option(ref.resolve()).toSeq

          val refName = element match
            case n: com.intellij.psi.PsiNamedElement => n.getName
            case _ => element.getText.trim.split("\\W").headOption.getOrElse("")
          val locations = resolvedTargets.flatMap: target =>
            targetToLocation(target).orElse(fallbackToContainer(target, refName))
          if locations.nonEmpty then locations
          else navigateElement(element)
        else
          navigateElement(element)
      case None =>
        Seq.empty

  /** Convert a PSI target element to a Location, using getNavigationElement. */
  private def targetToLocation(target: PsiElement): Option[Location] =
    val nav = target.getNavigationElement
    // Prefer navigation element if it has a real file
    val effective = if nav != null && nav.getContainingFile != null &&
      nav.getContainingFile.getVirtualFile != null then nav else target
    PsiUtils.elementToLocation(effective).orElse:
      // If direct location fails, try resolving library source
      PsiUtils.resolveLibraryElement(effective).flatMap(PsiUtils.elementToLocation)

  /** When a resolved target has no VirtualFile, fall back to container class. */
  private def fallbackToContainer(target: PsiElement, refName: String): Option[Location] =
    val containingClass = target match
      case m: com.intellij.psi.PsiMethod => Option(m.getContainingClass)
      case _ => None

    val cls = containingClass.orElse:
      var p = target.getParent
      var depth = 0
      var found: Option[com.intellij.psi.PsiClass] = None
      while p != null && depth < 10 && found.isEmpty do
        p match
          case c: com.intellij.psi.PsiClass => found = Some(c)
          case _ => ()
        p = p.getParent
        depth += 1
      found

    val fromClass = cls.flatMap: c =>
      PsiUtils.elementToLocation(c).orElse:
        PsiUtils.resolveLibraryElement(c).flatMap(PsiUtils.elementToLocation)

    if fromClass.isDefined then return fromClass

    if refName.nonEmpty then findRealElementByName(refName)
    else None

  /** Find a real (non-synthetic) element by name via IntelliJ's symbol index. */
  private def findRealElementByName(name: String): Option[Location] =
    import com.intellij.navigation.{ChooseByNameContributor, ChooseByNameContributorEx, NavigationItem}
    import com.intellij.util.indexing.FindSymbolParameters

    try
      val project = projectManager.getProject
      val contributors =
        ChooseByNameContributor.CLASS_EP_NAME.getExtensionList.asScala ++
        ChooseByNameContributor.SYMBOL_EP_NAME.getExtensionList.asScala

      var result: Option[Location] = None
      val iter = contributors.iterator
      while result.isEmpty && iter.hasNext do
        iter.next() match
          case ex: ChooseByNameContributorEx =>
            val params = FindSymbolParameters.wrap(name, project, true)
            ex.processElementsWithName(
              name,
              ((item: NavigationItem) => {
                if result.isEmpty then
                  item match
                    case psi: PsiElement =>
                      val unwrapped = PsiUtils.unwrapSyntheticElement(psi)
                      val hasVf = unwrapped.getContainingFile != null &&
                        unwrapped.getContainingFile.getVirtualFile != null
                      if hasVf then
                        unwrapped match
                          case n: com.intellij.psi.PsiNamedElement if n.getName == name =>
                            PsiUtils.elementToLocation(unwrapped).foreach: loc =>
                              result = Some(loc)
                          case _ => ()
                    case _ => ()
                result.isEmpty
              }): com.intellij.util.Processor[NavigationItem],
              params,
            )
          case _ => ()
      result
    catch
      case e: Exception =>
        System.err.println(s"[Definition] fallback search failed for '$name': ${e.getMessage}")
        None

  private def navigateElement(element: PsiElement): Seq[Location] =
    element match
      case named: com.intellij.psi.PsiNamedElement =>
        PsiUtils.elementToLocation(named).toSeq
      case _ =>
        Seq.empty

  private def nameOf(e: PsiElement): String = e match
    case n: com.intellij.psi.PsiNamedElement => n.getName
    case _ => "?"

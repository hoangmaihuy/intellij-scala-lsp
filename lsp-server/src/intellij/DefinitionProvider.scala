package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.{Location, Position}

/**
 * Implements textDocument/definition by resolving references through IntelliJ's PSI.
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
        val locations = resolveAtOffset(psiFile, offset)
        locations

      result.getOrElse(Seq.empty)

  private def resolveAtOffset(psiFile: com.intellij.psi.PsiFile, offset: Int): Seq[Location] =
    PsiUtils.findReferenceElementAt(psiFile, offset) match
      case Some(element) =>
        System.err.println(s"[Definition] element: ${element.getClass.getSimpleName} '${element.getText.take(50)}' ref=${element.getReference}")
        val ref = element.getReference
        if ref != null then
          // Try multiResolve for poly-variant references (check ref, not element)
          val polyRefOpt: Option[com.intellij.psi.PsiPolyVariantReference] = ref match
            case pr: com.intellij.psi.PsiPolyVariantReference => Some(pr)
            case _ => element match
              case pr: com.intellij.psi.PsiPolyVariantReference => Some(pr)
              case _ => None

          val resolvedTargets: Seq[PsiElement] = polyRefOpt match
            case Some(polyRef) =>
              val strict = polyRef.multiResolve(false)
              val results = if strict.nonEmpty then strict else polyRef.multiResolve(true)
              val targets = results.flatMap(rr => Option(rr.getElement)).toSeq
              System.err.println(s"[Definition] multiResolve: strict=${strict.length}, results=${results.length}, targets=${targets.map(t => s"${t.getClass.getSimpleName}").mkString(", ")}")
              if targets.nonEmpty then targets
              else Option(ref.resolve()).toSeq
            case None =>
              val resolved = Option(ref.resolve())
              System.err.println(s"[Definition] resolve: ${resolved.map(t => s"${t.getClass.getSimpleName}").getOrElse("null")}")
              resolved.toSeq

          resolvedTargets.foreach: target =>
            val nav = target.getNavigationElement
            val hasFile = target.getContainingFile != null
            val hasVf = hasFile && target.getContainingFile.getVirtualFile != null
            val navHasFile = nav != null && nav.getContainingFile != null
            val navHasVf = navHasFile && nav.getContainingFile.getVirtualFile != null
            val fileName = if hasFile then target.getContainingFile.getClass.getSimpleName + ":" + target.getContainingFile.getName else "none"
            val parentClass = target match
              case m: com.intellij.psi.PsiMethod => Option(m.getContainingClass).map(c => s"${c.getClass.getSimpleName}:${c.getName}").getOrElse("null")
              case _ =>
                var p = target.getParent
                var depth = 0
                var found = "none"
                while p != null && depth < 10 do
                  p match
                    case c: com.intellij.psi.PsiClass =>
                      found = s"${c.getClass.getSimpleName}:${c.getName}"
                      p = null
                    case n: com.intellij.psi.PsiNamedElement =>
                      found = s"${n.getClass.getSimpleName}:${n.getName}"
                      p = null
                    case _ =>
                      p = p.getParent
                      depth += 1
                  end match
                found
            val targetName = target match { case n: com.intellij.psi.PsiNamedElement => n.getName case _ => "?" }
            val targetText = try target.getText.take(100) catch { case _: Exception => "?" }
            System.err.println(s"[Definition] target: ${target.getClass.getName} name=$targetName hasFile=$hasFile hasVf=$hasVf file=$fileName nav=${if nav != null then nav.getClass.getSimpleName else "null"} navHasFile=$navHasFile navHasVf=$navHasVf parent=$parentClass")
            System.err.println(s"[Definition] target text: $targetText")

          // For each target, try direct location first; if it fails (e.g., synthetic apply
          // where the target has no VirtualFile), fall back using the original reference name
          val refName = element match
            case n: com.intellij.psi.PsiNamedElement => n.getName
            case _ => element.getText.trim.split("\\W").headOption.getOrElse("")
          val locations = resolvedTargets.flatMap: target =>
            PsiUtils.elementToLocation(target).orElse(fallbackToContainer(target, refName))
          System.err.println(s"[Definition] locations: ${locations.size}")
          if locations.nonEmpty then locations
          else navigateElement(element)
        else
          System.err.println(s"[Definition] no ref, trying navigateElement")
          // Element itself might be a declaration; try to navigate to it
          navigateElement(element)
      case None =>
        System.err.println(s"[Definition] findReferenceElementAt returned None")
        Seq.empty

  /** When a resolved target has no VirtualFile (e.g., synthetic element in dummy.scala),
    * try to find the real element by walking up to a class or resolving by name.
    * @param refName the original reference name from the source (e.g., "BackButton") */
  private def fallbackToContainer(target: PsiElement, refName: String): Option[Location] =
    System.err.println(s"[Definition] fallbackToContainer for ${target.getClass.getSimpleName}, refName=$refName")

    // Try PsiMethod.getContainingClass first
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
      System.err.println(s"[Definition] fallback: found class ${c.getName}")
      PsiUtils.elementToLocation(c).orElse:
        PsiUtils.resolveLibraryElement(c).flatMap(PsiUtils.elementToLocation)

    if fromClass.isDefined then return fromClass

    // No containing class (e.g., synthetic apply in dummy.scala).
    // Use the original reference name to find the real definition.
    System.err.println(s"[Definition] fallback: no class, searching symbol '$refName'")
    if refName.nonEmpty then findRealElementByName(refName)
    else None

  /** Find a real (non-synthetic) element by name via IntelliJ's symbol index. */
  private def findRealElementByName(name: String): Option[Location] =
    import com.intellij.navigation.{ChooseByNameContributor, ChooseByNameContributorEx, NavigationItem}
    import com.intellij.psi.search.GlobalSearchScope
    import com.intellij.util.indexing.FindSymbolParameters
    import scala.jdk.CollectionConverters.*

    try
      val project = projectManager.getProject
      val scope = GlobalSearchScope.allScope(project)
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
                      // Only accept elements with a real VirtualFile
                      val hasVf = unwrapped.getContainingFile != null &&
                        unwrapped.getContainingFile.getVirtualFile != null
                      if hasVf then
                        unwrapped match
                          case n: com.intellij.psi.PsiNamedElement if n.getName == name =>
                            PsiUtils.elementToLocation(unwrapped).foreach: loc =>
                              System.err.println(s"[Definition] fallback: found '$name' at ${loc.getUri}:${loc.getRange.getStart.getLine}")
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
    // If the element is a named element, return its own location
    element match
      case named: com.intellij.psi.PsiNamedElement =>
        PsiUtils.elementToLocation(named).toSeq
      case _ =>
        Seq.empty

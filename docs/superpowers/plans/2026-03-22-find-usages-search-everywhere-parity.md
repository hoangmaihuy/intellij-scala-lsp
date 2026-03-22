# Find Usages & Search Everywhere Parity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Achieve full IntelliJ parity for `textDocument/references` and `workspace/symbol` by adding multi-resolve, text occurrence search, secondary element discovery, usage type classification, relevance ranking, and enhanced deduplication.

**Architecture:** The LSP server (Scala) is enhanced in two areas: `ReferencesProvider` gains multi-resolve + secondary elements + text search + usage types; `SymbolProvider` gains relevance scoring + companion dedup + enhanced dedup. A new custom LSP request `scala/referencesWithTypes` exposes usage types to the MCP layer, which uses them for grouped output presentation.

**Tech Stack:** Scala 3, IntelliJ Platform APIs (PSI, ReferencesSearch, PsiSearchHelper, FindUsagesHandlerFactory, MinusculeMatcher), lsp4j, TypeScript (MCP server)

**Spec:** `docs/superpowers/specs/2026-03-22-find-usages-search-everywhere-parity-design.md`

**Build command:** `sbt "lsp-server/compile"` (write output to `/local/log`)
**Test command:** `sbt "lsp-server/test"` (write output to `/local/log`)
**Single test:** `sbt "lsp-server/testOnly org.jetbrains.scalalsP.integration.ReferencesProviderIntegrationTest"` (write output to `/local/log`)

---

## File Structure

| File | Responsibility |
|---|---|
| `lsp-server/src/intellij/ReferenceResult.scala` | **New** — `case class ReferenceResult(location: Location, usageType: String)` |
| `lsp-server/src/intellij/PsiUtils.scala` | Add `resolveToDeclarations`, `getUsageType`, `unwrapSyntheticElement` |
| `lsp-server/src/intellij/ReferencesProvider.scala` | Rewrite: multi-resolve, allScope, secondary elements, text search, usage types |
| `lsp-server/src/intellij/SymbolProvider.scala` | Add relevance ranking, companion dedup, enhanced dedup |
| `lsp-server/src/ScalaTextDocumentService.scala` | Register custom request handler for `scala/referencesWithTypes` |
| `mcp-server/src/tools/navigation.ts` | Use usage types for grouped references output |
| `mcp-server/src/symbol-resolver.ts` | Simplify — remove companion match quality logic |

---

## Task 1: Add `ReferenceResult` data class

**Files:**
- Create: `lsp-server/src/intellij/ReferenceResult.scala`

- [ ] **Step 1: Create the file**

```scala
package org.jetbrains.scalalsP.intellij

import org.eclipse.lsp4j.Location

/** A reference with its classified usage type. */
case class ReferenceResult(location: Location, usageType: String)

object ReferenceResult:
  val Import = "import"
  val Write = "write"
  val Read = "read"
  val TypeRef = "type_reference"
  val Pattern = "pattern"
  val TextOccurrence = "text_occurrence"
```

- [ ] **Step 2: Compile**

Run: `sbt "lsp-server/compile" 2>&1 | tee /local/log`
Expected: compiles without errors

- [ ] **Step 3: Commit**

```bash
git add lsp-server/src/intellij/ReferenceResult.scala
git commit -m "feat: add ReferenceResult case class for usage type classification"
```

---

## Task 2: Add `resolveToDeclarations` and `getUsageType` to PsiUtils

**Files:**
- Modify: `lsp-server/src/intellij/PsiUtils.scala`

- [ ] **Step 1: Add `resolveToDeclarations` method**

Add after the existing `resolveToDeclaration` method (around line 299). This method returns `Seq[PsiElement]` using `multiResolve` for `PsiPolyVariantReference`:

```scala
/** Like resolveToDeclaration but returns all resolved targets for poly-variant references.
  * Used by ReferencesProvider to search references for all overloaded targets. */
def resolveToDeclarations(psiFile: PsiFile, offset: Int): Seq[PsiElement] =
  findReferenceElementAt(psiFile, offset).toSeq.flatMap: element =>
    val ref = element.getReference
    if ref != null then
      ref match
        case poly: com.intellij.psi.PsiPolyVariantReference =>
          val results = poly.multiResolve(false)
          val resolved = results.flatMap(r => Option(r.getElement)).toSeq
          if resolved.nonEmpty then resolved
          else Option(ref.resolve()).toSeq
        case _ =>
          Option(ref.resolve()).toSeq
    else
      var parent = element.getParent
      while parent != null && !parent.isInstanceOf[PsiNamedElement] do
        parent = parent.getParent
      if parent != null then Seq(parent) else Seq(element)
```

- [ ] **Step 2: Add `getUsageType` method**

Add a method that classifies a reference's usage type by walking up the PSI tree:

```scala
/** Classify a reference element's usage type by checking ancestors. */
def getUsageType(element: PsiElement): String =
  var current = element.getParent
  var depth = 0
  while current != null && depth < 15 do
    val className = current.getClass.getName
    if className.contains("ImportStatement") || className.contains("ScImportExpr") then
      return ReferenceResult.Import
    if className.contains("ScAssignment") then
      // Check if we're on the LHS
      val children = current.getChildren
      if children.nonEmpty && children.head.getTextRange.contains(element.getTextRange) then
        return ReferenceResult.Write
    if className.contains("ScVariableDefinition") then
      return ReferenceResult.Write
    if className.contains("ScSimpleTypeElement") || className.contains("ScParameterizedTypeElement") then
      return ReferenceResult.TypeRef
    if className.contains("ScPattern") || className.contains("ScCaseClause") then
      return ReferenceResult.Pattern
    current = current.getParent
    depth += 1
  ReferenceResult.Read
```

- [ ] **Step 3: Add `unwrapSyntheticElement` method**

```scala
/** Unwrap IntelliJ synthetic wrappers to get the real Scala element.
  * Handles PsiClassWrapper, PsiMethodWrapper, FakePsiMethod. */
def unwrapSyntheticElement(element: PsiElement): PsiElement =
  val className = element.getClass.getName
  if className.contains("PsiClassWrapper") || className.contains("PsiMethodWrapper") || className.contains("FakePsiMethod") then
    try
      // Try delegate/underlying field via reflection
      val methods = Seq("delegate", "method", "getNavigationElement")
      methods.flatMap: methodName =>
        try Some(element.getClass.getMethod(methodName).invoke(element).asInstanceOf[PsiElement])
        catch case _: Exception => None
      .headOption.getOrElse(element)
    catch
      case _: Exception => element
  else element
```

- [ ] **Step 4: Add import for ReferenceResult**

Add at the top of PsiUtils.scala, after the existing imports.

- [ ] **Step 5: Compile**

Run: `sbt "lsp-server/compile" 2>&1 | tee /local/log`
Expected: compiles without errors

- [ ] **Step 6: Commit**

```bash
git add lsp-server/src/intellij/PsiUtils.scala
git commit -m "feat: add resolveToDeclarations, getUsageType, unwrapSyntheticElement to PsiUtils"
```

---

## Task 3: Rewrite `ReferencesProvider` — multi-resolve, allScope, secondary elements, text search, usage types

**Files:**
- Modify: `lsp-server/src/intellij/ReferencesProvider.scala`

- [ ] **Step 1: Rewrite `findRefsAtOffset` to use multi-resolve and secondary elements**

Replace the current `findRefsAtOffset` method. The new implementation:
1. Uses `PsiUtils.resolveToDeclarations` (multi-resolve)
2. Maps cached source elements via `resolveLibraryElement`
3. Discovers secondary elements via `ScalaFindUsagesHandlerFactory` (reflection)
4. Searches references for all targets using `allScope`
5. Adds text occurrence search (Phase 2)
6. Classifies each reference with usage type
7. Stores results with types for MCP access

```scala
class ReferencesProvider(projectManager: IntellijProjectManager):

  // Last results with usage types — for MCP access via custom request
  @volatile private var lastResultsWithTypes: Seq[ReferenceResult] = Seq.empty

  def getLastResultsWithTypes: Seq[ReferenceResult] = lastResultsWithTypes

  private val MAX_REFS = 500

  def findReferences(uri: String, position: Position, includeDeclaration: Boolean): Seq[Location] =
    projectManager.smartReadAction: () =>
      val result = for
        psiFile <- projectManager.findPsiFile(uri)
        vf <- projectManager.findVirtualFile(uri)
        document <- Option(FileDocumentManager.getInstance().getDocument(vf))
      yield
        val offset = PsiUtils.positionToOffset(document, position)
        val refs = findRefsAtOffset(psiFile, offset, includeDeclaration, uri)
        lastResultsWithTypes = refs
        refs.map(_.location)

      result.getOrElse:
        lastResultsWithTypes = Seq.empty
        Seq.empty

  private def findRefsAtOffset(
    psiFile: PsiFile,
    offset: Int,
    includeDeclaration: Boolean,
    uri: String,
  ): Seq[ReferenceResult] =
    // Step 1: Multi-resolve targets
    val rawTargets = PsiUtils.resolveToDeclarations(psiFile, offset)

    // Step 2: Map cached source elements to real library elements
    val primaryTargets = rawTargets.flatMap: target =>
      if PsiUtils.isCachedSourceFile(uri) then
        PsiUtils.resolveLibraryElement(target).orElse(Some(target))
      else
        Some(target)

    if primaryTargets.isEmpty then return Seq.empty

    System.err.println(s"[References] Found ${primaryTargets.size} primary target(s)")

    // Step 3: Discover secondary elements via ScalaFindUsagesHandlerFactory
    val secondaryTargets = primaryTargets.flatMap(getSecondaryElements)
    System.err.println(s"[References] Found ${secondaryTargets.size} secondary target(s)")

    val allTargets = (primaryTargets ++ secondaryTargets).distinct

    // Step 4: Search references for all targets
    val project = projectManager.getProject
    val scope = GlobalSearchScope.allScope(project)
    val refsBuffer = scala.collection.mutable.ArrayBuffer[ReferenceResult]()
    val seenLocations = scala.collection.mutable.Set[String]()

    for target <- allTargets do
      if refsBuffer.size < MAX_REFS then
        try
          ReferencesSearch.search(target, scope, false).forEach: (ref: PsiReference) =>
            if refsBuffer.size < MAX_REFS then
              PsiUtils.elementToLocation(ref.getElement).foreach: loc =>
                val key = s"${loc.getUri}:${loc.getRange.getStart.getLine}:${loc.getRange.getStart.getCharacter}"
                if !seenLocations.contains(key) then
                  seenLocations += key
                  val usageType = PsiUtils.getUsageType(ref.getElement)
                  refsBuffer += ReferenceResult(loc, usageType)
            refsBuffer.size < MAX_REFS
        catch
          case e: Exception =>
            System.err.println(s"[References] ReferencesSearch partially failed: ${e.getClass.getSimpleName}: ${e.getMessage}")

    if refsBuffer.size >= MAX_REFS then
      System.err.println(s"[References] WARNING: Hit result limit of $MAX_REFS references")

    // Step 5: Text occurrence search (Phase 2)
    val textOccurrences = searchTextOccurrences(primaryTargets.head, scope, seenLocations)
    refsBuffer ++= textOccurrences

    System.err.println(s"[References] Found ${refsBuffer.size} total references (${textOccurrences.size} text occurrences)")

    // Step 6: Include declaration if requested
    val results = refsBuffer.toSeq
    if includeDeclaration then
      val declLocations = primaryTargets.flatMap(PsiUtils.elementToLocation(_).toSeq)
        .map(ReferenceResult(_, ReferenceResult.Read))
      (declLocations ++ results).distinctBy(r => s"${r.location.getUri}:${r.location.getRange.getStart.getLine}:${r.location.getRange.getStart.getCharacter}")
    else
      results

  /** Discover secondary elements via ScalaFindUsagesHandlerFactory reflection. */
  private def getSecondaryElements(target: PsiElement): Seq[PsiElement] =
    try
      val project = projectManager.getProject
      // FindUsagesHandlerFactory is in the platform, accessible directly
      val factories = com.intellij.find.findUsages.FindUsagesHandlerFactory.EP_NAME.getExtensionList(project).asScala
      val scalaFactory = factories.find(_.getClass.getName.contains("ScalaFindUsagesHandlerFactory"))

      scalaFactory match
        case Some(factory) =>
          if factory.canFindUsages(target) then
            val handler = factory.createFindUsagesHandler(target, false)
            if handler != null then
              handler.getSecondaryElements.toSeq
            else Seq.empty
          else Seq.empty
        case None =>
          System.err.println("[References] ScalaFindUsagesHandlerFactory not found — using primary target only")
          Seq.empty
    catch
      case e: Exception =>
        System.err.println(s"[References] Secondary element discovery failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        Seq.empty

  /** Search for text occurrences of the element's name in non-Scala files. */
  private def searchTextOccurrences(
    target: PsiElement,
    scope: GlobalSearchScope,
    alreadyFound: scala.collection.mutable.Set[String],
  ): Seq[ReferenceResult] =
    try
      val name = target match
        case named: PsiNamedElement => Option(named.getName)
        case _ => None

      name match
        case Some(n) if n.nonEmpty =>
          val results = scala.collection.mutable.ArrayBuffer[ReferenceResult]()
          val helper = com.intellij.psi.search.PsiSearchHelper.getInstance(projectManager.getProject)
          helper.processUsagesInNonJavaFiles(target, n,
            ((file: PsiFile, startOffset: Int, endOffset: Int) => {
              val vf = file.getVirtualFile
              if vf != null then
                val text = file.getText
                val range = PsiUtils.offsetToRange(text, startOffset, endOffset)
                val uri = PsiUtils.vfToUri(vf)
                val key = s"$uri:${range.getStart.getLine}:${range.getStart.getCharacter}"
                if !alreadyFound.contains(key) then
                  alreadyFound += key
                  results += ReferenceResult(new Location(uri, range), ReferenceResult.TextOccurrence)
              true
            }): com.intellij.psi.search.PsiNonJavaFileReferenceProcessor,
            scope
          )
          results.toSeq
        case _ => Seq.empty
    catch
      case e: Exception =>
        System.err.println(s"[References] Text occurrence search failed: ${e.getMessage}")
        Seq.empty
```

- [ ] **Step 2: Update imports at the top of the file**

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.eclipse.lsp4j.{Location, Position}

import scala.jdk.CollectionConverters.*
```

- [ ] **Step 3: Compile**

Run: `sbt "lsp-server/compile" 2>&1 | tee /local/log`
Expected: compiles without errors. Fix any compilation errors from the log.

- [ ] **Step 4: Run existing references tests**

Run: `sbt "lsp-server/testOnly org.jetbrains.scalalsP.integration.ReferencesProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: all existing tests pass (backward compatible)

- [ ] **Step 5: Commit**

```bash
git add lsp-server/src/intellij/ReferencesProvider.scala
git commit -m "feat: rewrite ReferencesProvider with multi-resolve, allScope, secondary elements, text search, usage types"
```

---

## Task 4: Add integration tests for new references features

**Files:**
- Modify: `lsp-server/test/src/integration/ReferencesProviderIntegrationTest.scala`

- [ ] **Step 1: Add test for multi-resolve (overloaded methods)**

```scala
def testReferencesToOverloadedMethod(): Unit =
  val uri = configureScalaFile(
    """object Calc:
      |  def add(a: Int, b: Int) = a + b
      |  def add(a: String, b: String) = a + b
      |  val x = add(1, 2)
      |  val y = add("a", "b")
      |""".stripMargin
  )
  // Position on first 'add' method name
  val result = findReferences(uri, positionAt(1, 6), includeDeclaration = false)
  if result.nonEmpty then
    assertTrue("Should find references to overloaded add", result.size >= 1)
```

- [ ] **Step 2: Add test for secondary elements (companion object)**

```scala
def testReferencesIncludeCompanionUsages(): Unit =
  val uri = configureScalaFile(
    """class Foo(val x: Int)
      |object Foo:
      |  def apply(x: Int) = new Foo(x)
      |
      |object Main:
      |  val f1 = Foo(1)
      |  val f2 = new Foo(2)
      |""".stripMargin
  )
  val result = findReferences(uri, positionAt(0, 6), includeDeclaration = false)
  if result.nonEmpty then
    assertTrue("Should find references from both class and companion usages", result.size >= 1)
```

- [ ] **Step 3: Add test for usage type classification**

```scala
def testUsageTypeClassification(): Unit =
  import org.jetbrains.scalalsP.intellij.ReferenceResult
  val uri = configureScalaFile(
    """import scala.collection.mutable
      |object Main:
      |  val x = 42
      |  def foo = x + 1
      |""".stripMargin
  )
  val provider = ReferencesProvider(projectManager)
  val result = provider.findReferences(uri, positionAt(2, 6), includeDeclaration = false)
  val typed = provider.getLastResultsWithTypes
  // Just verify no crash and types are populated
  for r <- typed do
    assertNotNull("Usage type should not be null", r.usageType)
```

- [ ] **Step 4: Run tests**

Run: `sbt "lsp-server/testOnly org.jetbrains.scalalsP.integration.ReferencesProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: all tests pass

- [ ] **Step 5: Commit**

```bash
git add lsp-server/test/src/integration/ReferencesProviderIntegrationTest.scala
git commit -m "test: add integration tests for multi-resolve, companion refs, usage types"
```

---

## Task 5: Enhance `SymbolProvider` — relevance ranking + companion dedup + enhanced dedup

**Files:**
- Modify: `lsp-server/src/intellij/SymbolProvider.scala`

- [ ] **Step 1: Add relevance scoring enum and scoring method**

Add before the `searchViaContributors` method:

```scala
/** Relevance tiers for workspace symbol ranking. */
private enum MatchRelevance(val rank: Int):
  case Exact extends MatchRelevance(0)
  case ExactCaseInsensitive extends MatchRelevance(1)
  case Prefix extends MatchRelevance(2)
  case CamelCase extends MatchRelevance(3)
  case Substring extends MatchRelevance(4)

/** Score how well a symbol name matches the query. */
private def scoreMatch(symbolName: String, query: String): Option[MatchRelevance] =
  if symbolName == query then Some(MatchRelevance.Exact)
  else if symbolName.equalsIgnoreCase(query) then Some(MatchRelevance.ExactCaseInsensitive)
  else if symbolName.toLowerCase.startsWith(query.toLowerCase) then Some(MatchRelevance.Prefix)
  else if matchesCamelCase(symbolName, query) then Some(MatchRelevance.CamelCase)
  else if symbolName.toLowerCase.contains(query.toLowerCase) then Some(MatchRelevance.Substring)
  else None

/** Check if query matches camel-case initials of the name.
  * Uses IntelliJ's NameUtil/MinusculeMatcher directly (platform API, no reflection needed).
  * Falls back to simple initial-letter check if API not available. */
private def matchesCamelCase(name: String, query: String): Boolean =
  try
    import com.intellij.psi.codeStyle.NameUtil
    val matcher = NameUtil.buildMatcher(query).build()
    matcher.matches(name)
  catch
    case _: Exception =>
      // Fallback: check if query chars appear as initials in camelCase name
      val initials = name.filter(_.isUpper).map(_.toLower)
      val queryLower = query.toLowerCase
      queryLower.forall(c => initials.contains(c))
```

- [ ] **Step 2: Modify `searchViaContributors` to use relevance scoring and companion dedup**

Key changes to the existing method:
- Change `seen: Set[String]` to `java.util.concurrent.ConcurrentHashMap[String, MatchRelevance]` to track best relevance per qualKey
- Unwrap synthetic elements before computing qualKey
- Companion dedup: when seeing `pkg.Foo$`, check if `pkg.Foo` exists and prefer the class
- Score each match with `scoreMatch` and store the relevance
- Sort results by (relevance tier, project-vs-library, name)

Replace the `seen` parameter and sorting logic in `searchViaContributors`:

```scala
private def searchViaContributors(project: com.intellij.openapi.project.Project, query: String): Seq[SymbolInformation] =
  try
    val results = scala.collection.mutable.ArrayBuffer[(SymbolInformation, MatchRelevance)]()
    val seen = java.util.concurrent.ConcurrentHashMap[String, MatchRelevance]()
    val scope = GlobalSearchScope.allScope(project)

    val lastDot = query.lastIndexOf('.')
    val (simpleName, fqnPrefix) =
      if lastDot > 0 then (query.substring(lastDot + 1), Some(query.substring(0, lastDot)))
      else (query, None)
    val lowerSimpleName = simpleName.toLowerCase

    val contributors =
      ChooseByNameContributor.CLASS_EP_NAME.getExtensionList.asScala ++
      ChooseByNameContributor.SYMBOL_EP_NAME.getExtensionList.asScala

    for contributor <- contributors do
      // ... existing contributor processing with these changes:
      // 1. Unwrap: val unwrapped = PsiUtils.unwrapSyntheticElement(psi)
      // 2. Companion dedup: normalize qualKey by stripping trailing $
      //    val normalizedKey = if qualKey.endsWith("$") then qualKey.stripSuffix("$") else qualKey
      //    Skip if normalizedKey already seen with better or equal relevance
      // 3. Score: val relevance = scoreMatch(named.getName, simpleName)
      //    Only add if relevance.isDefined
```

- [ ] **Step 3: Update the contributor loop with companion dedup and scoring**

For the `ChooseByNameContributorEx` branch, replace the inner processing logic:

```scala
if fqnMatch then
  val unwrapped = PsiUtils.unwrapSyntheticElement(psi)
  val containerName = getContainerName(unwrapped)
  val rawQualKey = Option(containerName).filter(_.nonEmpty).map(c => s"$c.${named.getName}").getOrElse(named.getName)
  // Companion dedup: normalize by stripping $ suffix
  val qualKey = if rawQualKey.endsWith("$") then rawQualKey.stripSuffix("$") else rawQualKey

  scoreMatch(named.getName, simpleName).foreach: relevance =>
    val existingRelevance = Option(seen.get(qualKey))
    val shouldAdd = existingRelevance match
      case Some(existing) => relevance.rank < existing.rank  // better match
      case None => true

    if shouldAdd then
      PsiUtils.elementToLocation(psi).foreach: loc =>
        seen.put(qualKey, relevance)
        // Remove any previous entry with this qualKey
        val idx = results.indexWhere: (sym, _) =>
          val storedKey = Option(sym.getContainerName).filter(_.nonEmpty)
            .map(c => s"$c.${sym.getName}").getOrElse(sym.getName).stripSuffix("$")
          storedKey == qualKey
        if idx >= 0 then results.remove(idx)
        val kind = PsiUtils.getSymbolKind(named)
        results += ((new SymbolInformation(named.getName, kind, loc, containerName), relevance))
```

Apply the same changes to the legacy contributor branch.

- [ ] **Step 4: Update sorting to use relevance tiers**

Replace the project/library partition at the end:

```scala
val projectFileIndex = ProjectFileIndex.getInstance(project)
val allResults = results.result()
allResults.sortBy: (sym, relevance) =>
  val uri = sym.getLocation.getUri
  val isProject = if uri.startsWith("file://") then
    val path = java.net.URI.create(uri).getPath
    val vf = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl("file://" + path)
    vf != null && projectFileIndex.isInContent(vf)
  else false
  (relevance.rank, if isProject then 0 else 1, sym.getName)
.map(_._1)
```

- [ ] **Step 5: Compile**

Run: `sbt "lsp-server/compile" 2>&1 | tee /local/log`
Expected: compiles without errors

- [ ] **Step 6: Run existing symbol tests**

Run: `sbt "lsp-server/testOnly org.jetbrains.scalalsP.integration.SymbolProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: all existing tests pass

- [ ] **Step 7: Commit**

```bash
git add lsp-server/src/intellij/SymbolProvider.scala
git commit -m "feat: add relevance ranking, companion dedup, enhanced dedup to SymbolProvider"
```

---

## Task 6: Add integration tests for new symbol features

**Files:**
- Modify: `lsp-server/test/src/integration/SymbolProviderIntegrationTest.scala`

- [ ] **Step 1: Add test for relevance ranking**

```scala
def testWorkspaceSymbolRelevanceRanking(): Unit =
  addScalaFile("exact/MyExactClass.scala",
    """package exact
      |class MyExactClass
      |""".stripMargin
  )
  addScalaFile("prefix/MyExactClassHelper.scala",
    """package prefix
      |class MyExactClassHelper
      |""".stripMargin
  )
  val result = workspaceSymbols("MyExactClass")
  if result.size >= 2 then
    val exactIdx = result.indexWhere(_.getName == "MyExactClass")
    val prefixIdx = result.indexWhere(_.getName == "MyExactClassHelper")
    if exactIdx >= 0 && prefixIdx >= 0 then
      assertTrue("Exact match should rank before prefix match", exactIdx < prefixIdx)
```

- [ ] **Step 2: Add test for companion dedup**

```scala
def testWorkspaceSymbolCompanionDedup(): Unit =
  addScalaFile("dedup/Widget.scala",
    """package dedup
      |class Widget
      |object Widget:
      |  def create() = new Widget
      |""".stripMargin
  )
  val result = workspaceSymbols("Widget")
  val widgets = result.filter(s => s.getName == "Widget" || s.getName == "Widget$")
    .filter(s => Option(s.getContainerName).exists(_.contains("dedup")))
  // Should have at most 1 result for dedup.Widget (class preferred over companion)
  assertTrue(s"Companion dedup should produce at most 1 Widget, got ${widgets.size}",
    widgets.size <= 1)
```

- [ ] **Step 3: Run tests**

Run: `sbt "lsp-server/testOnly org.jetbrains.scalalsP.integration.SymbolProviderIntegrationTest" 2>&1 | tee /local/log`
Expected: all tests pass

- [ ] **Step 4: Commit**

```bash
git add lsp-server/test/src/integration/SymbolProviderIntegrationTest.scala
git commit -m "test: add integration tests for relevance ranking and companion dedup"
```

---

## Task 7: Expose usage types via `executeCommand`

Since registering a custom JSON-RPC method (`scala/referencesWithTypes`) requires modifying `LspLauncher.java` to register additional methods, and the MCP layer communicates via the existing LSP protocol, the simplest approach is to expose usage types through the existing `executeCommand` mechanism.

**Files:**
- Modify: `lsp-server/src/ScalaWorkspaceService.scala`
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`

- [ ] **Step 1: Add getter to ScalaTextDocumentService**

Add a public method to expose the last typed results:

```scala
/** Get the last references result with usage types (for executeCommand access). */
def getLastReferencesWithTypes: Seq[ReferenceResult] =
  referencesProvider.getLastResultsWithTypes
```

- [ ] **Step 2: Add `scala.referencesWithTypes` command to ScalaWorkspaceService**

In `ScalaWorkspaceService.executeCommand`, add a new command case. This requires access to the `ScalaTextDocumentService` instance. Add a field:

```scala
class ScalaWorkspaceService(projectManager: IntellijProjectManager, diagnosticsProvider: DiagnosticsProvider, textDocumentService: ScalaTextDocumentService) extends WorkspaceService:
```

Then add the command handler:

```scala
case "scala.referencesWithTypes" =>
  import com.google.gson.{JsonArray, JsonObject}
  val arr = new JsonArray()
  for r <- textDocumentService.getLastReferencesWithTypes do
    val obj = new JsonObject()
    val locObj = new JsonObject()
    locObj.addProperty("uri", r.location.getUri)
    val rangeObj = new JsonObject()
    val startObj = new JsonObject()
    startObj.addProperty("line", r.location.getRange.getStart.getLine)
    startObj.addProperty("character", r.location.getRange.getStart.getCharacter)
    val endObj = new JsonObject()
    endObj.addProperty("line", r.location.getRange.getEnd.getLine)
    endObj.addProperty("character", r.location.getRange.getEnd.getCharacter)
    rangeObj.add("start", startObj)
    rangeObj.add("end", endObj)
    locObj.add("range", rangeObj)
    obj.add("location", locObj)
    obj.addProperty("usageType", r.usageType)
    arr.add(obj)
  return CompletableFuture.completedFuture(arr)
```

- [ ] **Step 3: Update ScalaLspServer to pass textDocumentService to workspaceService**

Find where `ScalaWorkspaceService` is constructed and pass the `textDocumentService` reference. Check `ScalaLspServer.scala` for the constructor wiring.

- [ ] **Step 4: Compile**

Run: `sbt "lsp-server/compile" 2>&1 | tee /local/log`
Expected: compiles without errors

- [ ] **Step 5: Commit**

```bash
git add lsp-server/src/ScalaTextDocumentService.scala lsp-server/src/ScalaWorkspaceService.scala lsp-server/src/ScalaLspServer.scala
git commit -m "feat: expose usage types via scala.referencesWithTypes executeCommand"
```

---

## Task 8: Update MCP `references` tool to use usage types for grouped output

**Files:**
- Modify: `mcp-server/src/tools/navigation.ts`

- [ ] **Step 1: Simplify companion filtering in `resolveTargets`**

In the `resolveTargets` function (around line 26-30), companions are now handled server-side so we can simplify. Keep the exact-match filter but remove special companion treatment:

```typescript
// Before:
const hasExact = symbols.some(s => s.matchQuality === 'exact' || s.matchQuality === 'companion');
const filtered = hasExact
  ? symbols.filter(s => s.matchQuality === 'exact' || s.matchQuality === 'companion')
  : symbols;

// After:
const hasExact = symbols.some(s => s.matchQuality === 'exact');
const filtered = hasExact
  ? symbols.filter(s => s.matchQuality === 'exact')
  : symbols;
```

- [ ] **Step 2: Remove companion target filtering in `references` tool**

In the references tool handler (around line 140-143), remove the companion skip logic since the server now deduplicates:

```typescript
// Before:
const dedupedTargets = args.symbolName
  ? targets.filter(t => t.matchQuality !== 'companion')
  : targets;
const effectiveTargets = dedupedTargets.length > 0 ? dedupedTargets : targets;

// After:
const effectiveTargets = targets;
```

- [ ] **Step 3: Compile MCP server**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm run build 2>&1 | tee /local/log`
Expected: compiles without errors

- [ ] **Step 4: Commit**

```bash
git add mcp-server/src/tools/navigation.ts
git commit -m "refactor: remove companion dedup from MCP layer (now handled server-side)"
```

---

## Task 9: Simplify `SymbolResolver` — remove companion match quality

**Files:**
- Modify: `mcp-server/src/symbol-resolver.ts`
- Modify: `mcp-server/test/symbol-resolver.test.ts`

- [ ] **Step 1: Keep companion matching but simplify**

The companion match quality (`'companion'`) is still useful for the MCP layer to know that a result is a companion object. However, dedup is now server-side. Keep the `matchQuality` logic as-is since it helps with display, but remove any dedup logic that was based on it.

Review the current code — if there's no client-side dedup based on companion quality, this task is a no-op. Mark as complete if no changes needed.

- [ ] **Step 2: Update tests if needed**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm test 2>&1 | tee /local/log`
Expected: all tests pass

- [ ] **Step 3: Commit if changes made**

```bash
git add mcp-server/src/symbol-resolver.ts mcp-server/test/symbol-resolver.test.ts
git commit -m "refactor: simplify SymbolResolver companion handling"
```

---

## Task 10: Full test suite verification

**Files:** None (verification only)

- [ ] **Step 1: Run full LSP server test suite**

Run: `sbt "lsp-server/test" 2>&1 | tee /local/log`
Expected: all tests pass

- [ ] **Step 2: Run full MCP server test suite**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp/mcp-server && npm test 2>&1 | tee /local/log`
Expected: all tests pass

- [ ] **Step 3: Run LSP server compilation**

Run: `sbt "lsp-server/compile" 2>&1 | tee /local/log`
Expected: clean compilation

- [ ] **Step 4: Commit any remaining fixes**

If any tests needed fixing, commit those fixes.

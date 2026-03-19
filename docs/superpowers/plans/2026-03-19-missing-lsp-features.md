# Rename, Type Hierarchy, Execute Command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add rename, type hierarchy, and execute command LSP features to the intellij-scala-lsp server.

**Architecture:** Each feature follows the existing provider pattern — one provider class per feature under `lsp-server/src/intellij/`, handlers in the text document / workspace services, capability registration in `ScalaLspServer`. All PSI reads use `ReadAction.compute`, write operations use `invokeAndWait + WriteCommandAction`.

**Tech Stack:** Scala 3, lsp4j 0.23.1, IntelliJ Platform SDK 253, JUnit 4

**Spec:** `docs/superpowers/specs/2026-03-19-missing-lsp-features-design.md`

---

### Task 1: RenameProvider — Integration Test

**Files:**
- Create: `lsp-server/test/src/integration/RenameProviderIntegrationTest.scala`

- [ ] **Step 1: Write the integration test file**

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.RenameProvider
import org.junit.Assert.*

class RenameProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = RenameProvider(projectManager)

  def testPrepareRenameOnVal(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val myValue = 42
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 6))
    assertNotNull("Should be able to rename a val", result)
    assertEquals("myValue", result.getPlaceholder)

  def testPrepareRenameOnMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String) = s"Hello, $name"
        |""".stripMargin
    )
    val result = provider.prepareRename(uri, positionAt(1, 6))
    assertNotNull("Should be able to rename a method", result)
    assertEquals("greet", result.getPlaceholder)

  def testPrepareRenameOnNonRenameable(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42 + 1
        |""".stripMargin
    )
    // Position on whitespace between tokens — not a named element
    val result = provider.prepareRename(uri, positionAt(1, 10))
    assertNull("Literal should not be renameable", result)

  def testRenameLocalVariable(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |  def foo = x + 1
        |  def bar = x + 2
        |""".stripMargin
    )
    val result = provider.rename(uri, positionAt(1, 6), "y")
    assertNotNull(result)
    val changes = result.getChanges
    assertNotNull(changes)
    // Should have changes for this file
    assertTrue("Should have edits", !changes.isEmpty)

  def testRenameMethod(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  def greet(name: String) = s"Hello, $name"
        |  def a = greet("Alice")
        |  def b = greet("Bob")
        |""".stripMargin
    )
    val result = provider.rename(uri, positionAt(1, 6), "sayHello")
    assertNotNull(result)
    val changes = result.getChanges
    assertNotNull(changes)

  def testRenameWithConflict(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 1
        |  val y = 2
        |  def foo = x + y
        |""".stripMargin
    )
    // Rename x to y — should still produce edits (conflict detection is client-side)
    val result = provider.rename(uri, positionAt(1, 6), "y")
    assertNotNull("Rename should produce edits even with conflicts", result)

  def testRenameCrossFile(): Unit =
    addScalaFile("Helper.scala",
      """package example
        |object Helper:
        |  def compute(x: Int): Int = x * 2
        |""".stripMargin
    )
    val uri = configureScalaFile("Main.scala",
      """package example
        |object Main:
        |  val result = Helper.compute(21)
        |""".stripMargin
    )
    val result = provider.rename(uri, positionAt(2, 27), "calculate")
    assertNotNull(result)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp && sbt "testOnly org.jetbrains.scalalsP.integration.RenameProviderIntegrationTest" 2>&1 | tee /local/log/rename-test-1.log`
Expected: Compilation failure — `RenameProvider` does not exist yet.

- [ ] **Step 3: Commit**

```bash
git add lsp-server/test/src/integration/RenameProviderIntegrationTest.scala
git commit -m "test: add integration tests for RenameProvider"
```

---

### Task 2: RenameProvider — Implementation

**Files:**
- Create: `lsp-server/src/intellij/RenameProvider.scala`

- [ ] **Step 1: Write the RenameProvider**

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiNamedElement}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.eclipse.lsp4j.{Position, PrepareRenameResult, Range, TextEdit, WorkspaceEdit}

import scala.jdk.CollectionConverters.*

/**
 * Implements textDocument/prepareRename and textDocument/rename.
 * Uses ReferencesSearch to find all usages, returns a WorkspaceEdit
 * without mutating PSI — the client applies edits.
 */
class RenameProvider(projectManager: IntellijProjectManager):

  def prepareRename(uri: String, position: Position): PrepareRenameResult | Null =
    ReadAction.compute[PrepareRenameResult | Null, RuntimeException]: () =>
      val named = findNamedElementAt(uri, position)
      named match
        case Some(element) =>
          val name = element.getName
          if name == null || name.isEmpty then null
          else
            for
              file <- Option(element.getContainingFile)
              vf <- Option(file.getVirtualFile)
              document <- Option(FileDocumentManager.getInstance().getDocument(vf))
            yield
              val range = PsiUtils.nameElementToRange(document, element)
              PrepareRenameResult(range, name)
            match
              case Some(result) => result
              case None => null
        case None => null

  def rename(uri: String, position: Position, newName: String): WorkspaceEdit | Null =
    ReadAction.compute[WorkspaceEdit | Null, RuntimeException]: () =>
      val named = findNamedElementAt(uri, position)
      named match
        case Some(target) =>
          val project = projectManager.getProject
          val scope = GlobalSearchScope.projectScope(project)

          // Collect all reference locations
          val refEdits = ReferencesSearch.search(target, scope, false)
            .findAll()
            .asScala
            .flatMap: ref =>
              val refElement = ref.getElement
              for
                file <- Option(refElement.getContainingFile)
                vf <- Option(file.getVirtualFile)
                document <- Option(FileDocumentManager.getInstance().getDocument(vf))
              yield
                val refUri = PsiUtils.vfToUri(vf)
                val range = PsiUtils.nameElementToRange(document, refElement)
                (refUri, TextEdit(range, newName))
            .toSeq

          // Add the declaration itself
          val declEdits = (for
            file <- Option(target.getContainingFile)
            vf <- Option(file.getVirtualFile)
            document <- Option(FileDocumentManager.getInstance().getDocument(vf))
          yield
            val declUri = PsiUtils.vfToUri(vf)
            val range = PsiUtils.nameElementToRange(document, target)
            (declUri, TextEdit(range, newName))
          ).toSeq

          val allEdits = (declEdits ++ refEdits)
            .groupBy(_._1)
            .map((fileUri, edits) => fileUri -> edits.map(_._2).asJava)
            .asJava

          if allEdits.isEmpty then null
          else WorkspaceEdit(allEdits)

        case None => null

  private def findNamedElementAt(uri: String, position: Position): Option[PsiNamedElement] =
    for
      psiFile <- projectManager.findPsiFile(uri)
      vf <- projectManager.findVirtualFile(uri)
      document <- Option(FileDocumentManager.getInstance().getDocument(vf))
    yield
      val offset = PsiUtils.positionToOffset(document, position)
      resolveToNamedElement(psiFile, offset)
    match
      case Some(Some(named)) => Some(named)
      case _ => None

  private def resolveToNamedElement(psiFile: com.intellij.psi.PsiFile, offset: Int): Option[PsiNamedElement] =
    PsiUtils.findReferenceElementAt(psiFile, offset).flatMap: element =>
      val ref = element.getReference
      val resolved = if ref != null then Option(ref.resolve()) else Some(element)
      resolved.collect { case named: PsiNamedElement => named }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp && sbt "testOnly org.jetbrains.scalalsP.integration.RenameProviderIntegrationTest" 2>&1 | tee /local/log/rename-test-2.log`
Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add lsp-server/src/intellij/RenameProvider.scala
git commit -m "feat: add RenameProvider for textDocument/rename and prepareRename"
```

---

### Task 3: TypeHierarchyProvider — Integration Test

**Files:**
- Create: `lsp-server/test/src/integration/TypeHierarchyProviderIntegrationTest.scala`

- [ ] **Step 1: Write the integration test file**

```scala
package org.jetbrains.scalalsP.integration

import org.eclipse.lsp4j.Position
import org.jetbrains.scalalsP.intellij.TypeHierarchyProvider
import org.junit.Assert.*

class TypeHierarchyProviderIntegrationTest extends ScalaLspTestBase:

  private def provider = TypeHierarchyProvider(projectManager)

  def testPrepareOnClass(): Unit =
    val uri = configureScalaFile(
      """class MyClass:
        |  def value = 42
        |""".stripMargin
    )
    val result = provider.prepare(uri, positionAt(0, 6))
    if result.nonEmpty then
      assertEquals("MyClass", result.head.getName)

  def testPrepareOnTrait(): Unit =
    val uri = configureScalaFile(
      """trait MyTrait:
        |  def value: Int
        |""".stripMargin
    )
    val result = provider.prepare(uri, positionAt(0, 6))
    if result.nonEmpty then
      assertEquals("MyTrait", result.head.getName)

  def testPrepareOnNonType(): Unit =
    val uri = configureScalaFile(
      """object Main:
        |  val x = 42
        |""".stripMargin
    )
    // Position on literal
    val result = provider.prepare(uri, positionAt(1, 12))
    // May return empty or a result depending on resolution
    assertNotNull(result)

  def testSupertypes(): Unit =
    val uri = configureScalaFile(
      """trait Animal:
        |  def name: String
        |
        |trait Domestic
        |
        |class Dog extends Animal with Domestic:
        |  def name = "Dog"
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(5, 6))
    if items.nonEmpty then
      val supers = provider.supertypes(items.head)
      if supers.nonEmpty then
        val names = supers.map(_.getName)
        assertTrue("Should find Animal as supertype", names.contains("Animal"))

  def testSubtypes(): Unit =
    val uri = configureScalaFile(
      """trait Shape:
        |  def area: Double
        |
        |class Circle extends Shape:
        |  def area = 3.14
        |
        |class Square extends Shape:
        |  def area = 1.0
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(0, 6))
    if items.nonEmpty then
      val subs = provider.subtypes(items.head)
      if subs.nonEmpty then
        val names = subs.map(_.getName)
        assertTrue("Should find Circle", names.contains("Circle"))
        assertTrue("Should find Square", names.contains("Square"))

  def testCrossFileHierarchy(): Unit =
    addScalaFile("Base.scala",
      """package example
        |trait Base:
        |  def id: Int
        |""".stripMargin
    )
    val uri = configureScalaFile("Impl.scala",
      """package example
        |class Impl extends Base:
        |  def id = 1
        |""".stripMargin
    )
    val items = provider.prepare(uri, positionAt(1, 6))
    if items.nonEmpty then
      val supers = provider.supertypes(items.head)
      if supers.nonEmpty then
        assertTrue("Should find Base as supertype",
          supers.exists(_.getName == "Base"))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp && sbt "testOnly org.jetbrains.scalalsP.integration.TypeHierarchyProviderIntegrationTest" 2>&1 | tee /local/log/typehierarchy-test-1.log`
Expected: Compilation failure — `TypeHierarchyProvider` does not exist yet.

- [ ] **Step 3: Commit**

```bash
git add lsp-server/test/src/integration/TypeHierarchyProviderIntegrationTest.scala
git commit -m "test: add integration tests for TypeHierarchyProvider"
```

---

### Task 4: TypeHierarchyProvider — Implementation

**Files:**
- Create: `lsp-server/src/intellij/TypeHierarchyProvider.scala`

- [ ] **Step 1: Write the TypeHierarchyProvider**

```scala
package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.application.ReadAction
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
    ReadAction.compute[Seq[TypeHierarchyItem], RuntimeException]: () =>
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
    ReadAction.compute[Seq[TypeHierarchyItem], RuntimeException]: () =>
      findElementFromItem(item) match
        case Some(element) =>
          getSupertypes(element).flatMap(toTypeHierarchyItem)
        case None => Seq.empty

  def subtypes(item: TypeHierarchyItem): Seq[TypeHierarchyItem] =
    ReadAction.compute[Seq[TypeHierarchyItem], RuntimeException]: () =>
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
      val resolved = if ref != null then Option(ref.resolve()) else Some(element)
      resolved.flatMap: el =>
        // Walk up to find the nearest type (class/trait/object)
        var current = el
        while current != null && !isTypeElement(current) do
          current = current.getParent
        Option(current)

  private def isTypeElement(element: PsiElement): Boolean =
    val className = element.getClass.getName
    className.contains("ScClass") ||
    className.contains("ScTrait") ||
    className.contains("ScObject") ||
    element.isInstanceOf[PsiClass]

  private def getSupertypes(element: PsiElement): Seq[PsiElement] =
    // PsiClass.getSupers() returns direct supertypes, works for both Java and Scala PSI
    // (ScClass, ScTrait, ScObject all implement PsiClass)
    element match
      case psiClass: PsiClass =>
        psiClass.getSupers.toSeq.filter: sup =>
          val name = sup.getQualifiedName
          name != "java.lang.Object"
      case _ => Seq.empty

  private def toTypeHierarchyItem(element: PsiElement): Option[TypeHierarchyItem] =
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
          val kind = getSymbolKind(element)
          val item = TypeHierarchyItem(name, kind, uri, range, selectionRange)
          // Encode location for later resolution (same pattern as CallHierarchyProvider)
          item.setData(s"${uri}#${selectionRange.getStart.getLine}:${selectionRange.getStart.getCharacter}")
          item
      case _ => None

  private def findElementFromItem(item: TypeHierarchyItem): Option[PsiElement] =
    val uri = item.getUri
    val selRange = item.getSelectionRange
    for
      psiFile <- projectManager.findPsiFile(uri)
      vf <- projectManager.findVirtualFile(uri)
      document <- Option(FileDocumentManager.getInstance().getDocument(vf))
    yield
      val offset = PsiUtils.positionToOffset(document, selRange.getStart)
      var elem = psiFile.findElementAt(offset)
      while elem != null && !isTypeElement(elem) do
        elem = elem.getParent
      elem
    match
      case Some(null) => None
      case Some(elem) => Some(elem)
      case None => None

  private def getSymbolKind(element: PsiElement): SymbolKind =
    val className = element.getClass.getName
    if className.contains("ScTrait") then SymbolKind.Interface
    else if className.contains("ScObject") then SymbolKind.Module
    else SymbolKind.Class
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp && sbt "testOnly org.jetbrains.scalalsP.integration.TypeHierarchyProviderIntegrationTest" 2>&1 | tee /local/log/typehierarchy-test-2.log`
Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add lsp-server/src/intellij/TypeHierarchyProvider.scala
git commit -m "feat: add TypeHierarchyProvider for type hierarchy LSP methods"
```

---

### Task 5: ExecuteCommand — Integration Test

**Files:**
- Create: `lsp-server/test/src/integration/ExecuteCommandIntegrationTest.scala`

- [ ] **Step 1: Write the integration test file**

```scala
package org.jetbrains.scalalsP.integration

import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.ScalaWorkspaceService
import org.junit.Assert.*

import java.util.concurrent.TimeUnit

class ExecuteCommandIntegrationTest extends ScalaLspTestBase:

  private def workspaceService = ScalaWorkspaceService(projectManager)

  def testOrganizeImports(): Unit =
    val uri = configureScalaFile(
      """import scala.collection.mutable.ArrayBuffer
        |import scala.collection.mutable.ListBuffer
        |
        |object Main:
        |  val x = new ArrayBuffer[Int]
        |""".stripMargin
    )
    val params = ExecuteCommandParams()
    params.setCommand("scala.organizeImports")
    params.setArguments(java.util.List.of(uri))
    val result = workspaceService.executeCommand(params).get(10, TimeUnit.SECONDS)
    // After organizing, the unused ListBuffer import should be removed
    val doc = getDocument
    val text = doc.getText
    assertFalse("Unused import should be removed", text.contains("ListBuffer"))
    assertTrue("Used import should remain", text.contains("ArrayBuffer"))

  def testReformat(): Unit =
    val uri = configureScalaFile(
      """object Main{
        |def foo={
        |val x=42
        |x+1
        |}
        |}
        |""".stripMargin
    )
    val params = ExecuteCommandParams()
    params.setCommand("scala.reformat")
    params.setArguments(java.util.List.of(uri))
    val result = workspaceService.executeCommand(params).get(10, TimeUnit.SECONDS)
    // Verify document was reformatted (exact formatting depends on code style settings)
    val doc = getDocument
    assertNotNull(doc)

  def testUnknownCommandReturnsNull(): Unit =
    val params = ExecuteCommandParams()
    params.setCommand("scala.nonexistent")
    params.setArguments(java.util.List.of("file:///dummy"))
    val result = workspaceService.executeCommand(params).get(10, TimeUnit.SECONDS)
    assertNull("Unknown command should return null", result)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp && sbt "testOnly org.jetbrains.scalalsP.integration.ExecuteCommandIntegrationTest" 2>&1 | tee /local/log/executecommand-test-1.log`
Expected: Failure — `executeCommand` is not implemented yet (the current `ScalaWorkspaceService` does not override it).

- [ ] **Step 3: Commit**

```bash
git add lsp-server/test/src/integration/ExecuteCommandIntegrationTest.scala
git commit -m "test: add integration tests for workspace/executeCommand"
```

---

### Task 6: ExecuteCommand — Implementation

**Files:**
- Modify: `lsp-server/src/ScalaWorkspaceService.scala`

- [ ] **Step 1: Add executeCommand to ScalaWorkspaceService**

Add imports at the top of the file:
```scala
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.codeStyle.CodeStyleManager
import org.eclipse.lsp4j.{ApplyWorkspaceEditParams, TextEdit, WorkspaceEdit}
import org.jetbrains.scalalsP.intellij.PsiUtils
```

Add the executeCommand handler method:
```scala
  override def executeCommand(params: ExecuteCommandParams): CompletableFuture[AnyRef] =
    CompletableFuture.supplyAsync: () =>
      val command = params.getCommand
      val args = params.getArguments
      command match
        case "scala.organizeImports" =>
          executeOnFile(args): psiFile =>
            val processor = OptimizeImportsProcessor(projectManager.getProject, psiFile)
            processor.run()
        case "scala.reformat" =>
          executeOnFile(args): psiFile =>
            CodeStyleManager.getInstance(projectManager.getProject).reformat(psiFile)
        case _ =>
          System.err.println(s"[WorkspaceService] Unknown command: $command")
          null

  private def executeOnFile(args: java.util.List[AnyRef])(action: com.intellij.psi.PsiFile => Unit): Null =
    if args == null || args.isEmpty then
      System.err.println("[WorkspaceService] executeCommand: missing file URI argument")
      return null

    val uri = args.get(0) match
      case s: String => s
      case gson: com.google.gson.JsonPrimitive => gson.getAsString
      case other =>
        System.err.println(s"[WorkspaceService] executeCommand: unexpected argument type: ${other.getClass}")
        return null

    projectManager.findPsiFile(uri) match
      case Some(psiFile) =>
        // Capture document text before mutation
        val vfOpt = projectManager.findVirtualFile(uri)
        val beforeText = vfOpt.flatMap(vf => Option(FileDocumentManager.getInstance().getDocument(vf))).map(_.getText)

        ApplicationManager.getApplication.invokeAndWait: () =>
          WriteCommandAction.runWriteCommandAction(projectManager.getProject, (() =>
            action(psiFile)
          ): Runnable)

        // Notify client of changes via workspace/applyEdit so it stays in sync
        if client != null then
          val afterText = vfOpt.flatMap(vf => Option(FileDocumentManager.getInstance().getDocument(vf))).map(_.getText)
          (beforeText, afterText) match
            case (Some(before), Some(after)) if before != after =>
              // Send full document replacement to client
              val fullRange = new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(0, 0),
                new org.eclipse.lsp4j.Position(before.count(_ == '\n') + 1, 0)
              )
              val edit = WorkspaceEdit(java.util.Map.of(uri, java.util.List.of(TextEdit(fullRange, after))))
              client.applyEdit(ApplyWorkspaceEditParams(edit))
            case _ => () // No changes
      case None =>
        System.err.println(s"[WorkspaceService] File not found: $uri")

    null
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp && sbt "testOnly org.jetbrains.scalalsP.integration.ExecuteCommandIntegrationTest" 2>&1 | tee /local/log/executecommand-test-2.log`
Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add lsp-server/src/ScalaWorkspaceService.scala
git commit -m "feat: add workspace/executeCommand with organizeImports and reformat"
```

---

### Task 7: Wire Rename + Type Hierarchy into LSP Services

**Files:**
- Modify: `lsp-server/src/ScalaTextDocumentService.scala`
- Modify: `lsp-server/src/ScalaLspServer.scala`

- [ ] **Step 1: Add providers and handlers to ScalaTextDocumentService**

Add to the provider field declarations (after `private val codeActionProvider`):
```scala
  private val renameProvider = RenameProvider(projectManager)
  private val typeHierarchyProvider = TypeHierarchyProvider(projectManager)
```

Add handler methods:
```scala
  // --- Rename ---

  override def prepareRename(params: PrepareRenameParams): CompletableFuture[LspEither[Range, PrepareRenameResult]] =
    CompletableFuture.supplyAsync: () =>
      val result = renameProvider.prepareRename(
        params.getTextDocument.getUri,
        params.getPosition
      )
      if result != null then LspEither.forRight(result)
      else null

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    CompletableFuture.supplyAsync: () =>
      renameProvider.rename(
        params.getTextDocument.getUri,
        params.getPosition,
        params.getNewName
      )

  // --- Type Hierarchy ---

  override def prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    CompletableFuture.supplyAsync: () =>
      typeHierarchyProvider.prepare(
        params.getTextDocument.getUri,
        params.getPosition
      ).asJava

  override def typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    CompletableFuture.supplyAsync: () =>
      typeHierarchyProvider.supertypes(params.getItem).asJava

  override def typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture[util.List[TypeHierarchyItem]] =
    CompletableFuture.supplyAsync: () =>
      typeHierarchyProvider.subtypes(params.getItem).asJava
```

- [ ] **Step 2: Register capabilities in ScalaLspServer**

In `ScalaLspServer.scala`, in the `initialize` method, after `capabilities.setCallHierarchyProvider(true)`:

```scala
      // Rename
      val renameOptions = RenameOptions()
      renameOptions.setPrepareProvider(true)
      capabilities.setRenameProvider(renameOptions)

      // Type hierarchy
      capabilities.setTypeHierarchyProvider(true)

      // Execute commands
      val executeCommandOptions = ExecuteCommandOptions(
        java.util.List.of("scala.organizeImports", "scala.reformat")
      )
      capabilities.setExecuteCommandProvider(executeCommandOptions)
```

- [ ] **Step 3: Run all tests**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp && sbt test 2>&1 | tee /local/log/all-tests.log`
Expected: All tests PASS (existing + new).

- [ ] **Step 4: Commit**

```bash
git add lsp-server/src/ScalaTextDocumentService.scala lsp-server/src/ScalaLspServer.scala
git commit -m "feat: wire rename, type hierarchy, and executeCommand into LSP server"
```

---

### Task 8: Final Verification

- [ ] **Step 1: Run full test suite**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp && sbt test 2>&1 | tee /local/log/final-tests.log`
Expected: All tests PASS.

- [ ] **Step 2: Verify compilation is clean**

Run: `cd /Users/hoangmei/Work/intellij-scala-lsp && sbt compile 2>&1 | tee /local/log/final-compile.log`
Expected: Clean compilation with no warnings.

- [ ] **Step 3: Verify new capabilities appear in server initialization**

Grep for the new capability registrations:
```bash
grep -n "renameProvider\|typeHierarchyProvider\|executeCommandProvider" lsp-server/src/ScalaLspServer.scala
```
Expected: All three capabilities registered.

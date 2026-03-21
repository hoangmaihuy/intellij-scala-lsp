package org.jetbrains.scalalsP.integration

import org.junit.Assert.*

/**
 * Verifies that the LSP server can be instantiated with direct Scala plugin
 * type imports without classloader errors.
 *
 * The launcher script must include Scala plugin JARs (scalaCommunity.jar etc.)
 * and Java plugin JARs (java-impl-frontend.jar etc.) on the classpath so that
 * all classes in the type hierarchy are resolvable.
 */
class ClassloaderSafetyTest extends ScalaLspTestBase:

  def testScalaLspServerInstantiatesWithoutClassloaderErrors(): Unit =
    // This exercises the full chain: ScalaLspServer → ScalaTextDocumentService
    // → all providers (CallHierarchyProvider, InlayHintProvider, RenameProvider, etc.)
    // If any provider's Scala plugin imports can't be resolved, this will throw
    // NoClassDefFoundError / ClassNotFoundException.
    val server = org.jetbrains.scalalsP.ScalaLspServer(getProject.getBasePath, projectManager)
    assertNotNull("ScalaLspServer should instantiate successfully", server)
    assertNotNull("TextDocumentService should be available", server.getTextDocumentService)
    assertNotNull("WorkspaceService should be available", server.getWorkspaceService)

  def testLauncherScriptIncludesPluginJars(): Unit =
    val launcherPath = System.getProperty("user.dir") + "/launcher/launch-lsp.sh"
    val launcherFile = java.io.File(launcherPath)
    if !launcherFile.exists() then return

    val content = scala.io.Source.fromFile(launcherFile).mkString
    assertTrue(
      "launch-lsp.sh must add scalaCommunity.jar to the classpath",
      content.contains("scalaCommunity.jar")
    )
    assertTrue(
      "launch-lsp.sh must add java-impl-frontend.jar to the classpath (Scala plugin types extend Java PSI classes)",
      content.contains("java-impl-frontend.jar")
    )

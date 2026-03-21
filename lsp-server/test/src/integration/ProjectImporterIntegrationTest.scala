package org.jetbrains.scalalsP.integration

import org.junit.Assert.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Integration test for ProjectImporter's sbt reflection mechanism.
 *
 * Verifies that SbtOpenProjectProvider (from the Scala plugin) can be loaded
 * and instantiated via reflection, which is how ProjectImporter triggers
 * sbt project import.
 */
class ProjectImporterIntegrationTest extends ScalaLspTestBase:

  def testSbtOpenProjectProviderCanBeLoaded(): Unit =
    // Verify the reflection that ProjectImporter uses actually works.
    // Must load via the Scala plugin's classloader to avoid ClassCastException
    // when sbt-api classes exist in both plugin and LSP server classloaders.
    val pluginId = com.intellij.openapi.extensions.PluginId.getId("org.intellij.scala")
    val plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)
    assertNotNull("Scala plugin should be loaded", plugin)

    val pluginClassLoader = plugin.getPluginClassLoader
    val sbtProviderClass = Class.forName("org.jetbrains.sbt.project.SbtOpenProjectProvider", true, pluginClassLoader)
    assertNotNull("SbtOpenProjectProvider class should be loadable", sbtProviderClass)

    val provider = sbtProviderClass.getDeclaredConstructor().newInstance()
    assertNotNull("SbtOpenProjectProvider should be instantiable", provider)

    val doLinkMethod = sbtProviderClass.getMethod("doLinkProject",
      classOf[VirtualFile], classOf[Project])
    assertNotNull("doLinkProject method should exist", doLinkMethod)

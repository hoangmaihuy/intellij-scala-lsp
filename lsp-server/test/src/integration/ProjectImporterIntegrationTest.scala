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
    // Verify the reflection that ProjectImporter uses actually works
    val sbtProviderClass = Class.forName("org.jetbrains.sbt.project.SbtOpenProjectProvider")
    assertNotNull("SbtOpenProjectProvider class should be loadable", sbtProviderClass)

    val provider = sbtProviderClass.getDeclaredConstructor().newInstance()
    assertNotNull("SbtOpenProjectProvider should be instantiable", provider)

    val doLinkMethod = sbtProviderClass.getMethod("doLinkProject",
      classOf[VirtualFile], classOf[Project])
    assertNotNull("doLinkProject method should exist", doLinkMethod)

package org.jetbrains.scalalsP.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.{ModuleRootModificationUtil, OrderRootType}
import com.intellij.openapi.vfs.{JarFileSystem, VirtualFileManager}

import scala.jdk.CollectionConverters.*

/**
 * Test base that adds external library JARs (cats, zio) to the test module,
 * enabling workspace/symbol, definition, and references on external library symbols.
 *
 * The JARs come from the test classpath (added via build.sbt test dependencies).
 * We find them on the classpath and register them with the IntelliJ module.
 */
abstract class ExternalLibraryTestBase extends ScalaLspTestBase:

  override def setUp(): Unit =
    super.setUp()
    addClasspathLibraries()

  /** Find JARs from the test classpath matching the given prefixes and add them
    * to the IntelliJ test module as libraries. */
  private def addClasspathLibraries(): Unit =
    val classpath = System.getProperty("java.class.path", "")
    val entries = classpath.split(java.io.File.pathSeparator).toSeq

    // Find JARs for cats-core and zio
    val targetJars = entries.filter: entry =>
      val name = java.nio.file.Path.of(entry).getFileName.toString
      (name.startsWith("cats-core") || name.startsWith("cats-kernel") ||
       name.startsWith("zio_") || name.startsWith("zio-stacktracer")) &&
      name.endsWith(".jar")

    if targetJars.nonEmpty then
      ApplicationManager.getApplication.invokeAndWait: () =>
        ApplicationManager.getApplication.runWriteAction((() =>
          for jarPath <- targetJars do
            val jarUrl = s"jar://$jarPath!/"
            ModuleRootModificationUtil.addModuleLibrary(
              myFixture.getModule,
              jarUrl
            )
        ): Runnable)

      System.err.println(s"[ExternalLibraryTestBase] Added ${targetJars.size} JARs to module: ${targetJars.map(java.nio.file.Path.of(_).getFileName).mkString(", ")}")
    else
      System.err.println("[ExternalLibraryTestBase] WARNING: No cats/zio JARs found on classpath")

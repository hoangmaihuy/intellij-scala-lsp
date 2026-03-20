package org.jetbrains.scalalsP.e2e

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.scalalsP.BootstrapState
import org.junit.Assert.*

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.jdk.StreamConverters.*

/**
 * Wire protocol test that launches the server as a subprocess via java -cp.
 * Tests the full JSON-RPC lifecycle against both sbt and mill fixture projects.
 *
 * Requires: `sbt lsp-server/packageArtifact` has been run AND fixture projects built.
 */
class WireProtocolE2eTest extends BasePlatformTestCase:

  override def setUp(): Unit =
    super.setUp()
    BootstrapState.bootstrapComplete.countDown()

  private def buildLaunchCommand(fixtureDir: String): Option[List[String]] =
    val lspLibDir = Path.of("lsp-server/target/plugin/intellij-scala-lsp/lib")
    if !Files.exists(lspLibDir) then return None

    val sdkCandidates: List[String] = List("target/aarch64/idea", "target/idea")
      .flatMap(d => Option(Path.of(d).toFile.listFiles()).getOrElse(Array.empty[File]).toList)
      .flatMap { (d: File) =>
        val contents = Path.of(d.getAbsolutePath, "Contents")
        if Files.exists(contents.resolve("lib")) then List(contents.toString)
        else if Files.exists(Path.of(d.getAbsolutePath, "lib")) then List(d.getAbsolutePath)
        else Nil
      }
    val sdkDir: String = sdkCandidates.headOption.getOrElse(return None)

    val sdkJars: List[String] = Files.list(Path.of(sdkDir, "lib"))
      .filter(_.toString.endsWith(".jar")).toScala(List).map(_.toString)
    val lspJars: List[String] = Files.list(lspLibDir)
      .filter(_.toString.endsWith(".jar")).toScala(List).map(_.toString)

    val scalaPluginDir = List(
      Path.of(sdkDir, "custom-plugins", "Scala"),
      Path.of(sdkDir, "plugins", "Scala")
    ).find(p => Files.exists(p.resolve("lib"))).map(_.toString)

    val scalaRuntimeJars = scalaPluginDir.toList.flatMap { d =>
      List("scala-library.jar", "scala3-library_3.jar")
        .map(j => Path.of(d, "lib", j).toString)
        .filter(j => Files.exists(Path.of(j)))
    }

    val classpath = (sdkJars ++ lspJars ++ scalaRuntimeJars).mkString(File.pathSeparator)

    val java = List(
      Path.of(sdkDir, "jbr", "Contents", "Home", "bin", "java"),
      Path.of(sdkDir, "jbr", "bin", "java")
    ).find(Files.exists(_)).map(_.toString).getOrElse("java")

    val pluginPath = List(
      lspLibDir.getParent.toString,
      scalaPluginDir.getOrElse(""),
      Path.of(sdkDir, "plugins", "java").toString
    ).filter(_.nonEmpty).mkString(File.pathSeparator)

    Some(List(
      java, "-Xmx1g", "-Djava.awt.headless=true",
      s"-Didea.home.path=$sdkDir",
      s"-Dplugin.path=$pluginPath",
      "-cp", classpath,
      "org.jetbrains.scalalsP.ScalaLspMain",
      fixtureDir
    ))

  private def runProtocolTest(fixtureType: String): Unit =
    val fixtureDir = Path.of(s"lsp-server/test/resources/fixtures/$fixtureType-project")
      .toAbsolutePath.toString
    if !Files.exists(Path.of(fixtureDir)) then
      System.err.println(s"Skipping $fixtureType test — fixture not built")
      return

    val command = buildLaunchCommand(fixtureDir).getOrElse {
      System.err.println("Skipping wire protocol test — run 'sbt lsp-server/packageArtifact' first")
      return
    }

    val client = TestLspClient.subprocess(command)
    try
      val initResult = client.initialize(s"file://$fixtureDir")
      assertNotNull("Initialize should return result", initResult)
      assertEquals("intellij-scala-lsp", initResult.getServerInfo.getName)

      val shapeFile = Path.of(fixtureDir, "src", "main", "scala", "hierarchy", "Shape.scala")
      if Files.exists(shapeFile) then
        val shapeUri = shapeFile.toUri.toString
        val shapeContent = Files.readString(shapeFile)
        client.openFile(shapeUri, shapeContent)

        client.hover(shapeUri, line = 2, char = 13)
        // Verify no crash

        val symbols = client.documentSymbols(shapeUri)
        assertFalse("Should return symbols", symbols.isEmpty)

      client.shutdown()
    catch
      case e: Exception =>
        try client.shutdown() catch case _: Exception => ()
        throw e

  def testSbtFixture(): Unit = runProtocolTest("sbt")
  def testMillFixture(): Unit = runProtocolTest("mill")

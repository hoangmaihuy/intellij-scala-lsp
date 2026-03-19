package millbuild

import mill.api.PathRef
import os.Path

/**
 * Locates IntelliJ Community Edition SDK and Scala plugin JARs for compilation.
 *
 * Lookup order for IntelliJ:
 *   1. INTELLIJ_HOME environment variable
 *   2. Common macOS application directories
 *   3. ~/.cache/intellij-scala-lsp/idea-IC-{version}/
 *
 * Lookup order for Scala plugin:
 *   1. SCALA_PLUGIN_HOME environment variable
 *   2. Inside IntelliJ's plugins/ directory
 *   3. ~/.cache/intellij-scala-lsp/scala-plugin-{version}/
 */
object IntellijSdk {

  val intellijVersion = "261.22158.121"
  val scalaPluginVersion = "2026.1.7"

  private def cacheDir: Path = os.home / ".cache" / "intellij-scala-lsp"

  private def hasLib(p: Path): Boolean = os.exists(p / "lib")

  def findIntellijHome(): Path = {
    val candidates: Seq[Path] = Seq(
      // 1. Environment variable
      sys.env.get("INTELLIJ_HOME").map(os.Path(_)),
      // 2. Common macOS locations
      Some(os.root / "Applications" / "IntelliJ IDEA CE.app" / "Contents"),
      Some(os.root / "Applications" / "IntelliJ IDEA Community Edition.app" / "Contents"),
      Some(os.root / "Applications" / "IntelliJ IDEA.app" / "Contents"),
      Some(os.home / "Applications" / "IntelliJ IDEA CE.app" / "Contents"),
      Some(os.home / "Applications" / "IntelliJ IDEA Community Edition.app" / "Contents"),
      Some(os.home / "Applications" / "IntelliJ IDEA.app" / "Contents"),
      // 3. Cached download
      Some(cacheDir / s"idea-IC-$intellijVersion"),
    ).flatten

    candidates.find(hasLib).getOrElse {
      sys.error(
        s"""IntelliJ Community Edition not found.
           |
           |Please set INTELLIJ_HOME to your IntelliJ installation directory,
           |or install IntelliJ IDEA Community Edition.
           |
           |Expected to find 'lib/' directory containing platform JARs.
           |
           |Searched locations:
           |${candidates.map(p => s"  - $p").mkString("\n")}
           |""".stripMargin
      )
    }
  }

  def findScalaPluginDir(): Path = {
    val intellijHome = findIntellijHome()

    val candidates: Seq[Path] = Seq(
      // 1. Environment variable
      sys.env.get("SCALA_PLUGIN_HOME").map(os.Path(_)),
      // 2. Inside IntelliJ's plugins directory
      Some(intellijHome / "plugins" / "Scala"),
      // 3. User plugins directories (macOS + Linux)
      Some(os.home / "Library" / "Application Support" / "JetBrains" / s"IdeaIC${intellijVersion.take(3)}" / "plugins" / "Scala"),
      Some(os.home / ".config" / "JetBrains" / s"IdeaIC${intellijVersion.take(3)}" / "plugins" / "Scala"),
      // 4. Cached download
      Some(cacheDir / s"scala-plugin-$scalaPluginVersion"),
    ).flatten

    candidates.find(hasLib).getOrElse {
      sys.error(
        s"""Scala plugin not found.
           |
           |Please set SCALA_PLUGIN_HOME to the Scala plugin directory,
           |or install the Scala plugin in your IntelliJ installation.
           |
           |Searched locations:
           |${candidates.map(p => s"  - $p").mkString("\n")}
           |""".stripMargin
      )
    }
  }

  def platformJars(): Seq[PathRef] = {
    val sdkDir = findIntellijHome()
    val libDir = sdkDir / "lib"
    System.err.println(s"[IntellijSdk] Using IntelliJ platform JARs from: $libDir")
    os.list(libDir)
      .filter(_.last.endsWith(".jar"))
      .map(p => PathRef(p))
      .toSeq
  }

  def testFrameworkJars(): Seq[PathRef] = {
    val sdkDir = findIntellijHome()
    val libDir = sdkDir / "lib"
    val testJarNames = Set("testFramework.jar")
    os.list(libDir)
      .filter(p => testJarNames.contains(p.last))
      .map(p => PathRef(p))
      .toSeq
  }

  def scalaPluginJars(): Seq[PathRef] = {
    val pluginDir = findScalaPluginDir()
    val libDir = pluginDir / "lib"
    System.err.println(s"[IntellijSdk] Using Scala plugin JARs from: $libDir")
    os.list(libDir)
      .filter(_.last.endsWith(".jar"))
      .map(p => PathRef(p))
      .toSeq
  }
}

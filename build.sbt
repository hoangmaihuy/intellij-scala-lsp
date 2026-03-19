import org.jetbrains.sbtidea.Keys._
import org.jetbrains.sbtidea.packaging.PackagingKeys._

// IntelliJ Platform settings (must be ThisBuild-scoped for sbt-idea-plugin)
ThisBuild / intellijPluginName := "intellij-scala-lsp"
ThisBuild / intellijBuild := "253.32098.37"
ThisBuild / intellijPlatform := IntelliJPlatform.IdeaCommunity

Global / excludeLintKeys ++= Set(intellijPlugins)

lazy val root = project.in(file("."))
  .aggregate(`lsp-server`)
  .settings(
    name := "intellij-scala-lsp",
    publish / skip := true,
  )

lazy val `lsp-server` = project.in(file("lsp-server"))
  .enablePlugins(SbtIdeaPlugin)
  .settings(
    scalaVersion := "3.8.2",

    // Target Java 21 (IntelliJ's bundled JBR version)
    javacOptions ++= Seq("--release", "21"),

    // Packaging
    packageMethod := PackagingMethod.Standalone(),

    // Depend on the Scala plugin
    intellijPlugins += "org.intellij.scala".toPlugin,

    // lsp4j dependency
    libraryDependencies ++= Seq(
      "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.23.1",
    ),

    // Test dependencies
    libraryDependencies ++= Seq(
      // JUnit 4 needed at runtime for TestApplicationManager bootstrap (not just tests)
      "junit" % "junit" % "4.13.2",
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
      "org.opentest4j" % "opentest4j" % "1.3.0" % Test,
    ),

    // Add testFramework.jar to compile classpath (IntellijBootstrap uses TestApplicationManager)
    Compile / unmanagedJars += {
      (ThisBuild / intellijBaseDirectory).value / "lib" / "testFramework.jar"
    },

    // Add Java plugin JARs to test classpath (needed by Scala plugin)
    Test / unmanagedJars ++= {
      val javaPluginDir = (ThisBuild / intellijBaseDirectory).value / "plugins" / "java" / "lib"
      if (javaPluginDir.exists()) (javaPluginDir ** "*.jar").get
      else Seq.empty
    },

    // Source layout matching our project structure
    Compile / sourceDirectory := baseDirectory.value / "src",
    Compile / unmanagedSourceDirectories := Seq((Compile / sourceDirectory).value),
    Compile / resourceDirectory := baseDirectory.value / "resources",
    Compile / unmanagedResourceDirectories := Seq((Compile / resourceDirectory).value),
    Test / sourceDirectory := baseDirectory.value / "test" / "src",
    Test / unmanagedSourceDirectories := Seq((Test / sourceDirectory).value),
    Test / resourceDirectory := baseDirectory.value / "test" / "resources",
    Test / unmanagedResourceDirectories := Seq((Test / resourceDirectory).value),

    // Override plugin.path to include both our plugin AND the Scala plugin
    // sbt-idea-plugin only sets our plugin; we need the Scala plugin too for integration tests
    Test / javaOptions := (Test / javaOptions).value
      .filterNot(_.startsWith("-Dplugin.path=")) :+
      s"-Dplugin.path=${baseDirectory.value / "target" / "plugin" / "intellij-scala-lsp"}${java.io.File.pathSeparator}${(ThisBuild / intellijBaseDirectory).value.getAbsolutePath}/custom-plugins/Scala${java.io.File.pathSeparator}${(ThisBuild / intellijBaseDirectory).value.getAbsolutePath}/plugins/java",

    // JVM options for tests (IntelliJ test framework needs --add-opens)
    Test / javaOptions ++= Seq(
      s"-Didea.home.path=${(ThisBuild / intellijBaseDirectory).value.getAbsolutePath}",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.net=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
      "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
      "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
      "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
      "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
      "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
      "-Djava.awt.headless=true",
    ),
    Test / fork := true,
  )

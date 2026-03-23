package org.jetbrains.scalalsP.integration

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.{Files, FileVisitResult, Path, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException

/**
 * Tests for the --import flag in the launcher script.
 *
 * These tests verify the detection logic (build tool detection, error handling)
 * by running the launcher script with ProcessBuilder. They do NOT test actual
 * sbt/Mill imports — just the bash-level argument validation and file detection.
 */
class ProjectImportLauncherTest:

  /** Resolve launcher path — works whether CWD is project root or lsp-server/ */
  private val launcherPath: String =
    val candidates = Seq(
      Path.of("launcher/intellij-scala-lsp").toAbsolutePath,
      Path.of("../launcher/intellij-scala-lsp").toAbsolutePath.normalize
    )
    candidates.find(Files.exists(_)) match
      case Some(p) => p.toString
      case None => sys.error(s"Cannot find launcher script; tried: ${candidates.mkString(", ")}; CWD=${Path.of(".").toAbsolutePath}")

  private def runImport(args: String*): (Int, String, String) =
    val cmd = (Seq("bash", launcherPath, "--import") ++ args).toArray
    val pb = new ProcessBuilder(cmd*)
    // Minimal PATH so we don't accidentally pick up sbt/mill
    pb.environment().put("PATH", "/usr/bin:/bin:/usr/local/bin")
    // Prevent the script from finding a real IntelliJ SDK
    pb.environment().remove("INTELLIJ_HOME")
    val process = pb.start()
    val stdout = new String(process.getInputStream.readAllBytes())
    val stderr = new String(process.getErrorStream.readAllBytes())
    // Timeout to avoid hanging if the script spawns long-running children
    val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
    if !finished then process.destroyForcibly()
    val exitCode = if finished then process.exitValue() else 1
    (exitCode, stdout, stderr)

  /** Check if text appears in stdout or stderr (the script uses both) */
  private def outputContains(stdout: String, stderr: String, text: String): Boolean =
    stdout.contains(text) || stderr.contains(text)

  /** Recursively delete a directory (the launcher may create subdirs like project/, target/).
    * Uses walkFileTree + retry to handle races where the launched process is still writing files. */
  private def deleteRecursively(dir: Path): Unit =
    if !Files.exists(dir) then return
    // Retry up to 3 times — the launched script process may still be writing files
    for attempt <- 1 to 3 do
      try
        Files.walkFileTree(dir, new SimpleFileVisitor[Path]:
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
            Files.deleteIfExists(file)
            FileVisitResult.CONTINUE
          override def visitFileFailed(file: Path, exc: IOException): FileVisitResult =
            FileVisitResult.CONTINUE // skip files that vanished mid-walk
          override def postVisitDirectory(d: Path, exc: IOException): FileVisitResult =
            try Files.deleteIfExists(d)
            catch case _: IOException => () // directory may not be empty yet
            FileVisitResult.CONTINUE
        )
        if !Files.exists(dir) then return
        Thread.sleep(200) // brief pause before retry
      catch case _: IOException =>
        Thread.sleep(200)

  @Test def testMissingPathArgument(): Unit =
    val (exitCode, stdout, stderr) = runImport()
    assertEquals(s"Expected exit code 1 for missing argument. stdout: $stdout, stderr: $stderr", 1, exitCode)
    assertTrue(
      s"Expected error about missing path, got stdout: $stdout, stderr: $stderr",
      outputContains(stdout, stderr, "--import requires a project path argument")
    )

  @Test def testNonexistentDirectory(): Unit =
    val (exitCode, stdout, stderr) = runImport("/nonexistent/path/that/does/not/exist")
    assertEquals(s"Expected exit code 1 for nonexistent directory. stdout: $stdout, stderr: $stderr", 1, exitCode)
    assertTrue(
      s"Expected error about directory not existing, got stdout: $stdout, stderr: $stderr",
      outputContains(stdout, stderr, "Directory does not exist")
    )

  @Test def testNoBuildFile(): Unit =
    val tmpDir = Files.createTempDirectory("import-test-empty")
    try
      val (exitCode, stdout, stderr) = runImport(tmpDir.toString)
      assertEquals(s"Expected exit code 1 for no build file. stdout: $stdout, stderr: $stderr", 1, exitCode)
      assertTrue(
        s"Expected error about build tool detection, got stdout: $stdout, stderr: $stderr",
        outputContains(stdout, stderr, "Could not detect build tool")
      )
    finally
      deleteRecursively(tmpDir)

  @Test def testDetectsMillProject(): Unit =
    val tmpDir = Files.createTempDirectory("import-test-mill")
    try
      Files.createFile(tmpDir.resolve("build.mill"))
      val (_, stdout, stderr) = runImport(tmpDir.toString)
      assertTrue(
        s"Expected Mill detection message, got stdout: $stdout, stderr: $stderr",
        outputContains(stdout, stderr, "Detected Mill project")
      )
    finally
      deleteRecursively(tmpDir)

  @Test def testDetectsMillProjectWithBuildSc(): Unit =
    val tmpDir = Files.createTempDirectory("import-test-mill-sc")
    try
      Files.createFile(tmpDir.resolve("build.sc"))
      val (_, stdout, stderr) = runImport(tmpDir.toString)
      assertTrue(
        s"Expected Mill detection message, got stdout: $stdout, stderr: $stderr",
        outputContains(stdout, stderr, "Detected Mill project")
      )
    finally
      deleteRecursively(tmpDir)

  @Test def testDetectsSbtProject(): Unit =
    val tmpDir = Files.createTempDirectory("import-test-sbt")
    try
      Files.createFile(tmpDir.resolve("build.sbt"))
      val (_, stdout, stderr) = runImport(tmpDir.toString)
      assertTrue(
        s"Expected sbt detection message, got stdout: $stdout, stderr: $stderr",
        outputContains(stdout, stderr, "Detected sbt project")
      )
    finally
      deleteRecursively(tmpDir)

  @Test def testMillTakesPriorityOverSbt(): Unit =
    val tmpDir = Files.createTempDirectory("import-test-both")
    try
      Files.createFile(tmpDir.resolve("build.mill"))
      Files.createFile(tmpDir.resolve("build.sbt"))
      val (_, stdout, stderr) = runImport(tmpDir.toString)
      assertTrue(
        s"Expected Mill detection (priority over sbt), got stdout: $stdout, stderr: $stderr",
        outputContains(stdout, stderr, "Detected Mill project")
      )
    finally
      deleteRecursively(tmpDir)

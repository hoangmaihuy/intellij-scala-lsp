package org.jetbrains.scalalsP

/**
 * Alternative entry point for direct invocation (without IntelliJ's Main class).
 * Typically, the LSP is launched via `com.intellij.idea.Main scala-lsp`,
 * which triggers [[ScalaLspApplicationStarter]].
 *
 * This main class is provided for development/debugging convenience.
 */
object ScalaLspMain:
  def main(args: Array[String]): Unit =
    System.setProperty("java.awt.headless", "true")
    System.setProperty("idea.is.internal", "false")

    // Delegate to IntelliJ's Main with our appStarter command
    val fullArgs = Array("scala-lsp") ++ args
    com.intellij.idea.Main.main(fullArgs)

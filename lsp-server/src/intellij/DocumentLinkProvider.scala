package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import org.eclipse.lsp4j.{DocumentLink, Position, Range}

import scala.util.matching.Regex

// Implements textDocument/documentLink by scanning document text for clickable links:
// URLs, SBT dependency coordinates, and file paths in strings.
class DocumentLinkProvider(projectManager: IntellijProjectManager):

  import DocumentLinkProvider.*

  def getDocumentLinks(uri: String): Seq[DocumentLink] =
    try
      projectManager.smartReadAction: () =>
        val result = for
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
        yield
          val text = document.getText
          val lines = text.split("\n", -1)
          val links = Seq.newBuilder[DocumentLink]

          for (line, lineIdx) <- lines.zipWithIndex do
            // Detect URLs
            val urlMatches = UrlPattern.findAllMatchIn(line)
            for m <- urlMatches do
              val link = new DocumentLink()
              link.setRange(new Range(
                new Position(lineIdx, m.start),
                new Position(lineIdx, m.end)
              ))
              link.setTarget(m.matched)
              links += link

            // Detect SBT dependencies
            val sbtMatches = SbtDependencyPattern.findAllMatchIn(line)
            for m <- sbtMatches do
              val group = m.group(1)
              val artifact = m.group(2)
              val target = s"https://search.maven.org/search?q=g:$group+a:$artifact*"
              val link = new DocumentLink()
              link.setRange(new Range(
                new Position(lineIdx, m.start),
                new Position(lineIdx, m.end)
              ))
              link.setTarget(target)
              links += link

            // Detect file paths in strings
            val pathMatches = FilePathPattern.findAllMatchIn(line)
            for m <- pathMatches do
              val filePath = m.group(1)
              val projectRoot = projectManager.getProject.getBasePath
              if projectRoot != null then
                val resolved = java.nio.file.Path.of(projectRoot).resolve(filePath)
                if java.nio.file.Files.exists(resolved) then
                  val link = new DocumentLink()
                  link.setRange(new Range(
                    new Position(lineIdx, m.start),
                    new Position(lineIdx, m.end)
                  ))
                  link.setTarget(s"file://${resolved.toAbsolutePath}")
                  links += link

          links.result()

        result.getOrElse(Seq.empty)
    catch
      case e: Exception =>
        System.err.println(s"[ScalaLsp] Error getting document links: ${e.getMessage}")
        Seq.empty

object DocumentLinkProvider:
  private val UrlPattern: Regex = """https?://[^\s"')>\]]+""".r
  private val SbtDependencyPattern: Regex = """"([^"]+)"\s+%{1,3}\s+"([^"]+)"\s+%\s+"([^"]+)"""".r
  private val FilePathPattern: Regex = """"((?:src|test|resources|conf|config)/[^"]+)"""".r

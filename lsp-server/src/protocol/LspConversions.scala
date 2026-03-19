package org.jetbrains.scalalsP.protocol

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.*
import org.jetbrains.scalalsP.intellij.PsiUtils

/** Conversion utilities between IntelliJ types and LSP protocol types. */
object LspConversions:

  def uriToPath(uri: String): String =
    if uri.startsWith("file://") then
      java.net.URI.create(uri).getPath
    else
      uri

  def pathToUri(path: String): String =
    s"file://$path"

  def toLocation(vf: VirtualFile, document: Document, startOffset: Int, endOffset: Int): Location =
    val start = PsiUtils.offsetToPosition(document, startOffset)
    val end = PsiUtils.offsetToPosition(document, endOffset)
    new Location(PsiUtils.vfToUri(vf), new Range(start, end))

  def toTextEdit(document: Document, startOffset: Int, endOffset: Int, newText: String): TextEdit =
    val start = PsiUtils.offsetToPosition(document, startOffset)
    val end = PsiUtils.offsetToPosition(document, endOffset)
    new TextEdit(new Range(start, end), newText)

  def toSymbolKind(element: PsiElement): SymbolKind =
    val className = element.getClass.getName
    if className.contains("ScClass") then SymbolKind.Class
    else if className.contains("ScTrait") then SymbolKind.Interface
    else if className.contains("ScObject") then SymbolKind.Module
    else if className.contains("ScFunction") then SymbolKind.Method
    else if className.contains("ScValue") then SymbolKind.Field
    else if className.contains("ScVariable") then SymbolKind.Variable
    else if className.contains("ScTypeAlias") then SymbolKind.TypeParameter
    else SymbolKind.Variable

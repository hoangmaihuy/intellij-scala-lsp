package org.jetbrains.scalalsP

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.{LanguageClient, WorkspaceService}
import org.jetbrains.scalalsP.intellij.{IntellijProjectManager, SymbolProvider}

import java.util
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*

// Handles workspace LSP requests.
class ScalaWorkspaceService(projectManager: IntellijProjectManager) extends WorkspaceService:

  import scala.compiletime.uninitialized
  private var client: LanguageClient = uninitialized
  private val symbolProvider = SymbolProvider(projectManager)

  def connect(client: LanguageClient): Unit =
    this.client = client

  override def symbol(params: WorkspaceSymbolParams): CompletableFuture[org.eclipse.lsp4j.jsonrpc.messages.Either[util.List[? <: SymbolInformation], util.List[? <: WorkspaceSymbol]]] =
    CompletableFuture.supplyAsync: () =>
      val symbols = symbolProvider.workspaceSymbols(params.getQuery)
      org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(symbols.asJava)

  override def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = ()

  override def didChangeWatchedFiles(params: DidChangeWatchedFilesParams): Unit = ()

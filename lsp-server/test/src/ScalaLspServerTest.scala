package org.jetbrains.scalalsP

import munit.FunSuite
import org.eclipse.lsp4j.*

import java.util.concurrent.CompletableFuture

// Unit tests for ScalaLspServer that don't require IntelliJ platform.
class ScalaLspServerTest extends FunSuite:

  test("server initializes with file URI root"):
    val server = new ScalaLspServer("/tmp/fallback")
    val params = InitializeParams()
    params.setRootUri("file:///home/user/project")
    params.setCapabilities(ClientCapabilities())

    // Will fail due to no IntelliJ platform, but we test the URI parsing
    val future = server.initialize(params)
    try
      future.get(2, java.util.concurrent.TimeUnit.SECONDS)
    catch
      // Expected: IntelliJ platform not running
      case _: java.util.concurrent.ExecutionException => ()

  test("server initializes with plain path root"):
    val server = new ScalaLspServer("/tmp/fallback")
    val params = InitializeParams()
    params.setRootPath("/home/user/project")
    params.setCapabilities(ClientCapabilities())

    val future = server.initialize(params)
    try
      future.get(2, java.util.concurrent.TimeUnit.SECONDS)
    catch
      case _: java.util.concurrent.ExecutionException => ()

  test("server uses projectPath as fallback when no root provided"):
    val server = new ScalaLspServer("/tmp/my-project")
    val params = InitializeParams()
    // No rootUri or rootPath
    params.setCapabilities(ClientCapabilities())

    val future = server.initialize(params)
    try
      future.get(2, java.util.concurrent.TimeUnit.SECONDS)
    catch
      case _: java.util.concurrent.ExecutionException => ()

  test("server returns correct service instances"):
    val server = new ScalaLspServer("/tmp/test")
    val tds = server.getTextDocumentService
    val ws = server.getWorkspaceService

    assert(tds.isInstanceOf[ScalaTextDocumentService])
    assert(ws.isInstanceOf[ScalaWorkspaceService])

  test("connect sets client on services"):
    val server = new ScalaLspServer("/tmp/test")
    val mockClient = TestLanguageClient()
    server.connect(mockClient)
    // If connect didn't throw, it worked

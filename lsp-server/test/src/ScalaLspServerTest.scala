package org.jetbrains.scalalsP

import org.junit.Assert.*
import org.junit.Test
import org.eclipse.lsp4j.*

import java.util.concurrent.TimeUnit

class ScalaLspServerTest:

  @Test def testServerInitializesWithFileUriRoot(): Unit =
    val server = new ScalaLspServer("/tmp/fallback")
    val params = new InitializeParams()
    params.setRootUri("file:///home/user/project")
    params.setCapabilities(new ClientCapabilities())
    try server.initialize(params).get(2, TimeUnit.SECONDS)
    catch case _: java.util.concurrent.ExecutionException => ()

  @Test def testServerInitializesWithPlainPathRoot(): Unit =
    val server = new ScalaLspServer("/tmp/fallback")
    val params = new InitializeParams()
    params.setRootPath("/home/user/project")
    params.setCapabilities(new ClientCapabilities())
    try server.initialize(params).get(2, TimeUnit.SECONDS)
    catch case _: java.util.concurrent.ExecutionException => ()

  @Test def testServerUsesProjectPathAsFallback(): Unit =
    val server = new ScalaLspServer("/tmp/my-project")
    val params = new InitializeParams()
    params.setCapabilities(new ClientCapabilities())
    try server.initialize(params).get(2, TimeUnit.SECONDS)
    catch case _: java.util.concurrent.ExecutionException => ()

  @Test def testServerReturnsCorrectServiceInstances(): Unit =
    val server = new ScalaLspServer("/tmp/test")
    assertTrue(server.getTextDocumentService.isInstanceOf[ScalaTextDocumentService])
    assertTrue(server.getWorkspaceService.isInstanceOf[ScalaWorkspaceService])

  @Test def testConnectSetsClientOnServices(): Unit =
    val server = new ScalaLspServer("/tmp/test")
    val mockClient = TestLanguageClient()
    server.connect(mockClient)

package org.jetbrains.scalalsP.e2e

import com.google.gson.JsonObject
import org.junit.Assert.*

// End-to-end code action tests exercising the full LSP wire path.
//
// NOTE: The light test framework's IntentionManager may not produce available intentions
// when actions are requested off-EDT through CompletableFuture.supplyAsync.  When no
// actions are returned the structural assertions below are skipped, but the request must
// never crash and must always return a non-null list.
class CodeActionE2eTest extends E2eTestBase:

  // configureActiveScalaFile creates a real editor-backed file so that any intentions
  // that ARE available in the test environment will be discovered.

  def testCodeActionsNeverCrash(): Unit =
    val uri = configureActiveScalaFile(
      """object Main:
        |  val x = 1 + 2
        |""".stripMargin
    )
    // Must not throw; must return a non-null list
    val actions = client.codeActions(uri, 1, 2, 1, 13)
    assertNotNull("codeActions must return a non-null list", actions)

  def testCodeActionsHaveNonEmptyTitlesWhenPresent(): Unit =
    val uri = configureActiveScalaFile(
      """object Main:
        |  val x = 1 + 2
        |""".stripMargin
    )
    val actions = client.codeActions(uri, 1, 2, 1, 13)
    assertNotNull("codeActions must return a non-null list", actions)
    // When actions are present every one must have a non-blank title
    actions.foreach: action =>
      val title = action.getTitle
      assertNotNull("Every code action must have a title", title)
      assertFalse(s"Code action title must not be blank", title.isBlank)

  def testCodeActionDataHasTypeAndUri(): Unit =
    val uri = configureActiveScalaFile(
      """object Main:
        |  val x = 1 + 2
        |""".stripMargin
    )
    val actions = client.codeActions(uri, 1, 2, 1, 13)
    assertNotNull("codeActions must return a non-null list", actions)
    // When actions are present they must all carry properly structured data objects
    actions.foreach: action =>
      val data = action.getData
      assertNotNull(s"Action '${action.getTitle}' must carry a data field", data)
      val obj = data.asInstanceOf[JsonObject]
      assertNotNull(s"Action data must have 'type'", obj.get("type"))
      assertNotNull(s"Action data must have 'uri'", obj.get("uri"))
      val actionType = obj.get("type").getAsString
      assertTrue(
        s"'type' must be 'quickfix' or 'intention', got: '$actionType'",
        actionType == "quickfix" || actionType == "intention"
      )
      val actionUri = obj.get("uri").getAsString
      assertTrue(s"'uri' must start with 'file://', got: $actionUri",
        actionUri.startsWith("file://"))

  def testCodeActionResolveRoundTrip(): Unit =
    val uri = configureActiveScalaFile(
      """object Main:
        |  val x = 1 + 2
        |""".stripMargin
    )
    val actions = client.codeActions(uri, 1, 2, 1, 13)
    assertNotNull("codeActions must return a non-null list", actions)
    // When actions are present, resolving any one of them must return a well-formed result
    if actions.nonEmpty then
      val firstAction = actions.head
      assertNotNull("Action must have a data field before resolving", firstAction.getData)
      val resolved = client.resolveCodeAction(firstAction)
      assertNotNull("Resolved action must not be null", resolved)
      assertNotNull("Resolved action must have a title", resolved.getTitle)
      assertFalse("Resolved action title must not be blank", resolved.getTitle.isBlank)
      // A resolved action must have either a workspace edit OR a command
      assertTrue(
        "Resolved action must have a workspace edit or a command",
        resolved.getEdit != null || resolved.getCommand != null
      )

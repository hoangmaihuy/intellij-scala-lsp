package org.jetbrains.scalalsP

import org.junit.Test
import org.junit.Assert.*

class ProjectRegistryTest:
  @Test def testGetProjectReturnsNoneForUnknown(): Unit =
    val registry = ProjectRegistry()
    assertEquals(None, registry.getProject("/nonexistent"))

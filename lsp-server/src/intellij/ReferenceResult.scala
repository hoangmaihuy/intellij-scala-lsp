package org.jetbrains.scalalsP.intellij

import org.eclipse.lsp4j.Location

/** A reference with its classified usage type. */
case class ReferenceResult(location: Location, usageType: String)

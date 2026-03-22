package org.jetbrains.scalalsP.intellij

import org.eclipse.lsp4j.Location

/** A reference with its classified usage type. */
case class ReferenceResult(location: Location, usageType: String)

object ReferenceResult:
  val Import = "import"
  val Write = "write"
  val Read = "read"
  val TypeRef = "type_reference"
  val Pattern = "pattern"
  val TextOccurrence = "text_occurrence"

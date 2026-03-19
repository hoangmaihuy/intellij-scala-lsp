package org.jetbrains.scalalsP.test

import com.intellij.openapi.editor.{Document, RangeMarker}
import com.intellij.openapi.util.{Key, TextRange}
import java.beans.PropertyChangeListener

// Minimal Document implementation for testing offset/position conversions.
// Only implements the methods needed by PsiUtils.
class MockDocument(text: String) extends Document:

  private val lines: Array[Int] = computeLineStarts(text)

  private def computeLineStarts(s: String): Array[Int] =
    val starts = scala.collection.mutable.ArrayBuffer(0)
    var i = 0
    while i < s.length do
      if s(i) == '\n' then
        starts += (i + 1)
      i += 1
    starts.toArray

  override def getImmutableCharSequence: CharSequence = text
  override def getText: String = text
  override def getTextLength: Int = text.length
  override def getLineCount: Int = lines.length

  override def getLineNumber(offset: Int): Int =
    val clamped = math.max(0, math.min(offset, text.length))
    var lo = 0
    var hi = lines.length - 1
    while lo <= hi do
      val mid = (lo + hi) / 2
      if lines(mid) <= clamped then lo = mid + 1
      else hi = mid - 1
    lo - 1

  override def getLineStartOffset(line: Int): Int =
    if line < 0 then 0
    else if line >= lines.length then text.length
    else lines(line)

  override def getLineEndOffset(line: Int): Int =
    if line < 0 then 0
    else if line >= lines.length - 1 then text.length
    else lines(line + 1) - 1 // -1 to exclude the \n

  // --- Stubs for methods we don't use in tests ---
  override def getCharsSequence: CharSequence = text
  override def getText(range: TextRange): String = text.substring(range.getStartOffset, range.getEndOffset)
  override def getModificationStamp: Long = 0L
  override def isWritable: Boolean = false
  override def insertString(offset: Int, s: CharSequence): Unit = ()
  override def deleteString(startOffset: Int, endOffset: Int): Unit = ()
  override def replaceString(startOffset: Int, endOffset: Int, s: CharSequence): Unit = ()
  override def setText(text: CharSequence): Unit = ()
  override def isLineModified(line: Int): Boolean = false
  override def addPropertyChangeListener(listener: PropertyChangeListener): Unit = ()
  override def removePropertyChangeListener(listener: PropertyChangeListener): Unit = ()
  override def setReadOnly(isReadOnly: Boolean): Unit = ()
  override def createRangeMarker(startOffset: Int, endOffset: Int): RangeMarker = null
  override def createRangeMarker(startOffset: Int, endOffset: Int, surviveOnExternalChange: Boolean): RangeMarker = null
  override def createGuardedBlock(startOffset: Int, endOffset: Int): RangeMarker = null
  override def removeGuardedBlock(block: RangeMarker): Unit = ()
  override def getOffsetGuard(offset: Int): RangeMarker = null
  override def getRangeGuard(start: Int, end: Int): RangeMarker = null
  override def startGuardedBlockChecking(): Unit = ()
  override def stopGuardedBlockChecking(): Unit = ()

  // UserDataHolder stubs
  override def getUserData[T](key: Key[T]): T = null.asInstanceOf[T]
  override def putUserData[T](key: Key[T], value: T): Unit = ()

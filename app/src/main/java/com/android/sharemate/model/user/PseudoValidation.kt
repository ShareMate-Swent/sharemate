// Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>
package com.android.sharemate.model.user

internal const val MAX_PSEUDO_LENGTH = 40

internal enum class PseudoValidationError {
  REQUIRED,
  TOO_LONG,
}

internal fun validatePseudo(pseudo: String): PseudoValidationError? {
  val value = pseudo.trimPseudoPadding()
  return when {
    value.isEmpty() -> PseudoValidationError.REQUIRED
    value.codePointCount(0, value.length) > MAX_PSEUDO_LENGTH -> PseudoValidationError.TOO_LONG
    else -> null
  }
}

internal fun String.trimPseudoPadding(): String {
  var start = 0
  var end = length
  while (start < end) {
    val codePoint = codePointAt(start)
    if (!codePoint.isInvisiblePseudoCharacter()) break
    start += Character.charCount(codePoint)
  }
  while (end > start) {
    val codePoint = codePointBefore(end)
    if (!codePoint.isInvisiblePseudoCharacter()) break
    end -= Character.charCount(codePoint)
  }
  return substring(start, end)
}

private fun Int.isInvisiblePseudoCharacter(): Boolean {
  val category = Character.getType(this).toInt()
  return Character.isWhitespace(this) ||
      Character.isSpaceChar(this) ||
      category == Character.FORMAT.toInt() ||
      category == Character.CONTROL.toInt() ||
      category == Character.NON_SPACING_MARK.toInt() ||
      category == Character.ENCLOSING_MARK.toInt() ||
      category == Character.LINE_SEPARATOR.toInt() ||
      category == Character.PARAGRAPH_SEPARATOR.toInt()
}

package ba.sake.potateh.internal

import scala.util.boundary

import boundary.break

private[potateh] object HttpSyntax {

  private val MaxEmptyListElements = 8

  def listElements(values: Vector[String]): Option[Vector[String]] = boundary {
    val elements = Vector.newBuilder[String]
    var empty = 0
    val iterator = values.iterator
    while (iterator.hasNext) {
      val value = iterator.next()
      var start = 0
      var end = value.indexOf(',')
      while (end >= 0) {
        val element = trimOws(value.substring(start, end))
        if (element.isEmpty) empty += 1 else elements += element
        if (empty > MaxEmptyListElements) break(None)
        start = end + 1
        end = value.indexOf(',', start)
      }
      val element = trimOws(value.substring(start))
      if (element.isEmpty) empty += 1 else elements += element
      if (empty > MaxEmptyListElements) break(None)
    }
    Some(elements.result())
  }

  def field(line: String): Option[(name: String, value: String)] = boundary {
    val colon = line.indexOf(':')
    if (colon <= 0) break(None)
    var nameIndex = 0
    while nameIndex < colon && isToken(line.charAt(nameIndex)) do nameIndex += 1
    if (nameIndex != colon) break(None)
    val value = line.substring(colon + 1)
    if (!isFieldValue(value)) break(None)
    Some((name = line.substring(0, colon), value = trimOws(value)))
  }

  def trimOws(value: String): String = {
    var start = 0
    var end = value.length
    while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) start += 1
    while (
      end > start && (value
        .charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')
    ) end -= 1
    value.substring(start, end)
  }

  def isToken(c: Char): Boolean = {
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
    (c match {
      case '!' | '#' | '$' | '%' | '&' | '\'' | '*' | '+' | '-' | '.' | '^' | '_' | '`' | '|' | '~' => true
      case _                                                                                        => false
    })
  }

  def isTokenName(value: String): Boolean = {
    var index = 0
    while index < value.length && isToken(value.charAt(index)) do index += 1
    index == value.length && index > 0
  }

  def areTokens(values: Seq[String]): Boolean = {
    var index = 0
    while index < values.length && isTokenName(values(index)) do index += 1
    index == values.length
  }

  def isFieldValue(value: String): Boolean = {
    var index = 0
    var valid = true
    while index < value.length && valid do {
      val c = value.charAt(index)
      if (c != '\t' && (c < ' ' || c == 127 || c > 255)) valid = false
      index += 1
    }
    valid
  }

  def equalsIgnoreCaseAscii(left: String, right: String): Boolean = {
    if (left eq right) true
    else if (left.length != right.length) false
    else {
      var index = 0
      var equal = true
      while (index < left.length && equal) {
        equal = asciiLower(left.charAt(index)) == asciiLower(right.charAt(index))
        index += 1
      }
      equal
    }
  }

  private inline def asciiLower(value: Char): Char = {
    if (value >= 'A' && value <= 'Z') (value + ('a' - 'A')).toChar else value
  }

}

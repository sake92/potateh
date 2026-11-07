package ba.sake.potateh.internal

import scala.util.boundary, boundary.break

private[potateh] object HttpSyntax {
  def field(line: String): Option[(name: String, value: String)] = boundary {
    val colon = line.indexOf(':')
    if (colon <= 0 || !line.substring(0, colon).forall(isToken)) break(None)
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
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
    (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0
  }

  def isFieldValue(value: String): Boolean = {
    value.forall(c => c == '\t' || (c >= ' ' && c != 127 && c <= 255))
  }
}

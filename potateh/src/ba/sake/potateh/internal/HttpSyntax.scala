package ba.sake.potateh.internal

private[potateh] object HttpSyntax {
  def field(line: String): Option[(String, String)] = {
    val colon = line.indexOf(':')
    if (colon <= 0 || !line.substring(0, colon).forall(isToken)) return None
    val value = line.substring(colon + 1)
    if (!isFieldValue(value)) return None
    Some((line.substring(0, colon), trimOws(value)))
  }

  def trimOws(value: String): String = {
    var start = 0
    var end = value.length
    while (
      start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')
    ) start += 1
    while (
      end > start && (value
        .charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')
    ) end -= 1
    value.substring(start, end)
  }

  def isToken(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
      (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0

  def isFieldValue(value: String): Boolean =
    value.forall(c => c == '\t' || (c >= ' ' && c != 127 && c <= 255))
}

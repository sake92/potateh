package ba.sake.potateh.internal

import java.net.{URI, URISyntaxException}

private[internal] object RequestTargetValidation {
  // Common ASCII targets and hosts skip URI parsing, e.g. /items?page=2 and example.com:8080.
  // Other forms use the original checks, e.g. /hello%20world, http://example.com/ and [::1]:8080.
  def validateTarget(method: String, target: String): Unit = {
    if (!simpleTarget(method, target)) validateTargetWithUri(method, target)
  }

  private def simpleTarget(method: String, target: String): Boolean = {
    if (method == "CONNECT" || !target.startsWith("/")) false
    else {
      var index = 0
      var valid = true
      while (index < target.length && valid) {
        val c = target.charAt(index)
        valid = asciiLetterOrDigit(c) || c == '-' || c == '.' || c == '_' || c == '~' ||
          c == '/' || c == '?' || c == '&' || c == '='
        index += 1
      }
      valid
    }
  }

  private def asciiLetterOrDigit(c: Char): Boolean = {
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
  }

  private def validateTargetWithUri(method: String, target: String): Unit = {
    if (!target.forall(c => c > ' ' && c < 127) || target.indexOf('#') >= 0) {
      fail(400, "Invalid request target")
    }
    if (target == "*") {
      if (method != "OPTIONS") fail(400, "Asterisk target requires OPTIONS")
    } else if (method == "CONNECT") {
      val uri = parseUri("http://" + target)
      if (
        !validAuthority(uri, requirePort = true) || uri
          .getRawPath() != "" ||
        Option(uri.getRawQuery()).nonEmpty || Option(
          uri.getRawUserInfo()
        ).nonEmpty
      ) {
        fail(400, "Invalid CONNECT authority")
      }
    } else if (target.startsWith("/")) {
      // Prefixing an authority keeps a leading // in the path, rather than
      // letting URI reinterpret it as an authority. Preserve the raw target.
      parseUri("http://request.invalid" + target)
    } else {
      val uri = parseUri(target)
      if (
        !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(
          uri.getScheme()
        )) ||
        !validAuthority(uri, requirePort = false)
      ) {
        fail(400, "Invalid absolute request target")
      }
    }
  }

  def validateHost(value: String): Unit = {
    if (!simpleHost(value)) validateHostWithUri(value)
  }

  private def simpleHost(value: String): Boolean = {
    var index = 0
    while (
      index < value.length && (asciiLetterOrDigit(value.charAt(index)) ||
        value.charAt(index) == '.' || value.charAt(index) == '-')
    ) {
      index += 1
    }
    if (index == 0 || !asciiLetterOrDigit(value.charAt(0)) || !asciiLetterOrDigit(value.charAt(index - 1))) false
    else if (index == value.length) true
    else if (value.charAt(index) != ':') false
    else {
      index += 1
      val portStart = index
      while (index < value.length && value.charAt(index) >= '0' && value.charAt(index) <= '9') index += 1
      index == value.length && index - portStart > 0 && index - portStart <= 5
    }
  }

  private def validateHostWithUri(value: String): Unit = {
    // Empty Host is permitted when the target authority is undefined.
    if (value.nonEmpty) {
      val uri = parseUri("http://" + value)
      if (
        !validAuthority(uri, requirePort = false) ||
        uri.getRawPath() != "" || Option(uri.getRawQuery()).nonEmpty || Option(
          uri.getRawFragment()
        ).nonEmpty
      ) {
        fail(400, "Invalid Host")
      }
    }
  }

  private def validAuthority(uri: URI, requirePort: Boolean): Boolean = {
    val authority = Option(uri.getRawAuthority()).getOrElse("")
    val closingBracket = authority.indexOf(']')
    val separator = {
      if (authority.startsWith("[") && closingBracket >= 0) closingBracket + 1
      else {
        authority.indexOf(':') match {
          case -1    => authority.length
          case index => index
        }
      }
    }
    val host = authority.substring(0, separator)
    val suffix = authority.substring(separator)
    val validHost = {
      if (host.startsWith("[")) Option(uri.getHost()).nonEmpty
      else validRegisteredName(host)
    }
    val validPort = {
      if (suffix.isEmpty) !requirePort
      else if (!suffix.startsWith(":")) false
      else {
        val port = suffix.substring(1)
        if (port.isEmpty) !requirePort
        else port.forall(c => c >= '0' && c <= '9') && port.toIntOption.nonEmpty
      }
    }
    validHost && validPort
  }

  private def validRegisteredName(host: String): Boolean = {
    def hex(c: Char): Boolean = {
      (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
    }
    var index = 0
    var valid = host.nonEmpty
    while (index < host.length && valid) {
      val c = host.charAt(index)
      if (c == '%') {
        valid = index + 2 < host.length && hex(host.charAt(index + 1)) && hex(host.charAt(index + 2))
        index += 3
      } else {
        valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') || "-._~!$&'()*+,;=".contains(c)
        index += 1
      }
    }
    valid
  }

  private def parseUri(value: String): URI = {
    try new URI(value)
    catch { case _: URISyntaxException => fail(400, "Invalid URI syntax") }
  }

  private def fail(code: Int, message: String): Nothing = {
    throw new RequestHeadParser.ParseException(code, message)
  }
}

# Planned improvements

- [ ] Close RFC audit gaps with shared JVM/Native regression tests:
  - Ignore HTTP/1.0 `Expect: 100-continue` and `Upgrade` fields instead of
    returning 417/501 (RFC 9110 sections 10.1.1 and 7.8).
  - Accept a bounded number of empty list elements in `Transfer-Encoding` and
    `Expect`, preserving framing-conflict checks (RFC 9110 section 5.6.1.2).
  - Validate URI registered names without depending on `URI.getHost` accepting
    them: valid hosts such as `foo_bar` and `%65xample.org` currently get 400
    (RFC 9110 section 7.2; RFC 3986 section 3.2.2).
  - Avoid inferring representation length from an empty HEAD/304 body;
    omit length metadata when the corresponding GET/200 length is unknown
    (RFC 9110 section 8.6).
- [ ] Review interoperability policies: higher HTTP/1 minor versions, a leading
  CRLF, and ignoring unselected HTTP/1.1 upgrade offers (RFC 9110 sections 2.5
  and 7.8; RFC 9112 section 2.2).
- [ ] Clarify application responsibilities for absolute-target authority and
  status-specific response fields; currently 426 requires an `Upgrade` field
  that the response writer forbids (RFC 9112 section 3.2.2; RFC 9110 section 7.8).
- [ ] Add protocol upgrade support for a separate WebSocket implementation:
  preserve buffered input, define stream lifetime/timeouts, and retain connection
  admission and shutdown ownership.
- [ ] Track the Scala Native 0.5.12 TERM shutdown exception; see
  [known limitations](conformance/README.md#known-limitations).
- [ ] Discuss the empty-Host expectation disagreement with Http11Probe maintainers.
- [ ] Add repeatable external-suite CI checks for JVM and Native.
- [ ] Validate macOS and Windows behavior.

# Planned improvements

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

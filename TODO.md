# Planned improvements

- [ ] Add protocol upgrade support for a separate WebSocket implementation:
  preserve buffered input, define stream lifetime/timeouts, and retain connection
  admission and shutdown ownership.
- [ ] Track the Scala Native 0.5.12 TERM shutdown exception; see
  [known limitations](conformance/README.md#known-limitations).
- [ ] Discuss the empty-Host expectation disagreement with Http11Probe maintainers.
- [ ] Add repeatable external-suite CI checks for JVM and Native.
- [ ] Validate macOS and Windows behavior.

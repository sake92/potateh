
add some logging
https://index.scala-lang.org/outr/scribe

- DRY, simplify

# Planned improvements

- [ ] Replace the pinned Scala Native allocator snapshot with the stable 0.5.13
  release when available.
- [ ] Add protocol upgrade support for a separate WebSocket implementation:
  preserve buffered input, define stream lifetime/timeouts, and retain connection
  admission and shutdown ownership.
- [ ] Track the Scala Native 0.5.12 TERM shutdown exception; see
  [known limitations](conformance/README.md#known-limitations).
- [ ] Discuss the empty-Host expectation disagreement with Http11Probe maintainers.
- [ ] Add repeatable external-suite CI checks for JVM and Native.
- [ ] Validate macOS and Windows behavior.

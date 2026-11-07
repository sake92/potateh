# Planned improvements

Unchecked items are outstanding work, not implemented protections or confirmed vulnerabilities. Source-server results do not establish potateh behavior.

## Hardening validation

- [ ] Test request-boundary disagreements through a real reverse proxy and potateh; distinguish deployment findings from direct-server findings.
- [ ] Expand RFC coverage beyond external suites without treating suite preferences as normative requirements.

## Conformance and portability

- [ ] Track/fix upstream Scala Native 0.5.12 TERM shutdown while the fixture main thread is parked: `IllegalMonitorStateException` in Runtime hook joining was observed after `Server.stop()` completed. The same exception reproduces without potateh in a shutdown-hook plus CountDownLatch program. Explicit stop and file-triggered fixture shutdown pass; see conformance/README.md.
- [ ] Discuss the remaining empty-Host expectation disagreement with Http11Probe maintainers; the local RFC review is recorded in conformance/README.md.
- [ ] Establish a repeatable external-suite CI baseline for potateh JVM and Native; distinguish local results from remote CI evidence.
- [ ] Validate macOS/Windows behavior before claiming those platforms are supported. Linux release-mode Native builds are exercised locally.

JDK 8/Scala 2 compatibility, protected-realm mutation, Java binary compatibility and upstream JDK API placement are intentionally dropped from the source backlog. HTTPS, HTTP/2, provider discovery, upgrades/WebSockets and CONNECT tunnels remain exclusions, not planned commitments.

Keep raw reports and one-off diagnostics in ignored build output. Record completed behavior and reproducible validation concisely in the compatibility/conformance documentation, then remove completed backlog entries.

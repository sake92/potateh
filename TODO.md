# Planned improvements

Implement the initial library using [PLAN.md](PLAN.md). This backlog adapts the source project's TODO to potateh's scope. Unchecked items are outstanding work, not implemented protections or confirmed vulnerabilities. Source-server results do not establish potateh behavior.

## Hardening validation

- [ ] Extend the ported bounded slow-client tests to sustained load and representative slow/streaming traffic using potateh's case-class configuration and owned workers.
- [ ] Evaluate Nmap `http-slowloris-check` alongside measured exhaustion tests.
- [ ] Add reproducible Scala grammar/stateful fuzzing for malformed messages, framing boundaries and persistent-connection sequences; preserve failing seeds as regressions.
- [ ] Test request-boundary disagreements through a real reverse proxy and potateh; distinguish deployment findings from direct-server findings.
- [ ] Expand RFC coverage beyond external suites without treating suite preferences as normative requirements.

## Conformance and portability

- [ ] Review the disputed empty-Host expectation against the RFC and with Http11Probe maintainers if applicable to the adapted fixture.
- [ ] Establish a repeatable external-suite CI baseline for potateh JVM and Native; distinguish local results from remote CI evidence.
- [ ] Check whether the source fixture's recorded Scala Native `PosixThread.park` assertion during TERM shutdown reproduces with potateh; investigate if it does.
- [ ] Exercise incomplete trailers under worker saturation using potateh's configured admission and I/O bounds. Reference-JDK behavior is optional comparison evidence.
- [ ] Validate Native release-mode builds and macOS/Windows behavior before claiming those platforms are supported.

JDK 8/Scala 2 compatibility, protected-realm mutation, Java binary compatibility and upstream JDK API placement are intentionally dropped from the source backlog. HTTPS, HTTP/2, provider discovery, upgrades/WebSockets and CONNECT tunnels remain exclusions, not planned commitments.

Keep raw reports and one-off diagnostics in ignored build output. Record completed behavior and reproducible validation concisely in the eventual compatibility/conformance documentation, then remove completed backlog entries.

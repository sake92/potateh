# Protocol coverage

The source tree was clean at revision
`7cd8160eee9702f02a13e273b80cb5391d9283d1` when selected for adaptation.
`NOTICE` and `LICENSE` retain attribution and Apache-2.0 terms.
These mappings describe potateh tests, not inherited passing results.

| Source scenarios | potateh coverage / decision |
| --- | --- |
| `RequestHeadParserTest` | All 13 scenarios directly adapted to MUnit, including every fragment boundary, truncation, target forms, Host, strict CRLF, transfer coding and ambiguous/overflowing lengths. |
| `FixedLengthInputStreamTest` | All 11 scenarios: exact boundaries, long lengths, fragmented/bulk/skip reads, EOF, terminal failure and connection ownership. |
| `ChunkedInputStreamTest` | All 16 scenarios: chunk syntax/extensions, decoded budgets, metadata/trailer limits, terminal failure, fragmentation and connection ownership. Trailer-discard assertion replaced with separate exposed trailer fields. |
| `FixedLengthOutputStreamTest` | All 12 scenarios: exact lengths, underflow/overflow, zero writes, sink failure, completion and ownership. |
| `ChunkedOutputStreamTest` | All 9 scenarios: wire chunks, terminal failures, empty writes, close and ownership. |
| `ResponseHeadWriterTest` | Adapted status/repeated fields, framing, HEAD/304, bodyless statuses, HTTP/1.0, Date/calendar boundaries, splitting/invalid head rejection and length failures. Engine-owned headers now reject rather than normalize conflicts. Sink failures remain in both output-stream suites; loopback writing is in `ServerTest`. |
| Relevant `HeadersTest` assertions | `ModelTest`: ordered duplicates, case-insensitive lookup and immutable operations, syntax/splitting rejection. Mutable Java Map and null contracts excluded. |
| `NativeSocketSmokeTest`, transport metadata from `HttpServerContractTest` | `ServerTest`: actual bound ephemeral port, loopback exchanges, local/remote addresses, repeated headers, persistence. Runs on both targets. |
| Body limits, exact limits, caught failures, post-commit failures, oversized declared bodies | `ServerTest`: pre-dispatch 413, single/bulk/skip terminal errors, streaming failure abort. Optional limits are immutable configuration; no property snapshots. |
| Admission, queued limits, executor rejection and recovery | Connection admission covers queued and running executor tasks. Default cached pool has no task queue; caller-owned executors are supported. Active/idle/queued admission, submission rejection/recovery, queued shutdown and caller ownership tested over sockets. JVM virtual-thread execution is tested separately. |
| Stalled fixed/chunk/trailer reads; slow streaming; blocked writes | `ServerTest`: timeout/recovery for each framing state, streaming beyond individual timeouts, blocked-write expiry. Timeouts cannot be disabled; invalid timeout configuration is in `ModelTest`. |
| Initial/subsequent dripped and slow-complete heads; idle keep-alive | `ServerTest`: absolute deadlines on both heads, successful slow complete heads, idle/silent closure and handler-time exclusion. |
| Framing/persistence/pipelining, continuation and protocol/handler isolation | `ServerTest`: mixed framing pipeline order, HTTP/1.0 opt-in persistence, close tokens, lazy continuation, 417, malformed heads, 500 before commitment and abort after commitment. |
| Unread-body draining and drain deadlines | Replaced by unread-body closure without draining; pipelined bytes are never interpreted after unread payload. |
| Shutdown/idle closure/queued-request suppression | `ServerTest`: graceful active response, forced blocked-read shutdown, repeated/concurrent/callback stop, idle closure and port release. Blocked-write shutdown is also an external slowclients scenario. |
| Routing, contexts, authentication, principals, filters, wrapped exchange streams, provider/factory/JDK/null lifecycle | Excluded APIs. Embedded transport cases above retained. Raw target and `OPTIONS *` dispatch explicitly tested. CONNECT/upgrades return 501. |

Only the JVM/Java 21 and Linux Scala Native builds exercised here are covered.
macOS and Windows remain unverified. Passing bounded socket tests does not imply
sustained-load resilience. External suites and their qualifications belong in
[conformance/README.md](conformance/README.md).

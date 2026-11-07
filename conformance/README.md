# Run HTTP checks

Use Linux, Java 21, Deder 0.22.2, Scala CLI, and clang/libunwind to build the
fixtures and runner. External suites below also use Git, npm, and .NET SDK
10.0.100. External tools stay outside the library and under ignored
`conformance/out`:

```sh
mkdir -p conformance/out
deder exec -m fixture-jvm -t assembly
deder exec -m fixture-native -t nativeLink
.deder/out/fixture-native/nativeLink/fixture-native --smoke
scala --power package conformance/run.scala --server=false --assembly \
  -o conformance/out/runner.jar
```

Do not replace a runner JAR while a run using it is active. For another build,
use a different filename. Native uses release-fast mode with multithreading.
The CLI uses [mainargs](https://github.com/com-lihaoyi/mainargs) for typed options,
ujson for raw report inspection and Ant Commandline for quoted command splitting;
all three are pinned in `run.scala`. `--server` is split without a shell.

Prepare the pinned suites:

```sh
npm install --prefix conformance/out/tools deno@2.5.6
git clone https://github.com/uNetworking/h1spec.git conformance/out/h1spec
git -C conformance/out/h1spec checkout f0a5650a20c575fbea0f7179a3a9cfa50f20ba6e
git clone https://github.com/MDA2AV/Http11Probe.git conformance/out/Http11Probe
git -C conformance/out/Http11Probe checkout d50d818a0e008e9feb3bb9860ad5d9cc40342bc3
dotnet build conformance/out/Http11Probe/src/Http11Probe.Cli -c Release
git clone https://github.com/shekyan/slowhttptest.git conformance/out/slowhttptest
git -C conformance/out/slowhttptest checkout 17e8bd195e8c7fd3e0e925686c051e4d591c838f
(cd conformance/out/slowhttptest && ./configure && \
  make -j2 ACLOCAL=true AUTOCONF=true AUTOMAKE=true AUTOHEADER=true)
```

The runner verifies exact revisions and tracked cleanliness. h1spec has no
license file at its pinned revision; Http11Probe is MIT and slowhttptest is
Apache-2.0. No external suite sources or previous server results are vendored.

Run each suite with a new output directory:

```sh
java -jar conformance/out/runner.jar --suite h1spec \
  --checkout conformance/out/h1spec \
  --deno conformance/out/tools/node_modules/.bin/deno \
  --server .deder/out/fixture-native/nativeLink/fixture-native \
  --output conformance/out/native-h1spec

java -jar conformance/out/runner.jar --suite http11probe \
  --checkout conformance/out/Http11Probe \
  --probe-dll conformance/out/Http11Probe/src/Http11Probe.Cli/bin/Release/net10.0/Http11Probe.Cli.dll \
  --server .deder/out/fixture-native/nativeLink/fixture-native \
  --output conformance/out/native-probe

java -jar conformance/out/runner.jar --suite slowclients \
  --idle-drip-expectation close \
  --server .deder/out/fixture-native/nativeLink/fixture-native \
  --output conformance/out/native-slow

java -jar conformance/out/runner.jar --suite slowhttptest --duration 5 \
  --checkout conformance/out/slowhttptest \
  --server .deder/out/fixture-native/nativeLink/fixture-native \
  --output conformance/out/native-slowtool
```

For JVM, replace each `--server` value with
`'java -jar .deder/out/fixture-jvm/assembly/out.jar'` and choose fresh directories.
`--dotnet` accepts an SDK executable path. `--help` lists all options.

Readiness is capped at 30 seconds and probe invocations at 900 seconds by default.
The runner captures original logs/JSON and returns nonzero for failed verdicts,
including unscored Http11Probe failures despite its zero process exit code.
It stops owned process groups and reaps direct children on normal completion,
failure, timeout and interruption. Interrupted output is diagnostic, not a
completed result.

Slow-client scenarios cover silent/dripped heads, idle keep-alive, stalled fixed
and chunked bodies, trailers, stopped response readers, recovery and blocked-I/O
shutdown. `--capacity both|roomy|limited` selects fixture admission of 128 or 2
connections; both run by default. Four pressure connections exercise rejection
in limited mode. There is no executor queue. Body/write scenarios require seeing
two admitted active workers. `--case NAME` selects cases; `--duration 3` through
`30` controls the pressure window. `idle-drip` always takes 32 seconds.
Recovery and process shutdown must complete within five seconds. Availability
samples under pressure remain measurements, not pass/fail resilience claims.

# Run reverse-proxy checks

Build the fixtures and runner above, then use a local Linux Docker Engine with
Compose and permission to access its daemon. The proxies use host networking;
the fixture and proxy listeners bind random loopback ports. Docker Desktop,
remote Docker daemons, macOS and Windows are not covered by this setup.

```sh
java -jar conformance/out/runner.jar --suite proxy \
  --server 'java -jar .deder/out/fixture-jvm/assembly/out.jar' \
  --output conformance/out/proxy-jvm

java -jar conformance/out/runner.jar --suite proxy \
  --server .deder/out/fixture-native/nativeLink/fixture-native \
  --output conformance/out/proxy-native
```

Each invocation runs 13 raw HTTP/1.1 cases directly, through Caddy 2.10.2, and
through nginx 1.28.0 with request buffering enabled and disabled. Image digests,
Compose and configuration templates are in `proxy/`. Images are pulled if
missing. Generated configurations, image identities, request/response bytes,
proxy/fixture logs and `run.json` stay in the chosen output directory. Each run
owns a unique Compose project, removes its containers/volumes on completion or
TERM, and stops the fixture through its stop file on normal completion.

The cases cover fixed/chunked bodies containing request-shaped bytes, both
CL/TE orders, equal/conflicting/list/signed lengths, whitespace before a header
colon, malformed chunks, bare LF and folded fields. Staged chunk delivery also
exercises forwarding before a malformed body finishes. Valid controls must
succeed. Other cases may reject or normalize to the exact expected body; their
observed statuses remain in the report. Errors must close the client connection.
The fixture records handler entry and completed body reads separately, including
method, target, body and peer port. Unexpected handler entries, bodies or marker
responses fail the run. Every case checks a fresh client's recovery response;
forwarded sequences must reuse the same backend socket through each proxy.

These are bounded deployment checks for these image/configuration combinations,
not exhaustive request-smuggling coverage. Frontend HTTP/2 and TLS are excluded.
Proxy normalization or rejection does not establish direct-server acceptance.

# Run seeded fuzzing

After building the fixtures and runner above, run the same seed on each target.
Fuzzing needs no external suite checkout or tools beyond the runner and fixtures:

```sh
java -jar conformance/out/runner.jar --suite fuzz --seed 1 --iterations 1000 \
  --server 'java -jar .deder/out/fixture-jvm/assembly/out.jar' \
  --output conformance/out/jvm-fuzz-seed1

java -jar conformance/out/runner.jar --suite fuzz --seed 1 --iterations 1000 \
  --server .deder/out/fixture-native/nativeLink/fixture-native \
  --output conformance/out/native-fuzz-seed1
```

On failure, use the reported seed and case index with `--start-case INDEX
--iterations 1` and a fresh output directory. `failure.json` records the request
steps, expected responses and write sizes; `request.bin`, `response.bin` and
`server.log` retain wire diagnostics. `current.json` retains the last attempted
case if interrupted. Keep a discovered bug's seed/index and a focused regression
in `ServerTest` or the relevant codec suite when fixing it. Replay requires the
same generator version; the report records that version and the source revision.

The 48 families mix generated fixed/chunked/empty bodies, body-embedded decoy
requests, trailers, HTTP/1.0 keep-alive, staged exchanges and pipelines. Mutations
cover ambiguous framing, invalid syntax, truncation and exact/over-limit request,
header, chunk, extension, trailer and body budgets. The fixture uses small limits
to exercise boundaries without large allocations; library defaults are unchanged.
Checks require exact ordered status/body echoes, terminal closure without an
extra response, and recovery on a fresh connection after every case.

`--iterations` defaults to 256 (maximum 100000). Each connection has a five-second
deadline, response reads are capped at 64 KiB, and each generated write step is
at most 16 KiB. Terminal closure must arrive within 500 ms. `--timeout` caps the
campaign (default 900 seconds, maximum 3600), excluding startup and cleanup.
Fixture shutdown uses the stop file and must finish successfully within five
seconds. Write schedules vary between whole buffers, single bytes and random
fragments; TCP may coalesce writes. This is deterministic grammar/stateful
testing, not coverage-guided fuzzing, exhaustive fragmentation or a load test.

# Run sustained mixed traffic

Build the fixtures and runner above, then run both admission modes on each target:

```sh
java -jar conformance/out/runner.jar --suite sustained --duration 60 --connections 16 \
  --server 'java -jar .deder/out/fixture-jvm/assembly/out.jar' \
  --output conformance/out/jvm-sustained

java -jar conformance/out/runner.jar --suite sustained --duration 60 --connections 16 \
  --server .deder/out/fixture-native/nativeLink/fixture-native \
  --output conformance/out/native-sustained
```

`--duration` is 10–600 seconds per mode (default 60), excluding preflight and
recovery. `--connections` is 4–256 renewing pressure clients (default 16).
`--capacity both|roomy|limited` selects connection limits of 128 or 2; both run
by default. Four legitimate clients run ordinary persistent requests, slow
complete heads, slow fixed/chunked uploads and slow fixed/chunked downloads.
Pressure clients renew dripped heads/bodies, stalled trailers and unread large
responses. An admitted body reader and response writer are required before
the competing clients start.

The fixture uses owned server workers and one-second head/read/write timeouts;
its successful uploads/downloads take about 1.6 seconds with 100 ms between
blocks. Production defaults are unchanged. Every legitimate path must pass
before and after pressure. With enough capacity for all clients, each role must
make progress in every full ten-second window. Admission/transport failures
under pressure are counted; malformed or corrupted responses fail the run.
Each ordinary transaction contains eight requests on one connection.

`run.json` contains per-role attempts, successes, transport failures and maximum
successful transaction latency, plus one-second RSS/thread/fd samples. Pressure
successes count completed client hold cycles, not successful HTTP responses or
proof that the server kept those connections open. These are measurements,
not throughput or memory bounds. Cached worker threads may remain after clients
disconnect. Ordinary recovery must complete within five seconds; all legitimate
paths are then checked again. Client connections have five-second I/O deadlines;
all load threads are cancelled, sockets closed and threads joined before fixture
shutdown. Failure/interruption retains an incomplete report and requests file
shutdown, with process-group termination as a fallback.

# Run Nmap's Slowloris check

With Nmap on `PATH`, start a fresh fixture in one terminal:

```sh
mkdir conformance/out/nmap-native
HTTP_CONFORMANCE_PROFILE=nmap \
HTTP_CONFORMANCE_STOP_FILE="$PWD/conformance/out/nmap-native/stop" \
  .deder/out/fixture-native/nativeLink/fixture-native
```

In another terminal, replace `PORT` with the printed `LISTENING` port:

```sh
curl --fail --max-time 2 http://127.0.0.1:PORT/probe
nmap --unprivileged -sT -Pn -n -p PORT \
  --script +http-slowloris-check --script-args vulns.showall=true \
  --script-timeout 45s --host-timeout 60s \
  -oA conformance/out/nmap-native/scan 127.0.0.1
curl --fail --max-time 2 http://127.0.0.1:PORT/probe
touch conformance/out/nmap-native/stop
```

For JVM, start `java -jar .deder/out/fixture-jvm/assembly/out.jar` instead and use
a fresh directory. Repeat with `HTTP_CONFORMANCE_PROFILE=slowclients` for the
default ten-second head deadline. The `nmap` profile uses fifteen seconds so
the script's delayed header arrives before the absolute deadline.

`+` forces execution on an otherwise unrecognized ephemeral HTTP port;
`vulns.showall=true` requests explicit `NOT VULNERABLE` output. Require that
script result in the XML report. Missing output, a skipped script, or a zero
process exit alone is not a passing verdict. Check normal requests before/after
the scan and wait for the fixture's completed-stop marker.

This [NSE script](https://nmap.org/nsedoc/scripts/http-slowloris-check.html)
opens two incomplete requests and adds a header to one after ten seconds. Its
timing comparison is a timeout-extension heuristic, not an exhaustion test.
Read it alongside the sustained mixed-traffic results above; neither a negative
nor a positive heuristic verdict establishes a full denial-of-service outcome.

# Interpret results

h1spec uses one socket read and a 500 ms timeout per case. Valid fragmented
responses can fail that assumption; confirm with a complete response reader.
Http11Probe runs Compliance, Smuggling, MalformedInput, Normalization and
Capabilities. Cookies and WebSockets are outside the fixture/core. Caching,
compression and conditional-response warnings describe application capabilities.
Its `total` includes category-filtered skips: count executed cases as
`total - skipped`, and inspect raw verdicts as well as scored summaries.

An empty Host remains accepted. The probe's `COMP-HOST-EMPTY-VALUE` expects
rejection, but [RFC 9112 section 3.2](https://www.rfc-editor.org/rfc/rfc9112.html#section-3.2)
requires an empty Host when the target authority is missing or undefined, and
requires rejection of missing, repeated or invalid Host fields. We interpret
this as permitting the empty value rather than requiring blanket rejection. This disagreement remains visible as a failed
probe and a nonzero runner exit, without changing suite expectations.

Local results are recorded below. Remote CI and other operating systems require
separate verification. These are bounded local diagnostics, not production
capacity, performance, or complete RFC-conformance proof.

The idle-drip check accepts either EOF/reset or a fully validated bodyless 408
followed by EOF. It records the 408 wire response; any premature success or
other response fails. This adapts the source runner's close-only assumption to
potateh's explicit request-head timeout response.

# Local baseline (2026-11-07)

Linux x86_64, Temurin Java 21.0.10, Deder 0.22.2, Scala 3.9.0,
Native 0.5.12 release-fast with multithreading:

| Check | JVM | Native |
| --- | --- | --- |
| Shared MUnit codec and socket suites | Pass | Pass |
| Standalone release fixture smoke | HTTP served | Pass |
| h1spec | 33/33 | 33/33 |
| Http11Probe (five categories, raw verdicts) | 155 Pass, 40 Warn, 1 Fail | 155 Pass, 40 Warn, 1 Fail |
| slowhttptest (three modes, two admission capacities) | 6/6 | 6/6 |
| slowclients (ten cases, two admission capacities) | 20/20 | 20/20 |
| Seeded fuzzing (48 families; seeds 1 and 8675309) | 2000/2000 | 2000/2000 |
| Sustained mixed traffic (60 seconds per capacity, 16 pressure + 4 legitimate clients) | 2/2 | 2/2 |
| Nmap `http-slowloris-check` (10-/15-second head deadlines) | 2 explicit negatives | 2 explicit negatives |
| Proxy boundaries (13 cases × direct/Caddy/nginx buffered/nginx streaming) | 52/52 | 52/52 |

The proxy baseline used local changes based on `c2cd69f`, with the image digests
and configurations in `proxy/`. Both targets produced the same outcomes:
Caddy forwarded CL+TE and equal duplicate lengths with the exact decoded body;
nginx rejected them. Both proxies normalized bare LF; Caddy also normalized the
folded-field case. Direct potateh rejected these inputs. Invalid chunks produced
400 directly/through nginx and 502 through Caddy; Caddy and staged nginx streaming
could enter the backend handler before rejecting the incomplete body. No decoy
became a request, all follow-up markers/recovery responses matched, and proxy
backend reuse was verified. Normal shutdown completed for every fixture.
An injected proxy method rewrite failed the exact-response assertion and exited
nonzero; a TERM check removed the owned containers and stopped the active fixture.
Reports are under ignored `conformance/out/proxy-{jvm,native}-verified`.

Nmap 7.94SVN reported `NOT VULNERABLE` for both head deadlines on both targets;
its debug timing differences rounded to zero. A temporary control server that
deliberately extends its timeout reported `LIKELY VULNERABLE` with an 18-second
difference. Ordinary HTTP worked before/after each scan and every fixture
completed file-triggered shutdown. Raw XML/logs, commands, binary version and
NSE script SHA-256 are under ignored `conformance/out/nmap-evaluation`. These
heuristic results complement the measured admission/recovery checks below.

The sustained baseline used local changes based on `f0fc4a7`. All legitimate
roles progressed throughout the spare-capacity runs without transport failures.
The two-connection runs measured admission/transport failures under pressure;
both targets recovered ordinary requests within 8 ms after clients stopped,
then passed all slow/streaming recovery checks and completed fixture shutdown.
This one-minute workload per mode does not establish production capacity or
long-term resource stability. Raw reports are under ignored
`conformance/out/sustained-{jvm,native}-final`.

The fuzzing baseline used local changes based on `bfbc050`, with 1000 cases per
seed and recovery after every case. Both fixtures completed file-triggered
shutdown. A deliberately faulty temporary fixture verified nonzero failure
reporting and replay; no server defect was found by these bounded campaigns.

Http11Probe executed 196 distinct tests per target, excluding 17 cookie/WebSocket
cases. Its one failure is the empty-Host disagreement above; both runner exits
remain 1. The original scored/unscored verdicts and warnings remain untouched.

Raw local artifacts are ignored under `conformance/out`: `jvm-h1spec-ready`,
`native-h1spec-ready`, `jvm-probe-ready`, `native-probe-ready`, `jvm-slowtool-ready`,
`native-slowtool-ready`, and the final slowclients runs `jvm-slow-ready` and
`native-slow-ready`. Earlier harness-development runs are diagnostics only. The
final runner checks the new fixture's completed-stop marker and allows only a
validated 408 plus EOF as an early idle-drip deadline rejection.

Native TERM cleanup of the parked long-running fixture emitted an
`IllegalMonitorStateException` in Scala Native 0.5.12 `Runtime.runHooksConcurrent`
and `Thread.join`, after the server printed its completed-stop marker. This is
an outstanding portability limitation; successful HTTP verdicts do not establish
clean signal shutdown. Explicit `Server.stop()`, the release smoke, and
file-triggered slow-client fixture shutdown are checked separately. No release
readiness or remote CI claim follows from these local results.

The same exception reproduced in three of three TERM trials without potateh:
a standalone Scala 3.9.0 / Native 0.5.12 multithreaded program containing only
`Runtime.addShutdownHook(new Thread(() => println("HOOK FINISHED")))` and
`new java.util.concurrent.CountDownLatch(1).await()`. Each hook finished before
the runtime exception and process exit 1. This isolates the parked-main TERM
failure from the HTTP server. It remains a Native runtime limitation rather
than a passing shutdown result for that fixture profile.

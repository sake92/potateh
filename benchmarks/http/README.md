# HTTP benchmark

This is a small end-to-end fixture for comparing Potateh revisions and
executors on the same machine. The default is a fixed platform-thread executor
with one worker per keep-alive connection. `POTATEH_EXECUTOR=cached` uses one
platform thread per admitted connection, matching Potateh's default executor.
Set `POTATEH_EXECUTOR=virtual` to use one JVM virtual thread per admitted
connection. `run.sh` uses `wrk` with 16 load-generator threads and 16 connections
by default.

Build the JVM server:

```sh
deder exec -m http-benchmark-jvm -t compile
```

Run three ten-second measurements:

```sh
SERVER_BIN=benchmarks/http/server-jvm.sh \
  POTATEH_EXECUTOR=virtual \
  benchmarks/http/run.sh
```

Build the Scala Native server when comparing revisions on the native default:

```sh
deder exec -m http-benchmark-native -t nativeLink
```

```sh
SERVER_BIN=.deder/out/http-benchmark-native/nativeLink/http-benchmark-native \
  benchmarks/http/run.sh
```

Override `LOAD_THREADS`, `CONNECTIONS`, `DURATION`, `RUNS`, `POTATEH_EXECUTOR`,
or `PATH_SUFFIX` to compare another workload. `POTATEH_THREADS` applies only to
the fixed executor. A fixed pool must be at least as large as the number of
persistent connections; otherwise accepted connections remain queued behind
workers blocked on reads.

Set `SERVER_CPUS` and `LOAD_CPUS` to separate CPU sets for less noisy A/B runs:

```sh
SERVER_CPUS=0-3 \
LOAD_CPUS=4-7 \
SERVER_BIN=.deder/out/http-benchmark-native/nativeLink/http-benchmark-native \
  benchmarks/http/run.sh
```

`wrk` measures one outstanding request per connection. Use `gcannon` when
evaluating pipelined throughput:

```sh
LOAD_TOOL=gcannon \
GCANNON_BIN=/path/to/gcannon \
POTATEH_THREADS=64 \
CONNECTIONS=64 \
LOAD_THREADS=16 \
PIPELINE=16 \
SERVER_BIN=.deder/out/http-benchmark-native/nativeLink/http-benchmark-native \
  benchmarks/http/run.sh
```

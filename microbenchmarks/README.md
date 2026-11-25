# Microbenchmarks

Measures request-head parsing, URI construction, and response-head preparation on Scala Native. The response cases cover empty, typical, many-header, and chunked responses, with automatic and supplied Date fields. The request-head cases cover origin, escaped and absolute targets, plus CONNECT authorities. They parse in-memory input and include target and Host validation; they do not include socket reads. The URI cases measure the URI constructions used for common origin targets and Host values, not the complete validation logic.

Each case runs three warmup batches and five measured batches of 50,000 operations for request and URI cases or 100,000 for response cases. Results are elapsed nanoseconds per operation; a checksum consumes the results.

From the repository root, build an optimized executable and run it:

```sh
deder shutdown
deder exec -t fullNativeLink -m micro-benchmarks
deder shutdown
./.deder/out/micro-benchmarks/fullNativeLink/micro-benchmarks
```

To use a Scala Native snapshot, set `POTATEH_SCALA_NATIVE_VERSION` for the build command. Clean the affected Native modules when switching versions. Run on a quiet machine and compare results on the same hardware; on Linux, prefix the executable with `taskset -c 4` to pin it to a logical CPU. These simple microbenchmarks do not measure allocations or replace end-to-end HTTP measurements.

## JVM header benchmarks

From the repository root, list the JVM benchmarks or run them with the GC profiler to measure time and bytes allocated per operation:

```sh
deder exec -m header-jmh -t jmhList
deder exec -t jmhRun -m header-jmh -- -bm avgt -tu ns -wi 2 -i 3 -w 500ms -r 500ms -f 2 -prof gc '.*HeaderBenchmark.*'
```

The benchmark measures case-insensitive lookup and list parsing separately for absent, single, and repeated fields. Fixture construction happens before measurement. Use the same JDK for before-and-after comparisons.

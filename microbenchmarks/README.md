# Microbenchmarks

Measures request-head parsing and URI construction on Scala Native. The request-head cases cover origin, escaped and absolute targets, plus CONNECT authorities. They parse in-memory input and include target and Host validation; they do not include socket reads. The URI cases measure the URI constructions used for common origin targets and Host values, not the complete validation logic.

Each case runs three warmup batches and five measured batches of 50,000 operations. Results are elapsed nanoseconds per operation; a checksum consumes the results.

From the repository root, build an optimized executable and run it:

```sh
deder shutdown
deder exec -t fullNativeLink -m micro-benchmarks
deder shutdown
./.deder/out/micro-benchmarks/fullNativeLink/micro-benchmarks
```

To use a Scala Native snapshot, set `POTATEH_SCALA_NATIVE_VERSION` for the build command. Clean the affected Native modules when switching versions. Run on a quiet machine and compare results on the same hardware; on Linux, prefix the executable with `taskset -c 4` to pin it to a logical CPU. These simple microbenchmarks do not measure allocations or replace end-to-end HTTP measurements.

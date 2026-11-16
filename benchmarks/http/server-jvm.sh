#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/../.." && pwd)
cache_root=${XDG_CACHE_HOME:-${HOME}/.cache}/coursier
scala_version=3.9.0
scala3_jar=$(find "${cache_root}" \
  -path "*/org/scala-lang/scala3-library_3/${scala_version}/scala3-library_3-${scala_version}.jar" \
  -print -quit)
scala2_jar=$(find "${cache_root}" \
  -path "*/org/scala-lang/scala-library/${scala_version}/scala-library-${scala_version}.jar" \
  -print -quit)

if [[ -z "${scala3_jar}" || -z "${scala2_jar}" ]]; then
  echo "Scala ${scala_version} compiler dependencies were not found in ${cache_root}" >&2
  exit 1
fi

classpath="${repo_root}/.deder/out/http-benchmark-jvm/compile/classes"
classpath+=":${repo_root}/.deder/out/potateh-jvm-3.9.0/compile/classes"
classpath+=":${scala3_jar}:${scala2_jar}"

read -r -a java_opts <<< "${JAVA_OPTS:-}"

exec "${JAVA_BIN:-java}" "${java_opts[@]}" -cp "${classpath}" \
  ba.sake.potateh.benchmark.HttpBenchmarkServer

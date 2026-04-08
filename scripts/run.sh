#!/usr/bin/env bash
# Run the packaged fat JAR from target/ (build first with scripts/build.sh).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${ROOT}/target/mosaic-client-1.0-SNAPSHOT.jar"

if [[ ! -f "$JAR" ]]; then
  echo "run.sh: jar not found: ${JAR}" >&2
  echo "run.sh: run ${ROOT}/scripts/build.sh first." >&2
  exit 1
fi

cd "${ROOT}"
exec java -jar "$JAR" "$@"

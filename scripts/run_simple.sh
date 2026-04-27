#!/usr/bin/env bash
# This script was written with an LLM. The LLM was prompted to write a build script similar to build.sh, which didn't
# package the application. It is used for testing, since the build is faster without packaging.

# Build and run the Mosaic client using javafx:run, avoiding fat JAR packaging.
# Ensures dependencies are compiled and available.

# Optional: ./run.sh [--debug] [--frontend] [--config <file>] [extra mvn args...]
# Optional env: MAVEN=mvn  QUIET=1  DEBUG=1

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MAVEN="${MAVEN:-mvn}"
MVN_EXTRA=()
if [[ "${QUIET:-}" == "1" ]]; then
  MVN_EXTRA+=(-q)
fi

GOAL="javafx:run"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug)
      GOAL="javafx:run@debug"
      shift
      ;;
    --frontend)
      GOAL="javafx:run@run-frontend"
      shift
      ;;
    --config)
      if [[ -n "${2:-}" ]]; then
        # Ensure path is absolute for Maven
        CONFIG_PATH="$2"
        if [[ "$CONFIG_PATH" != /* && "$CONFIG_PATH" != ?:* ]]; then
          CONFIG_PATH="$(pwd)/$CONFIG_PATH"
        fi
        MVN_EXTRA+=("-Djavafx.args=--config $CONFIG_PATH")
        shift 2
      else
        echo "Error: --config requires a file path"
        exit 1
      fi
      ;;
    *)
      break
      ;;
  esac
done

echo "run.sh: ensuring dependencies are ready..."

if [[ -f "${ROOT}/exchange-server/pom.xml" ]]; then
  echo "run.sh: installing org.rumor:rumor (exchange-server) into local Maven repo..."
  "${MAVEN}" "${MVN_EXTRA[@]}" -f "${ROOT}/exchange-server/pom.xml" install -DskipTests
fi

DEMO_YAML="${ROOT}/target/mosaic.yml"
if [[ ! -f "${DEMO_YAML}" ]]; then
  mkdir -p "${ROOT}/target"
  cat > "${DEMO_YAML}" <<'EOF'
# Mosaic configuration
# Lives next to the runnable jar (or project root in dev).

# Network port this node listens on
port: 7000

# Node type: basic | seed | eviction | master
node-type: basic

# Seed node to bootstrap into the cluster (host:port)
# Leave empty to start as a standalone node.
seed:

# Debug snapshot file (written periodically while running)
debug-file: mosaic-debug.txt

# Set to true to enable periodic debug snapshots
debug-enabled: false
EOF
  echo "run.sh: wrote demo ${DEMO_YAML}"
fi

echo "run.sh: executing ${GOAL}..."
# Using compile to ensure everything is up to date before running
"${MAVEN}" "${MVN_EXTRA[@]}" -f test-pom.xml compile "${GOAL}" "$@"

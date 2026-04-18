#!/usr/bin/env bash
# Build the Mosaic client fat JAR for the host OS, remove any previous jar from target/,
# and add a demo mosaic.yml in target/ when none exists yet.

# Optional: ./build.sh [--debug] [javafx-platform] [extra mvn args...]
#   Platform: linux | linux-aarch64 | win | win-x86 | mac | mac-aarch64
# Optional env: MAVEN=mvn  QUIET=1  JAVAFX_PLATFORM=...  DEBUG=1

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MAVEN="${MAVEN:-mvn}"
MVN_EXTRA=()
if [[ "${QUIET:-}" == "1" ]]; then
  MVN_EXTRA+=(-q)
fi

if [[ "${1:-}" == "--debug" ]]; then
  DEBUG=1
  shift
fi

if [[ "${DEBUG:-}" == "1" ]]; then
  MVN_EXTRA+=(-Ddebug=true)
fi

JAR="${ROOT}/target/mosaic-client-1.0-SNAPSHOT.jar"
JAR_SHADED="${ROOT}/target/mosaic-client-1.0-SNAPSHOT-shaded.jar"

detect_javafx_platform() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m | tr '[:upper:]' '[:lower:]')"

  case "${os}-${arch}" in
    Linux-x86_64|Linux-amd64)           echo linux ;;
    Linux-aarch64|Linux-arm64)          echo linux-aarch64 ;;
    Darwin-x86_64|Darwin-amd64)        echo mac ;;
    Darwin-arm64|Darwin-aarch64)        echo mac-aarch64 ;;
  esac

  case "${os}" in
    CYGWIN_*|MINGW*|MSYS*)
      case "${arch}" in
        x86_64|amd64)                   echo win ;;
        i686|i386|x86)                  echo win-x86 ;;
      esac
      ;;
  esac
}

if [[ "${1:-}" =~ ^(linux|linux-aarch64|win|win-x86|mac|mac-aarch64)$ ]]; then
  JAVAFX_PLATFORM="$1"
  shift
elif [[ "${JAVAFX_PLATFORM:-}" ]]; then
  :
else
  JAVAFX_PLATFORM="$(detect_javafx_platform || true)"
fi

if [[ -z "${JAVAFX_PLATFORM}" ]]; then
  echo "build.sh: could not map OS/arch to a JavaFX platform." >&2
  echo "  uname: $(uname -s) / $(uname -m)" >&2
  echo "Set JAVAFX_PLATFORM or pass one as the first argument (e.g. linux, win, mac, mac-aarch64)." >&2
  exit 1
fi

mkdir -p "${ROOT}/target"

echo "build.sh: removing previous packaged jar (if any)..."
rm -f "${JAR}" "${JAR_SHADED}"

echo "build.sh: building for JavaFX platform: ${JAVAFX_PLATFORM}"

if [[ -f "${ROOT}/exchange-server/pom.xml" ]]; then
  echo "build.sh: installing org.rumor:rumor (exchange-server) into local Maven repo..."
  "${MAVEN}" "${MVN_EXTRA[@]}" -f "${ROOT}/exchange-server/pom.xml" install -DskipTests "$@"
fi

echo "build.sh: packaging mosaic-client..."
"${MAVEN}" "${MVN_EXTRA[@]}" -f "${ROOT}/pom.xml" package -DskipTests \
  -Djavafx.platform="${JAVAFX_PLATFORM}" "$@"

if [[ ! -f "${JAR}" ]]; then
  echo "build.sh: expected jar was not produced: ${JAR}" >&2
  exit 1
fi

DEMO_YAML="${ROOT}/target/mosaic.yml"
if [[ ! -f "${DEMO_YAML}" ]]; then
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
  echo "build.sh: wrote demo ${DEMO_YAML}"
else
  echo "build.sh: kept existing ${DEMO_YAML}"
fi

echo "build.sh: done — ${JAR}"

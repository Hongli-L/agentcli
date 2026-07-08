#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Building AgentCLI fat-jar..."
mvn -q clean package -DskipTests=false

jar="target/agentcli-1.0.0.jar"
if [[ ! -f "$jar" ]]; then
  echo "Build failed: $jar not found" >&2
  exit 1
fi

echo "Done: $jar"
echo "Run: java -jar $jar --help"

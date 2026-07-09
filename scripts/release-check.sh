#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> [1/4] mvn clean package + tests"
mvn -q clean package -DskipTests=false -DforkCount=0

echo "==> [2/4] jar smoke test"
jar="target/agentcli-1.0.0.jar"
[[ -f "$jar" ]] || { echo "Missing $jar" >&2; exit 1; }
java -jar "$jar" --version >/dev/null

echo "==> [3/4] legacy identifier scan"
patterns=("com.paicli" "paicli_" "~/.paicli" " paicli ")
roots=(src README.md pom.xml .env.example Dockerfile)
hits=0
for root in "${roots[@]}"; do
  [[ -e "$root" ]] || continue
  if [[ -d "$root" ]]; then
    mapfile -t files < <(find "$root" -type f \( -name '*.java' -o -name '*.md' -o -name '*.xml' -o -name '*.yml' -o -name '*.yaml' -o -name '*.properties' \))
  else
    files=("$root")
  fi
  for file in "${files[@]}"; do
    [[ "$file" == *SourceQualityScanTest.java ]] && continue
    content=$(tr '[:upper:]' '[:lower:]' < "$file")
    for p in "${patterns[@]}"; do
      if [[ "$content" == *"$p"* ]]; then
        echo "$file -> $p"
        hits=1
      fi
    done
  done
done
[[ "$hits" -eq 0 ]] || { echo "Legacy identifier scan failed" >&2; exit 1; }

echo "==> [4/4] secret leak scan in git history"
if git log -p --all 2>/dev/null | grep -qE 'sk-[a-zA-Z0-9]{20,}|Bearer [a-zA-Z0-9._-]{20,}'; then
  echo "Possible secret in git history" >&2
  exit 1
fi

echo ""
echo "All checks passed."

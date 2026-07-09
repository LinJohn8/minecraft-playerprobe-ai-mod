#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft/versions/26.1.2-Fabric}"
LIB_DIR="${LIB_DIR:-$HOME/Library/Application Support/minecraft/libraries}"
JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/mojang-25.0.1.bundle/Contents/Home}"
MOD_VERSION="${MOD_VERSION:-1.0.0-v0.3}"
MOD_NAME="playerprobe-${MOD_VERSION}.jar"

cd "$PROJECT_DIR"

rm -rf build/classes/main build/resources/main build/libs
mkdir -p build/classes/main build/resources/main build/tmp build/libs finalMod

jq -r '.libraries[] | if .downloads.artifact.path then .downloads.artifact.path else (.name | split(":") | . as $p | ($p[0] | gsub("\\."; "/")) + "/" + $p[1] + "/" + $p[2] + "/" + $p[1] + "-" + $p[2] + (if length > 3 then "-" + $p[3] else "" end) + ".jar") end' \
  "$MC_DIR/.parent/26.1.2.json" \
  "$MC_DIR/26.1.2-Fabric.json" \
  | while IFS= read -r path; do
      test -f "$LIB_DIR/$path" && printf '%s\n' "$LIB_DIR/$path"
    done > build/tmp/classpath.txt

{
  printf '%s\n' "$MC_DIR/26.1.2-Fabric.jar"
  cat build/tmp/classpath.txt
} | awk '!seen[$0]++' | paste -sd ':' - > build/tmp/classpath.joined

find src/main/java -name '*.java' | sort > build/tmp/sources.txt

"$JAVA_HOME/bin/javac" \
  --release 25 \
  -encoding UTF-8 \
  -cp "$(cat build/tmp/classpath.joined)" \
  -d build/classes/main \
  @build/tmp/sources.txt

sed 's/${version}/'"${MOD_VERSION}"'/g' src/main/resources/fabric.mod.json > build/resources/main/fabric.mod.json
jar --create --file "build/libs/${MOD_NAME}" -C build/classes/main . -C build/resources/main .
cp "build/libs/${MOD_NAME}" "finalMod/${MOD_NAME}"

printf 'Built %s\n' "$PROJECT_DIR/finalMod/${MOD_NAME}"

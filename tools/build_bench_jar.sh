#!/usr/bin/env bash
# Builds bench/adaptive-itr-spm-bench.jar from the committed sources and stamps it with
# build-info.properties (source commit, source tree id, javac, build time).
# Refuses to build from modified sources unless --allow-dirty is given (probe builds only:
# the stamp then says source_tree=MODIFIED and the jar must not be used for measured runs).
set -euo pipefail
cd "$(dirname "$0")/.."
allow=0
if [ "${1:-}" = "--allow-dirty" ]; then allow=1; fi
dirty=$(git status --porcelain -- src pom.xml | wc -l | tr -d ' ')
if [ "$dirty" != "0" ] && [ "$allow" = "0" ]; then
  echo "refusing to build: $dirty modified or untracked file(s) under src/ or pom.xml; commit first (or --allow-dirty for a probe build)" >&2
  git status --porcelain -- src pom.xml >&2
  exit 1
fi
mvn -B -q -o clean package -DskipTests
tmp=$(mktemp -d)
{
  echo "source_commit=$(git rev-parse HEAD)"
  echo "source_tree_id=$(git rev-parse HEAD:src)"
  if [ "$dirty" = "0" ]; then echo "source_tree=clean"; else echo "source_tree=MODIFIED ($dirty files)"; fi
  echo "built_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "javac=$(javac -version 2>&1)"
  echo "release=8"
} > "$tmp/build-info.properties"
jar uf target/adaptive-itr-spm.jar -C "$tmp" build-info.properties
cp target/adaptive-itr-spm.jar bench/adaptive-itr-spm-bench.jar
( cd bench && shasum -a 256 adaptive-itr-spm-bench.jar > adaptive-itr-spm-bench.jar.sha256 )
cat "$tmp/build-info.properties"
cat bench/adaptive-itr-spm-bench.jar.sha256
rm -rf "$tmp"

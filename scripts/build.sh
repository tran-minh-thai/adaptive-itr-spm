#!/usr/bin/env bash
# Build fat jar via Maven Shade plugin.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -DskipTests=true clean package

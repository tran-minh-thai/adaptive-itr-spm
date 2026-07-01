#!/usr/bin/env bash
# Convenience wrapper: run the full paper pipeline in one shot.
exec "$(dirname "$0")/run.sh" all "$@"

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Fast storage suites. Use a direct component task when filtering by class.
exec ./gradlew :object-store:test :generated-store:test "$@"

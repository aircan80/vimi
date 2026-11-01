#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Fast layer 1 suites. Use a direct component task when filtering by class.
exec ./gradlew :object-store:test :generated-store:test :network-clients:test "$@"

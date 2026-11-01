#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Fast component tests by default; pass Gradle options such as --tests '*FormatTest'.
exec ./gradlew :object-store:test "$@"

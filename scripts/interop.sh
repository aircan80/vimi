#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
arguments=(:object-store:interopTest :generated-store:interopTest)
if [[ -n "${DOTNET:-}" ]]; then
  arguments+=("-Pdotnet=$DOTNET")
fi
exec ./gradlew "${arguments[@]}" "$@"

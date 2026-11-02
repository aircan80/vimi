#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
arguments=(:object-store:interopTest :generated-store:interopTest :builds:interopTest :files:interopTest :formats-assets:interopTest :formats-tables:interopTest :concepts-core:interopTest :concepts-items:interopTest :concepts-creatures:interopTest :concepts-effects:interopTest)
if [[ -n "${DOTNET:-}" ]]; then
  arguments+=("-Pdotnet=$DOTNET")
fi
exec ./gradlew "${arguments[@]}" "$@"

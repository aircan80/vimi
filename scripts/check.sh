#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Fast component suites. Use a direct component task when filtering by class.
exec ./gradlew :object-store:test :generated-store:test :network-clients:test :object-access:test :builds:test :files:test :formats-assets:test :formats-tables:test :concepts-core:test :concepts-items:test :concepts-creatures:test :concepts-talents:test :concepts-races:test :concepts-effects:test :eg-link:test :polestar:test "$@"

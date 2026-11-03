# Comparison foundation validation

The Foundation slice was independently reviewed and merged through #131 after
Linux, macOS and Windows unit/integration checks and Linux crash/compatibility
checks. This evidence covers inventory comparison, not semantic entity changes.

## Deterministic checks

- 35 initial comparison core/input-adapter tests cover ordered identity, partial
  membership, field states, quotas, paging, cancellation and ownership.
- Coordinator fixtures cover pair reuse, canceled opening/reads, failed second
  opening, incompatible input, rejected admission, close failures and stale scratch.
- Native SQLite/pack fixtures use forbidden CDN/listfile clients. They verify exact
  prepared table receipt reuse, changed-key rejection, unavailable local metadata
  and independence from an existing browsed build context.
- Authored JavaFX checks cover explicit start, captured pair identity, same-version
  distinguishing configurations, partial coverage reasons, page retry, cursor
  history, narrow controls, history-based defaults and stale opening cleanup.

## Read-only interpretation of actual local inputs

A GUI run against locally prepared Forever 1.60.1.70707, enUS completed a Files
self-comparison over 1,245,599 identities. Known-equal records were omitted. Ten
uncertain records remained: their decoded sizes were unavailable rather than
invented as zero. Identical content evidence does not resolve those unknown sizes.
The UI retained the uncertainty and unknown membership coverage.

A Tables self-comparison completed over 1,270 cached known-definition outcomes.
This is preparation-inventory coverage, not a statement that every table or row is
readable. The screen kept inventory scope and per-field limitations visible.

Leaving Compare returned to Builds and removed the session scratch directories.
No actual Blizzard payloads, local settings or game screenshots are committed as
fixtures. The authored screen image uses invented inputs.

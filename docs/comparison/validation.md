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

## Content cursor performance proof

A read-only control against the same Forever inputs enumerated 65,536 identical
ID/size/CKey records through both APIs. Existing metadata listing took 13,238 ms
and requested presence for 65,536 keys plus 16 name-source batches. The dedicated
content cursor took 1,693 ms with zero presence/name requests, about 7.8 times
faster for this cursor prefix. Canonical sequence digests matched. This is a cursor
measurement, not a claim that every complete comparison runs 7.8 times faster.

A full content-cursor pass enumerated all 1,245,599 records in 33,800 ms with no
presence/name calls. No payload acquisition or live application mutation was
performed by the benchmark. Files checks include 90 unit tests and integration/
style checks; comparison checks now include 37 core/adapter tests. Unknown sizes
remain uncertain and membership remains UNKNOWN on both fast and fallback paths.

## Selected raw-table slice

The comparison module now passes 104 component/adapter tests, including bounded
external sorting, combined core/sorter quotas, callback/delivery ownership, raw
field semantics and captured-row conversion. Table component checks pass 96 unit
tests plus two integration checks. App coordinator checks include matching and
incompatible pair admission, cancellation before sorting, and budget close order.

A native SQLite/pack fixture rejects a missing graph, then reads three authored
cached Item rows with IDs 7, 8 and 4294967295. The original source payload remains
encrypted/unreadable. Successful comparison performs zero acquisition or object
writes and leaves the browsed build registry unchanged. Source receipts and
structural fingerprints are asserted.

A live GUI check against Forever 1.60.1.70707 enUS compared all 58 readable
ChrRaces rows from its existing prepared graph. The run completed with uncertified
membership and retained uncertainty rather than claiming gameplay changes. Fields
without a supplied issue channel remain unknown; bounded observed cell evidence
is available for inspection. No actual game rows or screenshots are committed.

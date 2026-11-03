# Placement source service checkpoint

Status: proposed API/lifetime contract, awaiting review before production service code. Raw parser/MAID prerequisite `3b5c589` is separately approved and intentionally outside the current visible-feature release bundle. No transform, visibility, material or render policy is added here.

## Evidence checkpoint

Private read-only-original/scratch-acquisition proof on wowf 1.60.1.70755 enUS, Eastern Kingdoms:

| Filename tile | Recorded obj0 ID | Content key | Bytes | MDDF / MODF |
| --- | --- | --- | --- | --- |
| 32,32 | 777948 | ebff6b81c3d73d404babbf90bdd3b5f9 | 39,192 | 783 / 8 |
| 32,33 | 778148 | 121771fe16b7511762c8a25aade3dabb | 56,396 | 1,206 / 7 |
| 33,32 | 777953 | 65d943351d91cc92d039bf7a4e522f1c | 42,272 | 862 / 12 |

All streams were complete and nonprotected, bounded before allocation, and content keys matched before/after reads. M2 raw flags were 64 and 576 (`0x40`, `0x240`); WMO flags were 12 (`0xC`). All sampled records explicitly selected direct FileDataID references. These additional flag bits are preserved facts, not admission approval. A warm fresh bootstrap found 19 identical repeated placements across these three sources and zero conflicts, yielding 2,859 distinct kind/unique-ID identities. No obj1 merge, model acquisition, transform or visual correctness claim follows.

Logs and the private Java bootstrap are under `/tmp/vimi-world-placement-private/`; raw files remain in that scratch object store, outside Git. Original library opened through `ObjectStore.openReadOnly`; scratch acquisition and generated stores are separate from the running application and other proof stores.

## Proposed public surface

`BuildWorld` gains an optional method with an explicit unsupported default, preserving existing fake/custom providers:

```java
default CompletableFuture<WorldPlacementSource.Result> placements(
        WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token);
```

The implementation is a build-scoped helper borrowed by `WorldService`, not a new global source owner. Proposed immutable result types:

```java
enum State { READ, UNAVAILABLE }
record Receipt(BuildRef build, String locale, long mapId,
               WorldFormats.Tile tile, long obj0Id,
               String contentKey, String parserRevision) {}
record Result(State state, Receipt receipt,
              WorldPlacements.Metadata facts, String issue) {}
```

`READ` means complete raw metadata was parsed; it does not mean a model, reference, geometry or appearance was resolved. It requires nonnull receipt/facts and no failure issue. `UNAVAILABLE` requires an explicit reason and null facts; it must never manufacture an empty placement list or a verified receipt. An exact valid MVER18 stream with zero placements returns `READ` with empty immutable facts. Cancellation completes exceptionally as cancellation, never as unavailable. Queue saturation and closed-workspace rejection also complete exceptionally, distinguishable from a bad source.

Receipt includes the requested map/tile namespace and the recorded obj0 dependency only. `obj1Id` is retained in Tile but never read or merged. File ID zero means no recorded obj0 source. There is no map-directory fallback or filename probing. The default provider result is unavailable with a clear unsupported reason and no acquisition.

## Read and cache transaction

1. Check request/lifetime cancellation and required nonnull inputs on the owned worker.
2. Use only `FileRef.id(tile.obj0Id())`. Before opening, require a found source, matching FileDataID, a 32-hex content key and reported size no larger than the parser's 8 MiB bound if known.
3. Open that exact bound source. Require `COMPLETE`, no protected ranges, length within the parser bound, and a full checked read. Close the opened stream on every terminal path; never close borrowed `BuildFiles`, tables, build handle or object stores.
4. Parse `WorldPlacements.readObj0` with the combined cancellation predicate. Preserve all raw ordinals and reference kinds, including unsupported legacy indices.
5. Re-stat the same ID after reading; require the same FileDataID/content key and check cancellation before publication. Changed source, optional parser failure or protected bytes yield unavailable with no complete derivative/cache receipt.
6. Cache only immutable parsed facts, keyed by bound build/locale, obj0 FileDataID, content key and parser revision. Every invocation performs a fresh stat before reusing facts; source receipt/map/tile is freshly attached to the request, never copied from a different tile's previous result.

The helper retains at most nine cache entries and at most 16,384 cached placement records total, evicting least recently used entries before insertion. A single full result remains at most 8,192 records under the parser's separate per-kind bounds. Oversized input is rejected, never truncated. Source arrays are released after parse and are not cached. This gives element/source-byte bounds, not an exact JVM heap-byte claim. Caller-retained results and future rendering allocations require their own ownership ledger. No persistent derivative format is introduced at this checkpoint.

## Admission, cancellation and close

The source helper owns one daemon request worker with a queue of one. Saturation rejects the extra request immediately; it does not silently replace another caller's result or spawn a worker per file. Files and decoding remain off FX even if a custom provider's future-producing method blocks synchronously.

A separate bounded cancellation lane dispatches linked token cancellation off the calling thread. Cancellation/close must not queue behind the very source operation they need to unblock. No unbounded worker or cancellation task is created per call. Registration lifetime ends with the owned request. Pending requests check caller/lifetime cancellation before opening anything. Cache mutation occurs on the request worker only.

`closeAsync()` is idempotent, rejects new work, schedules lifetime cancellation, drains existing requests, clears only the helper's own cache and resolves after its workers finish. It returns promptly and does not synchronously invoke potentially blocking custom cancellation callbacks on FX. If a borrowed source ignores cancellation, the bounded worker may remain occupied and close pending; the helper cannot safely kill or close the caller-owned source. Retirement never waits on that future on FX. This limitation must remain explicit in tests and review.

**Latest-view ownership belongs above this per-tile service.** A future neighborhood controller admits one active batch of at most nine tiles plus one replaceable pending neighborhood, cancels retired work through its bounded cancellation lane and rejects stale generations/source handles before publication. Replacing a neighborhood does not leave its futures unresolved. Camera-only updates do not enqueue another source batch. This controller is a later checkpoint; this API is not automatically called on area/list selection or camera motion.

## Deduplication ownership

The parser and per-source service preserve every declaration. A later neighborhood assembler deduplicates by bound build/locale/map plus placement kind and unsigned unique ID, comparing all relevant raw placement/reference fields while ignoring only source ordinal/tile provenance. Identical copies retain all contributing receipts. Conflicting copies omit that identity and record a conflict; no last-writer-wins selection. Future transform/material qualification may narrow supported flags, but must not erase raw conflicts before inspection. Asset sharing by model ID is separate from placement deduplication.

## Required authored gates before service approval

- No recorded obj0 ID and default/custom unsupported provider perform no file reads; obj1 is never probed.
- Complete readable empty versus malformed/protected/missing/oversized sources remain distinct; changed receipt cannot produce READ or enter the cache.
- A cache hit checks current stat and preserves each requesting tile's namespace. Changed content key forces a fresh read; parser revision participates in identity.
- Cancel before admission, cancel during read/parse, synchronous provider blocking until token cancellation, saturated request queue, close/drain and post-close rejection are tested. A stalled cancellation callback must not block FX or grow unbounded threads/queues.
- Facts remain immutable/detached; streams close exactly once on success and failure; borrowed source close methods are never called.
- Cache entry and aggregate-record limits evict deterministically without returning truncated facts. Durable provenance is absent until a later separately reviewed derivative contract exists.

No source-service test proves placement matrices, model geometry, buildings, water, textures, animation or collision.

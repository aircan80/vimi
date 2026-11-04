# Private prepared geometry checkpoint

This implements the approved geometry/cache part of the source-factory design.
It does not publish packets, create children, qualify materials or wire GUI code.
WorldService's existing cache is replaced, not supplemented, by WorldGeometryCache.
Existing public LoadedTile values remain descriptive and cannot create RootProof
or Prepared owners. The only Prepared construction path uses a private live
TileAdmission to read its recorded root, parse it, create the immutable native tile
and compute native-terrain-geometry-v1. An existing private proof can reuse geometry
after a fresh admitted-root identity/known-length check; the engine tracks that
binding and rechecks it after the callback with Map stamp/EOF and WDT identity.

Twelve counts ALL current and pinned retired entry versions plus a construction
reservation. Reservation precedes root source open/allocation. Eviction clears
unpinned entry fields; if preserving the sole old unpinned version would prevent
admission, discard that version before reading, without keeping its geometry alias.
Twelve independent output credits pin entries. A source close invalidates access
immediately; close retires each output after its one synchronous payload callback
drains. Output completion and callbacks execute outside cache/owner monitors.
A separate one-active preparation guard prevents concurrent lazy-native copies.

Each entry owns at most the existing raw pair plus one lazy native pair. Maximum
geometry payload is 12*2*1,231,872 = 29,564,928 bytes. Existing area metadata has its
separate conservative 12*64KiB = 786,432 allowance. New hot-proof/output controls are
within the approved additional64KiB control allowance: only twelve entries/outputs,
fixed numeric hot keys and32/64-character hashes. Legacy directory keys and descriptive metadata retain their existing cold-path
allowance; they are not charged to this new hot-control allowance. These exclude
preexisting externally
retained descriptive LoadedTile graphs and do not claim a process/RSS limit.

Construction requires the approved primitive parser (local prerequisite618c235,
original8e57ff1). Its16MiB phase consists of8MiB owned root input, four1,231,872-byte
geometry-payload allowances (including bounded parser growth/trim phases) and2MiB
area/parser allowance. Parser locals die before native conversion; source input
closes before later material work. Previous resident arrays remain charged to the
cache, including an old version pinned for comparison. Equal geometry reuses those
arrays but replaces area metadata and the recorded companion tuple from the new
checked parse/admission; the old pinned output keeps its old area facts.

Authored real-native Map/WDT/root tests demonstrate unchanged-root reuse without
another root read, legacy public DTO requalification, changed-root/same-geometry
fresh area facts with shared arrays, twelve pinned geometry versions surviving
replacement, thirteenth output rejection before IO, release permitting another
root read, malformed construction recovery, and held callback close drain.
`:concepts-world:check :formats-world:check` passes43 tasks; focused scope suite has29
fixtures. Log: `/tmp/vimi-geometry-cache-check.log`. No FPS, live-heap or seamless
hot-stream rendering claim follows from these source/cache checks.

Input receipts now preserve nullable declared length from the before-read stat and
the exact enforced source limit; metadata enrichment does not invent a formerly
unknown value. BASIC_WMO recorded ROOT is separately narrowed to2MiB before open,
OBJ0 remains8MiB, and unused TEX0 is declined. This cache's16MiB native-geometry
construction is restricted to TERRAIN_BASIC; WMO's metadata-only RootFrame parser
has its separate reviewed phase and does not call this factory.

# Source-only global declaration checkpoint

Implements the reviewed first slice in `world-global-declaration-admission-slice.md`.
`WorldGlobalScopes` is package-private and separate from terrain scopes. There is
no WorldService/BuildWorld capability, WMO model/material loading, packet, native
renderer, scene transform or GUI route. Existing hot-world source code is unchanged.

The only new origin sites are the explicitly reviewed `GLOBAL_PROFILE` (valid RAW
facts outside this narrow capability) and `GLOBAL_WDT_BINDING` (a known consumed
identity changed). Malformed RAW data, arbitrary providers, unknown cancellation
subclasses and cleanup faults stay fatal. Only exact clean JDK cancellation,
including the bounded standard async wrappers, preserves cancellation semantics.

`create` binds the actual native Map through WorldMapProofs. After a successful
Map bind it drops the old replacement before constructing another WDT proof;
current survives a failed candidate. The actual numeric WDT body is consumed once
with a one-input/1MiB reservation, complete/no-hole/declared-length/read/EOF checks,
both RAW scans on the same array, and fresh actual Map+WDT validation. The body and
RAW facts retire before final Map work. Unknown before-stat length remains unknown;
later metadata enrichment does not change the consumed identity.

The first strict profile requires exactly five singleton WDT18 chunks, explicit
zero MAIN words and all eight zero MAID reference columns, MPHD flags0x241 with
all seven tail words preserved, one direct positive-root MODF with flags8, zero
position/rotation/sets and rawscale1024. Signed-zero/bounds/other recorded bytes are
bound by full-body/declaration SHA. No asset IDs, Map IDs or build allowlist mint
positivity. A recorded root/reference/environment word is a description only.

Private scope tokens carry only namespace/generation/Map/WDT/key/digest. Registry
holds current+replacement, conservatively charged32KiB combined; neither retains
WDT/MAIN/MAID arrays, BitSets, receipt/schema/table handles or raw Facts. The control
ledger is64KiB, both RAW scan transients160KiB, digest scratch4KiB, plus one1MiB body;
these retire before the separately bounded4MiB Map phase. This is not a full global
mesh/texture/child16MiB admission. Generic callback R has its caller's separate ledger.

`withRecordedDeclaration` accepts only owned scope and map/ordinal/unsigned-UID
comparison. It reconsumes the actual WDT and compares the actual whole identity,
then invokes a same-thread metadata-only Borrow. Expiry clears facts and Thread.
Returned R remains engine-owned through final Map/WDT checks, actual cancellation
callback drain, and a final locked external/lifetime/health/known-scope gate.
Known failure closes R before waiting for callbacks that may require that close.
Late end failure also closes unreturned R inside the one-active guard. Callback,
IO, R close, cancellation dispatch and future completion occur outside metadata
monitors. Cleanup fault is sticky, clears proofs and denies future source work.
Close invalidates first, requests cancellation off caller and returns copied actual
drain; completed generic R belongs to its caller and carries no source authority.

Validation: twelve new authored scenarios pass using actual native Map preparation
and actual WDT bytes. They cover all eight unsigned MAID columns, MAIN reserved
words, shape/malformed refusal boundaries, pending/current registry policy, same-stat
body changes, final-source/R-cleanup failure, expiry/cross-thread access, real held
lower cancellation requiring R.close, close after cancellation sealing, null-stat
metadata enrichment, and known/unknown oversized inputs before open/read/allocation.
Removing only the post-drain callback-resource transfer gate causally fails the
close-after-seal fixture (successful R escaped instead of the expected failure).
The restored full concepts-world check passes147 tests plus style checks.

Private read-only/local-only proof passes Map249 OBSERVED_SELECTED_ROW→WDT789922
(163980B, CKeyf0f56c70a2d122b12e925530e58a406c)→recorded root109474/UID0xffffffff
and the actual seven MPHD words, repeated twice with both returned owners closed.
Its observed stat IDs are WDT789922 and Map1349477; only WDT789922 is opened by this
engine. Original/prior stores remain read-only; native Map preparation and all new
derived data go into fresh private scratch. No WMO/companion body, terrain array,
network request or game asset is committed. This establishes declaration admission
only, not instance rendering, lighting, water, floors, doodads or client parity.

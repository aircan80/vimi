# Primitive terrain parse checkpoint

Based on main78d03a6. This changes only WorldFormats.parseAdt geometry accumulation;
WDT/object parsing, source/cache/factory behavior and renderer APIs are unchanged.

The old path accumulated111,360 boxed Float components and up to196,608 boxed
Integer indices for a full tile, then unboxed them into primitive output. It also
constructed an int[12] for every rendered cell's four-triangle fan. The new private
builders write primitives directly, append the same12 indices without a per-fan
array and transfer exact-sized arrays to the existing defensive Terrain constructor.
No coordinate expression, sample order, fan order, hole/area/grid admission or
cancellation check was changed.

Builders start with shared empty arrays and allocate only when a validated vertex
or surviving fan is appended. Initial capacities435 float components and768 indices
fit one full chunk. Geometric1.5× growth is capped at111,360/196,608 elements and
throws before exceeding the existing tile bounds. Small/malformed source does not
reserve a maximum tile. take() clears the builder's payload reference. Public
Terrain constructors/getters keep their defensive copying behavior.

Before editing production code, five authored cases were parsed with the original
implementation and their native-terrain-geometry-v1 bitstream fingerprints and
complete area-evidence digests recorded. Fixed goldens now cover full256 chunks,
partial17 chunks, high/low holes and fully holed geometry, shuffled source order,
unsigned areas, negative zero and subnormal heights. New output matches every
count/hash. Raw bit, initial fan, mid-source cancellation and excess-chunk fixtures
also pass, alongside existing World/Grid/Area/source texture/binding tests. Changing
only the first fan's corner order causes the fixed geometry golden to fail.

Optional ThreadMXBean diagnostics measure current-thread Java heap bytes allocated
inside adtTile, excluding prebuilt input and getter/hash work. Same Linux JDK25
host,32 warmups and12 observations, volatile retained output; one baseline and one
new run are diagnostic, not a frame-rate/RSS or allocation-peak guarantee:

| Authored case | Old median allocated bytes | New median allocated bytes |
| --- | ---: | ---: |
| Full tile | 13,273,888 | 5,305,336 |
| Single chunk | 39,816 | 10,760 |
| Bad version header | 2,536 | 2,536 |

The observed full-tile Java allocation total fell by about60%. A separate sample
had median parse time11.753ms before and3.037ms after; scheduling/JIT/GC affect these
small traces. Current World cold loads call this parser, but smoother interactive
frames require measurement in the actual app. This does not remove native child
handoffs, improve view distance or implement hot streaming.

For the pending geometry-factory ledger, old boxed owners can no longer be used
to assess this implementation. Conservative overlap of both builders' largest
old+new resize arrays is about2.17MB (2.07MiB), plus existing Terrain output/trim copies and
area/parser owners. Root input and cache payload/AreaMetadata remain separate
named owners. The architect must approve the actual complete construction ledger;
this checkpoint does not itself certify the proposed factory's16MiB bound.

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew \
  :formats-world:check :rendering-world:check :concepts-world:check
```

## Read-only actual-source equivalence

A private proof compiled old main78d03a6 and new8e57ff1 into separate classpaths,
using the actual TerrainGeometryFingerprint utility. It read three already-available
private root ADTs selected by WDT775971; no user library/store was opened/written,
no asset bytes were added to Git and no GUI/GPU was started. A named source worker
parsed each input with16 warmups and9 measured observations.

All three inputs retained identical MD5, chunks/components/index counts, native
geometry fingerprint and complete raw area/entry-reference digest:

| Root FileDataID | Chunks | Components | Indices | Old/new median Java bytes |
| --- | ---: | ---: | ---: | ---: |
| 777947 | 256 | 111,360 | 196,464 | 13,263,712 / 6,115,272 |
| 777952 | 256 | 111,360 | 196,608 | 13,257,472 / 5,297,848 |
| 778147 | 256 | 111,360 | 196,464 | 13,253,248 / 6,074,136 |

Median parse times in the same order were6.407/1.838ms,2.600/2.212ms and
2.540/2.376ms (old/new). Fewer-than-maximum indices require an exact-size trim copy,
so allocation differs from the full unholed authored input. These small local
source-only traces do not establish frame rate, heap peak or interactive pacing.
The private source/helper/compiler/log evidence is retained under
`/tmp/vimi-terrain-primitive-live-proof/`; comparison.txt records each full hash.

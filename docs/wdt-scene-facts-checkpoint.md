# Raw WDT scene declarations

`WdtSceneFacts` is a separate bounded raw-facts parser; existing WorldFormats.wdt
and WorldService behavior are unchanged. It retains MPHD flags, MAIN active count,
raw MODF global records, detached read-only MWMO bytes and descriptors for all
unparsed payloads. It never resolves names, opens referenced files, authenticates
source data, creates an ADT or computes a placement matrix.

The declaration mode is terrain-only/global-WMO-only/mixed/unresolved. MAIN must
be present for any resolved mode. An MPHD global flag needs exactly one recorded
MODF object; missing/ambiguous or contradictory declarations remain unresolved.
A structural mode never claims readable geometry or a supported material profile.
Direct-ID flag8 is retained; other unsigned reference values remain unresolved,
without assuming a legacy name offset/index interpretation. Zero IDs and scales
remain raw facts, not inferred defaults. Floats preserve source axes and bits.

Limits are1MiB input,256 top-level chunks,64KiB MWMO and16 global declarations.
Version18 and one MPHD are required; singleton duplicates, malformed spans,
nonfinite vectors and over-limit counts refuse before their respective allocation.
MPHD extension words and all unknown chunks remain explicitly unparsed. The
bounded name snapshot is detached from input and exposed read-only. These limits
are this new parser's profile, not a change to existing terrain parser limits.

Five authored tests cover unsigned global IDs/raw float bits, mode contradictions,
name input detachment/read-only storage, unknown descriptors, singleton duplicates,
malformed spans/version/NaN, size/count limits and cancellation. Full formats-world
checks pass. Actual source proof, independent review, global transform qualification,
private source admission, instance scene/rendering and UI remain separate gates.

# Selected item spell-text previews

Status: source-backed design/API checkpoint; no implementation claimed here.

Item286556 reproduces the reported raw-token problem on selected Forever70755.
Its recorded ItemXItemEffect170254 → ItemEffect238154 → Spell1320579 route reads
“Desert Winds”. The description contains two percentage placeholders and a duration.
The Items backend currently preserves that raw text deliberately; it never calls
`SpellTexts`, although Talents and racial descriptions already do.

## Preserve facts and add a preview

Add optional `SpellTexts.Description preview` to `EffectsTypes.TooltipLine`, with
its current five-argument constructor retained. `text` and `state` remain the
original source text/outcome; native facts, comparison projections and issue
certificates do not gain interpreted values. Require preview spell/raw to match
its line before attachment.

Add an explicit version argument to an overload of `ItemEffectsService`'s
constructor. Legacy constructors remain raw-only. The application passes its
selected build version. Each version-enabled Handle owns one cold/bounded
`SpellTexts` helper; it drains the helper before closing its Tables handle,
including cleanup failures. A plain description requires no effect-index work.
Optional preview failure retains the raw line and an unavailable preview rather
than failing all item details. Caller cancellation remains cancellation.

The styled Items tooltip consumes `preview.display`, never raw client syntax.
Unsupported simple values become `?`; unsupported expressions yield a clear
unavailable description. A collapsed source section retains raw text and exact
substitution cells/issues. Existing raw-only consumers retain current behavior.

## Narrow new percentage profile

Keep the existing Forever1.60/SpellEffect5362E3D4 profile unchanged. Add only:

- Effect6/APPLY_AURA with EffectAura33/decrease movement speed;
- Effect3/DUMMY with EffectAura0, used as a recorded scalar description parameter.

For these new cases, accept an `$s1`–`$s3` token only when immediately followed by
literal `%`. Require difficulty0, a unique readable effect per index, integral
finite base within the existing limit, raw scaling guard column24 equal0,
Variance/EffectRealPointsPerLevel/EffectPointsPerResource/BonusCoefficientFromAP/
Coefficient/ResourceCoefficient/EffectBonusCoefficient equal0 and
GroupSizeBasePointsCoefficient/PvpMultiplier equal1. No damage/healing, area
eligibility, script execution, character scaling or server outcome is inferred.
Duplicate readable rows and partial scopes retain existing qualification rules.

For the reported example, rows1355298/index0 and1355304/index1 contain −50/−80.
The existing formatter already takes absolute integral amounts; the primary
WoWTools parser does the same. The literal percent suffix supplies units. This
supports description previews of50%/80%, not a claim that the character is
currently in a desert or that the server's script has been evaluated.

## Duration definition and `$d`

The selected SpellMisc434B3607 row866289 declares DurationIndex1. SpellDuration
FileDataID1137828, table hashb423fa7a, content key6a7a375278c64db6403456a967c26870
has physical layout4FDF0951. The unchanged pinned bundleb9a630… does not include
that layout, so current local-only table preparation reports NO_DEFINITION.

Upstream WoWDBDefs commit93405a655ab55efdcc04ade02bc78f266b565650 explicitly
lists4FDF0951 for1.60.1.70707/70753/70755. Its unmodified25904-byte SpellDuration.dbd
has SHA-2567bbfe3a9056372b7aef7e270e18eddaaefcc6da4f87cb81651114430508c944b.
The exact block has noninline ID and signed32-bit Duration, DurationPerLevel,
MaxDuration, DurationPerResource. Do not infer these names from four integers.

Proposed additive `TableDefinitions.withSupplementalDefinitions()` enables this
one separately pinned local resource. The existing constructor remains unchanged.
Selection requires matching manifest name/hash/FileDataID and one of the three
listed builds. A known layout must be exactly4FDF0951; initial layout-null
selection permits only the exact build's block, and the existing DB2 reader must
then verify the actual file layout before producing rows. Source.SUPPLEMENTAL
retains the real new revision, URL and hash; it is not relabelled as the old
bundle or a network fetch. All other tables/builds/layouts retain the existing
selection order and outcomes. No network access or implicit consent change.

The application enables the supplement only when definitions are enabled. Its
revision/hash enters the application definition/preparation identity, invalidating
the old known-table NO_DEFINITION ledger once. Generated native identities still
use their exact resolved-block hash; old table blocks and field-admission/issue
policies remain unchanged. The existing pinned compressed bundle and source
hashes are untouched. New data keeps upstream CC-BY-SA attribution separately.

`$d` resolution uses a unique readable difficulty0 SpellMisc with declared,
verified DurationIndex and exact reviewed layout. It reads that ID from qualified
SpellDuration4FDF0951; DurationPerLevel and DurationPerResource must be0,
Duration must be positive integral milliseconds with exact whole-second conversion,
and MaxDuration must equal Duration. Infinite/contextual/ambiguous/protected or
unavailable duration remains unresolved. Output uses bounded `N sec` text;
other token forms and duration-unit expressions remain unsupported initially.

## Evidence and acceptance

Private read-only/local-only source proof is under
`/tmp/vimi-item-tooltip-286556.log`; original library objects are read-only and
only generated scratch data is written. No game source payloads enter Git.

Primary references:

- [WoWTools parser, d1a5616c](https://github.com/Marlamin/WoWTools.SpellDescParser/blob/d1a5616c83a86a3f3ff549704e19ef752c66f604/WoWTools.SpellDescParser/Nodes/Property.cs)
  formats negative effect points as absolute integers and duration in seconds.
- [Its supplier](https://github.com/Marlamin/WoWTools.SpellDescParser/blob/d1a5616c83a86a3f3ff549704e19ef752c66f604/SpellDescTest/SpellDataSupplier.cs)
  follows SpellMisc.DurationIndex into SpellDuration.Duration.
- [Pinned duration definition](https://github.com/wowdev/WoWDBDefs/blob/93405a655ab55efdcc04ade02bc78f266b565650/definitions/SpellDuration.dbd).
- [Aura constants](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/Spells/Auras/SpellAuraDefines.h)
  identify aura33; this alone does not authorize general gameplay calculations.

Require authored tests for existing-profile stability, all context guards,
percentage-only dummy admission, signed values, duplicate/partial effects,
source/raw mismatch, exact duration schema/ID/units/scaling refusal, supplement
build/file/layout/hash/provenance gates, old preparation-ledger refresh,
close/cancellation and styled-tooltip display/source separation. Reproduce the
reported item through the public service plus a UI fixture; existing C# golden
facts and native issue certificates must remain unchanged.

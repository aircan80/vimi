# Races browser

The Races area appears after Talents in an open build. A sidebar lists race records
with recorded class combinations; **Include other race records** exposes internal
or unlinked `ChrRaces` records. If no class-linked rows are readable, the browser
shows the available race records instead of guessing which are playable.

The overview presents names, lore, a verified faction-name relationship when
available, recorded class names and racial ability cards with icons. Initials are
used for the race badge: character-creation portrait references are texture-atlas
elements and are not treated as image FileDataIDs. Missing icons remain placeholders.
Source IDs and partial-data diagnostics are in a collapsed Source details section.

Static client combinations do not establish current realm/account unlocks. Racial
cards are not matched to spells by name. Race mask relationships, if added later,
must use the reviewed `PlayableRaceBit` rather than `raceID − 1`.

## Component and lifetime

`:concepts-races` owns typed asynchronous projections over the workspace's borrowed
build/locale table handle. The GUI does not query SQLite or enumerate tables itself.
The domain handle is created cold, loaded when Races is opened, and drained before
borrowed tables close. The view rejects stale selections, workspace changes and
late image results. Readable PARTIAL rows remain usable.

All names and relationships come from the selected pinned WoWDBDefs schema. The
reviewed racial-card layouts are `B6115535` and `0FC0F0D5`; unsupported optional
shapes do not discard race/class facts. Older layouts without lore or racial-card
tables keep the available metadata.

## Static model preview

Races keeps the recorded race menu on the left and compact racial cards on the right.
The center shows a rotatable, zoomable static surface for a recorded model variant.
The exact build-local chain is `ChrRaceXChrModel → ChrModel → CreatureDisplayInfo →
CreatureModelData → FileDataID`. No models are chosen by name or guessed path.

The separate `M2RestSkinReader` validates SKIN topology against native M2 positions
without claiming that external bones, animations or draw materials were evaluated.
Existing strict static-geometry and appearance APIs retain their external-rig refusals.
A versioned, content-bound compact derivative is persisted through `GeneratedStore`;
restores verify both M2 and SKIN content keys against the current build root.

`:rendering-models` renders bounded positions/triangles on the CPU. This also works
on Linux ARM systems where the installed JavaFX pipeline reports no SCENE3D support.
Frame size, vertices, indices, surface count and raster work are capped. Cancellation
and latest-camera replacement keep stale frames out of the UI.

Sample customization uses the first recorded choice whose reviewed player/class/race
requirements are known to pass, plus related-choice gates and recorded geoset joins.
NPC-only options are excluded. Unknown account, region, archive, visibility or
prerequisite requirements are omitted rather than guessed. Race masks contain two
32-bit words and use `PlayableRaceBit`; class masks use the recorded class bit.
This follows the reviewed
[WoW Model Viewer character requirements/geoset convention](https://github.com/wowmodelviewer/wowmodelviewer/blob/develop/Source/games/wow/CharDetails.cpp).
The reviewed current layouts include option `DCC2A86E`, choice `9559C358`, element
`6483C37E`, geoset `BF55F2FB`, requirement `CA154412` and prerequisite `F925BC6F`.
The sample is not a claim about an account's eligibility or the client's default
choice. All recorded surfaces can be inspected explicitly, including mutually
exclusive alternatives.

## Human diffuse textures and starting clothing

The first reviewed character-composition profile supports the current Human male
and female models. Other races/layouts retain the available static surface and
report that their texture profile is unsupported. The GUI shows geometry first,
then loads the textured sample asynchronously; rotation/zoom remain usable.

The selected choices join `ChrCustomizationElement → ChrCustomizationMaterial →
TextureFileData` diffuse resources. `ChrModelMaterial`, `ChrModelTextureLayer` and
`CharComponentTextureSections` provide actual canvas sizes, ordering and atlas
rectangles. The profile supports recorded copy and straight-alpha source-over
operations; unreviewed layer operations are omitted with diagnostics. No fixed
legacy atlas dimensions or guessed texture filenames are used.

Class selection resolves the unique `CharacterLoadout` of purpose **9** for the
recorded class and playable-race bit, then `CharacterLoadoutItem`. TrinityCore
explicitly identifies this purpose as new-character equipment; purpose1 preview
loadouts are not substituted. [Structure](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/DataStores/DB2Structure.h),
[loader](https://github.com/TrinityCore/TrinityCore/blob/master/src/server/game/Globals/ObjectMgr.cpp).
Default modifier0 appearances join `ItemModifiedAppearance → ItemAppearance →
ItemDisplayInfo → ItemDisplayInfoMaterialRes`. Diffuse candidates match recorded
`ComponentTextureFileData` sex/class/race qualifiers; the most specific matching sex/class/race candidate is used only when its best rank
is unique. Ties preserve the remaining layers and a source diagnostic. Simple body geoset
overrides follow the recorded clothing groups: robes select their closed skirt and
sleeves over pants defaults, suppress boot shafts and retain the recorded boot feet.
Separate collection meshes and unreviewed group overrides remain explicit limitations.

`M2RestDrawSkinReader` validates raw draw references independently of skeletal
admission. The renderer uses actual UVs/native normals and a narrow one-stage diffuse
profile, including constant-opacity proof and no UV transform. Unsupported surfaces
keep neutral shading. Existing strict rig/material APIs retain their refusals.
The preview remains a static rest pose: animation, full lighting/material shaders,
weapon/shield attachments and runtime appearance overrides are not evaluated.
It is not a full character editor.

The composed geometry, UVs, normals and atlases persist as a bounded derivative,
bound to build, locale, source content keys, the exact recipe and reader/renderer
policy revisions. Restores verify the SKIN root content key. Incomplete texture
fetches are not frozen into the composition cache. The table projection worker
prepares immutable metadata; a separate bounded worker owns file/decode/composition
work, so voice/race queries do not wait on texture CDN acquisition. Closing cancels
and drains both workers before borrowed workspace handles close.

Separate actual-data probes verified Human Warrior male/female clothing38/39/40
and differing Mage clothing through real same-build joins while retaining PARTIAL
source tables. Warm derivative restoration was approximately74–75ms in the probe;
this is not a full application startup benchmark. No live library writes occurred.

Read-only current Forever 1.60.1.70707 proof follows Human models 1/2 through displays
57899/56658 and model records 7661/7599 to M2 files 1011653/1000764 and SKIN files
1012983/1000778. Both produce complete-body sample surfaces from recorded geosets.
No live library changes or installation are needed for this validation. Client model
assets are not checked into the repository; the screenshot below uses an authored
geometric fixture.

## Recorded voice previews

The voice selector uses the recorded model sex and class plus the workspace locale.
`EmotesText.Name` explicitly identifies `JOKE`, `FLIRT`, `CHARGE` and `HEALME`;
`EmotesTextSound` selects the exact race/sex and generic-or-selected-class rows.
The reviewed modern `3ECA1BE0` adapter joins `SoundID` to `SoundKitEntry.SoundKitID`
(the definition retains the historical `SoundEntries` label). This adapter is not
silently applied to older layouts. Entry reads are filtered by kit and bounded;
readable PARTIAL rows are accepted. Runtime player conditions, mixing and randomized
selection are not simulated: each recorded clip can be inspected independently.

Play is explicit; changing selection or closing the workspace stops playback.
Original voice assets use the normal build-local file/CDN pipeline. Derived signed
16-bit big-endian PCM is cached by build, locale, source content key and decoder
revision. Bounds are 2 MiB encoded, mono/stereo, 8–48 kHz and 30 seconds per preview.

`:formats-audio` uses the standalone pure-Java J-Ogg Vorbis decoder. Since its
internal codebook allocations are not heap bounded by the encoded size, decoding
runs in a short-lived Java worker with a 64 MiB heap, bounded output and an eight
second deadline; cancellation terminates that worker. The worker uses the installed
Java runtime, without FFmpeg or a separately installed codec. Repeated previews
restore PCM without decoding again. JavaSound releases the output line on stop;
a machine without supported audio output reports that state in the view.

This software is based on or using the J-Ogg library available from http://www.j-ogg.de and copyrighted by Tor-Einar Jarnbjo.

License and dependency notices ship under `formats-audio/src/main/resources/META-INF`.
Tests use an authored tone, not game audio. Separate read-only validation against
current Forever source rows found 16 Human male and 14 female clips across these
four actions. Actual Joke/Charge assets decoded at 22,050 Hz mono; no live library
was modified and no sound automatically played during the probe.

## Validation

```sh
./gradlew :formats-audio:check :rendering-models:check :formats-assets:check :concepts-races:check :gui:check
# Requires a graphical display; authored data only:
./gradlew :gui:racesUiTest
python3 -m unittest discover -s scripts/ci -p '*_test.py'
```

Focused tests cover recorded/internal race distinctions, real foreign-key joins,
readable partial data, missing race/class names, optional-table failure, unsupported
racial-card layouts, cancellation, borrowed lifetime, displayed icons, filtering
and stale selection. The component is included in Linux/macOS/Windows CI tasks.

A separate read-only probe exercised the actual component against existing
Forever 1.60.1.70707 row graphs and matching pinned schemas: 58 race records,
10 with class links, 56 unique race/class combinations and 4 racial cards per linked
race. All 10 overviews resolved with faction names. No live library writes occurred.
These are records in that build, not proof of server character-creation availability.

![Authored Races layout](images/races-authored.png)

## Texture format observation

Current Forever starting-clothing textures include BLP2 indexed-color encoding1,
alpha depth8 and alpha encoding8. This specific additional tuple uses palette
indices plus an appended eight-bit alpha plane; the alpha encoding selector does
not select a block codec for indexed color. The observed tuple is supported with
existing palette/span/allocation checks. Other unreviewed profile tuples remain
explicitly unsupported. Existing admitted image outputs and version2 persisted
payloads/identities remain byte compatible; unsupported outcomes were never cached.
The primary [js-blp palette reader](https://github.com/Kruithne/js-blp/blob/master/js-blp.js)
reads alpha from the plane by alpha depth independently of the block-codec selector.

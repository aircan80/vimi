# Parallel followup integration checkpoint

This branch is separate from the urgent Maps/build correction PR159. Its starting
main is dc7c5a4; alpha.21 publication was blocked by the SDK native-path check.
Latest published prerelease remains alpha.20. This integration branch is not a release.
No visible instance or water feature is claimed here.

Reviewed and integrated:

- G2 private instance payload e7d5f32, from a7a9ddc: shared two-owner source credits,
  actual qualified geometry/images and guarded scalar reads. Root source/assets
  checks pass62 tasks; independent Java review and private actual proof are recorded.
- Raw liquid parser73608b8, from1ec4e89: bounded MH2O/layout0 facts only. Root
  formats checks pass7 tasks; independent Maps review and actual raw counts pass.
- Visible Maps refresh05f8b85: revision changes reload the open Maps view through
  its existing guarded ensureLoaded. Independent Maps review; actual UI checks95.
- World catalog placeholder81888e2: failed/null completion replaces Loading with
  unavailable/retry text. Actual regression fails before and passes after (95tasks);
  independently reviewed by architect.
- Canonical native receipt stamp329f304, froma808132: private immutable bounded
  bytes, exact equality, unchanged streaming SHA serializer. Root reviewed and
  mechanically compared the serializer body; full formats-table checks46 tasks.
- Shared character choicesed48eb8, fromcc98c30: internal context-bound vector feeds
  geometry and atlas, preserving defaults and entering cache identity. Root reviewed
  production and full races checks pass48 tasks. Visible alternative proof is next.

Additional reviewed integration, 2026-11-04:

- Camera geographic correction78ec862: Explorer lateral projection and turning now
  agree with native north/west coordinates and minimap geography. Actual Vulkan,
  persistent stream owner and FX input checks pass107 tasks. Portrait cameras are
  unaffected. See world-camera-geographic-correction.md.
- Dwarf source-derived appearance controls and Night Elf production qualification
  are integrated through4aaf6f9. Dwarf combined checks pass143 tasks; Night Elf plus
  native API checks pass73 tasks. Actual male/female native renders were inspected.
  Undead remains blocked on qualified archive/appearance eligibility evidence.
- Canonical standalone global WMO file I/O4194667 and conservative JDK staging
  ledger ef82c70: root full bgfx checks pass39 tasks. Independent review read all
  production code and forced all8 I/O tests to execute successfully. This does not
  enable a public instance viewer.
- Public service-owned global Source facade55f6037/6753c2d and fatal-health fix
  15a54e7 preserve source pins, actual writer cancellation and sticky cleanup errors.
  Root full concepts-world checks pass36 tasks. The fixed writer bridge is next.
- Single-root liquid Source74ba41a plus rejected-work drain fix8901034 and native
  coordinate mapping6adeaec are integrated after independent owner/drain review.
  Flat geometry implementation93405ac remains under independent review before
  promotion. There is no water draw or GUI integration yet.
- Security/privacy inventory and review are merged into main7432249 (PR160),
  including persistent decoded audio. See data-flow-inventory.md and security-review.md.

Earlier combined root `check assemble` passed474 tasks (185 executed) BEFORE the
additional integrations above; it is not a full check of the latest head. Focused
checks are recorded above and in the component checkpoints. Private acquired assets
and proof images remain outside Git. Component memory envelopes are not whole-process
memory or FPS claims.

Release gate: PR159 contains the Maps availability and SDK native-library path fixes.
Its latest Linux and Windows source checks pass; hosted macOS jobs failed to acquire
runners, including one infrastructure retry. Alpha.21 remains unpublished; do not
retag it. A future combined candidate still needs final integration checks and all
five platform package gates before publication.

Root combined verification at6e57190: `check assemble` PASSED474 tasks
(102 executed,8 from cache,364 up-to-date), log
`/home/aircan/.cache/vimi-tmp/world-next-current-full-check.log`. This includes the
reviewed PR159 code changes fb80a21/a3fbb5f/6e57190 and liquid Source/mapping,
but excludes the still-reviewed flat geometry implementation and unfinished native
instance renderer/bridge. Separate root liquid owner/mapping checks passed42 tasks.
Documentation-only updates efa9afc/e6527ba follow that tested source head.

Followup review and integrated checks:

- Flat liquid geometry93405ac is independently approved and promoted asac56f66.
  Root integrated concepts/formats checks pass42 tasks (7 executed). Same private
  two-owner Source limits, no scene/native integration. Independent forced tests
  pass32 tasks; actual author proof matches5,268 vertices and7,902 indices.
- Standalone candidate renderer2cb45f2 and six test-only shaders1796a4c are
  independently approved and promoted as6b6a749/9366208. Root integrated bgfx check
  plus actual Vulkan native proof passes41 tasks (16 executed),19 readbacks:
  `/home/aircan/.cache/vimi-tmp/global-native-root-integrated-check.log`.
  Independent actual Vulkan proof also passes19 readbacks. Root inspected the
  authored asymmetric landmark image. This remains a private draw component;
  DX11 artifacts, fixed frame/process owner and GUI enablement are still pending.

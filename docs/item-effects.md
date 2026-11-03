# Item effect text

Selected-item inspection lazily requests the Layer 6 `BuildItemEffects.tooltip` projection.
The application composes its result with base item inspection; optional effect lookup failures
leave the base item visible. The existing browser selection token and workspace retirement
checks also cover this combined request.

The effect service follows the existing build/locale-scoped Item → ItemXItemEffect → ItemEffect
(or legacy ItemEffect.ParentItemID) relationship, then reads the linked Spell row's verified
`Description_lang` column. Trigger values 0, 1 and 2 are presented as Use, Equip and Chance on hit;
other values retain their numeric trigger. No spell-expression calculation runs in the GUI.

Readable rows in PARTIAL tables are admitted. Missing, protected, unsupported and invalid text
remain explicit outcomes; no whole PARTIAL table is rejected. Plain localized descriptions are
shown unchanged. Descriptions containing client expressions (`$`) or markup (`|`) are shown as
source text with an unresolved-expression notice, without inventing contextual values.

For example, Cozy Sleeping Bag (211527) in Forever 1.60.1.70707 links to Unroll Bag (429961).
Its plain description explains resting for an experience bonus that stacks three times.
A numeric maximum percentage belongs to separate buff mechanics and is not synthesized here.

The query uses the effects component's existing bounded first-page limit and reports truncation.
It does not scan every item's descriptions during build preparation.

Validation:

```sh
./gradlew :concepts-effects:test :concepts-effects:checkstyleMain :concepts-effects:checkstyleTest
./gradlew :gui:researchBrowserDataTest
# Requires a graphical display:
./gradlew :gui:researchBrowserUiTest
```

Focused tests cover available descriptions in PARTIAL tables, protected spell rows, plain text,
unresolved expressions, malformed/oversized text, and rendered selected-item Use text. The
existing UI harness also checks stale selection and closed-workspace reply retirement.

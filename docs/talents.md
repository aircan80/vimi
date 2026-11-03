# Talents browser

Open a prepared Classic-style build and select **Talents** after **Creatures**.
Choose a class on the left; the main area displays its ordered trees. Partial
client tables show all readable classes, trees and talents with an incomplete
data warning; protected records do not hide the readable records. A missing
prerequisite keeps its source ID in details and omits the unresolved connector.
Select a talent to inspect rank spell IDs, prerequisites and any readable spell
text in a fixed right-hand WoW-style tooltip. Its rank label previews the first
recorded rank's spell text; it does not represent learned character points.
Unreadable descriptions and prerequisite names stay explicitly unavailable.
Technical source details remain collapsed outside the tooltip. Arrow keys move
focus to the nearest node along a row or column; Enter selects it. The tree strip
scrolls horizontally when the detail rail leaves less room.

Class names, trees, positions and ranks come from the selected build's verified
client tables. Rank badges show recorded maximum ranks. The browser does not
allocate points, load character builds or calculate level/point gates. It does
not replace unavailable data with another game's or build's talents.

Icons load on demand through the build's existing file/image services. Missing
icons use neutral placeholders; unresolved background art uses a plain dark tree
surface. Unsupported models (including Retail and Mists) explain that their
layout is not implemented, instead of showing fabricated Classic trees.

Component contract and checks: [concepts-talents](../concepts-talents/README.md).

## Layout preview

This screenshot uses authored fixture nodes and placeholder icons to review the
layout. It does not represent a real build's talent data or a character's points.

![Authored three-tree talent layout](images/talents-tooltip-authored.png)

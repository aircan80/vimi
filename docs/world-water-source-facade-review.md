# Water Source facade — independent implementation review

Reviewed commit abdbe527 (closed single-root water Source handoff), read fresh by a reviewer
given only the code and the frozen contract in `world-water-source-file-contract.md`.

## Outcome

No blocker. Join to the genuine terrain owner, cleanup on every failure path, absence of
double close, lock discipline (no monitor held across writer/R.close/callbacks), and service
shutdown drain were each verified in code.

## Fixed in this follow-up

- `WrittenWater.withPayload` / `withWrittenResource` leaked the package-private
  `WorldTerrainLiquids.Pressure` for nested or concurrent borrows. Clean pressure now maps to a
  typed `RETRY_LATER` refusal (`WATER_OUTPUT_CREDIT`).
- `WorldStreamScopes.closeAsync` dropped the cancellation-request failure when liquid cleanup
  also failed; it is now suppressed behind the cleanup failure.
- The stale-ROOT test now asserts site `WATER_ROOT_BINDING`, proving the water/terrain ROOT
  join (not an earlier binding check) refuses the stale terrain.
- Javadoc: `Output` documents that it runs on the single Source lane with terrain pinned;
  `withWrittenResource` documents that it is a pinned borrow, not an ownership transfer;
  `BuildWorld.waterPreview()`/`globalScenes()` comments corrected; axis convention
  (north = terrain x, west = terrain y, up = z) documented.

## Accepted as-is (recorded limits)

- Only two water owners exist service-wide and R cannot outlive its `WrittenWater`. The visible
  integration therefore copies the scalar payload inside the writer, returns an inert R and
  closes the preparation immediately; no water Source owner is retained per tile.
- Water preparation holds the terrain tile's single payload pin and the Source lane for its
  duration. The application calls it only on the tile's own producer, before the terrain packet
  MOVE, so nothing else contends for that pin.
- One failure path closes R just after admission ends (publish gate); R in the application is
  inert, so this is not observable there.
- `liquidQuarantine` is write-only diagnostic retention; the ROOT join compares id/CKey/consumed
  length, not declared size/source limit (identical CKey makes this practically safe).

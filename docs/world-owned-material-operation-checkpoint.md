# Owned material result and worker drain

Package-internal `WorldTerrainTextureSource.readOwned` returns a private-constructed
Operation with result(), isDrained() and awaitDrained(). Result completion does not
mean the source worker/lower cancellation callbacks have retired. The factory must
hold its admission through awaitDrained in finally, including canceled/failed result
delivery. It must close every acquired BatchResult/unreturned output normally.
There is no second source helper, executor, atlas pool or cache, no pixel copy and
no capacity increase. Existing public read behavior remains compatible.

Request drain completes outside task monitors only after cancellation registration
removal, lower cancellation callback drain and active retirement. Pending supersede,
already-canceled and closed-source rejection also settle drain after retirement.
Rejected submit no longer invokes retire/completion while holding the task monitor.
Operation retains only result/drain futures, not a Request/service closure; the
private drain future cannot be canceled through the returned API. Cancellation
callback failures settle its drain exceptionally after actual retirement.

Owned mode propagates unknown RuntimeExceptions and CHANGED instead of converting
them into terminal fallback metadata. Known internal SourceFailure outcomes and
actual texture-parser/BLP-format rejection remain classified source outcomes.
Texture-parser rejection is wrapped at the actual parser call, never inferred from
an arbitrary provider IllegalArgumentException. Final receipt revalidation failures
propagate in owned mode; program errors cannot mint HEIGHT_FALLBACK. An actual
terminal BatchResult still requires the separately qualified geometry/source factory
before it can become a height packet. POOL_BUSY is transient, never a fallback.

Authored tests hold a real lower cancellation callback after result failure: the
operation remains undrained, outer scope admission remains busy and a next root
read cannot start. Tests cover pending supersede, already canceled, closed source,
unknown provider/final-validation bugs, actual texture/BLP profile rejection, known
source CHANGED, callback failure and existing legacy behavior. Full concepts-world
checks pass37 tasks (`/tmp/vimi-owned-material-check.log`). No GUI/native promotion.

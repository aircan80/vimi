# Selected-table native preparation backend checkpoint

This backend-only API is explicit preparation, not a new capture or feature-facade behavior. `BuildTables.prepareNativeEvidence(ref, priority, CONFIGURED_SOURCE, progress, token)` defaults to UNSUPPORTED. CONFIGURED_SOURCE deliberately permits the owning Files/BuildFiles policy, including acquisition. It never claims local-only access. Callers requiring no network must construct an existing local-only source owner; there is no hidden network-policy inference.

The native implementation bypasses legacy fallback at both pre-decode and post-layout lookups. It always requests stored native output, never an interactive direct table or unfinished background build. Existing check/schema/page/prepareKnown keep their compatibility behavior. Native generated singleflight remains keyed by the native identity; legacy hits cannot fulfill this operation. A valid native hit needs no DB2 rebuild.

READY contains a detached graph/CK/layout/native-certificate/completion observation. After preparation the operation checks the current file CK, locally resolved block/options/source identity, and opens the exact current native graph under current key fingerprints. The reopened source and full VERIFIED_NATIVE certificate evidence must equal the prepared graph, then the final source stat must still be FOUND with the exact FDID/CK immediately before returning READY. Source changes return SOURCE_CHANGED. Missing/protected/unsupported source outcomes retain the existing table entry as UNAVAILABLE; scheduler/producer failures and cancellation keep their existing exceptional behavior. PREPARING and VALIDATING are truthful phase notifications, not a fabricated percentage. Callback failures propagate through operation cleanup.

No legacy graph is deleted, recertified or rekeyed. Existing retained captures remain readable with UNKNOWN evidence. Strict consumers must still reacquire `capturePreparedLocal`, enforce their own physical profiles, strict row limits and final source stamp checks. This observation does not grant unrelated source-factory authority or promise future immutability of a mutable provider.

Preparation uses existing configured DB2 source/decoded-row/auxiliary/column limits, scheduler, generated writer and metadata owners. It can decode a whole table. Its preparation allocation envelope is separate from the 4MiB strict-row transient profile, the World texture helper ledger, prepared-manifest/schema metadata, JVM and lower file caches. No total48MiB preparation claim is made. No GUI, facade, Map/World auto-upgrade, inventory-cache rewrite or whole-workspace preparation is wired.

The authored same-store test seeds a real legacy generated graph, retains an UNKNOWN capture, proves ordinary check still selects it, decodes the original authored DB2 through the production reader into the SAME generated store, then verifies fresh VERIFIED_NATIVE capture, identical numeric values, retained UNKNOWN evidence, byte-identical old graph artifacts and stable repeated native preparation. No live credentials, network, user stores or game payloads are used.

```sh
./gradlew :formats-tables:check
```

Independent implementation review through e181fa2 is approved; consumer/GUI orchestration remains separate. Authored hardening fixtures additionally reject a CK changed during final open and same-identity reopened graphs with wrong source or missing certificate; cover callback failure/cancellation with subsequent recovery, held-validation close/drain, and an ordinary compatibility check/capture during the active legacy-to-native operation. These backend fixtures do not approve GUI orchestration or application source-access policy.

## Final metadata and actual overlay evidence

Reopening now also compares rowCount, completion, header, sections, keysUsed and holeKeys. A fake same-identity/certificate graph with changed completion is rejected. Removing only completion equality causally returns READY instead of SOURCE_CHANGED; the restored focused test passes. Full formats-tables checks pass after the metadata change.

A private selected wowf1.60.1.70755 enUS proof used original library ObjectStore.openReadOnly plus CachingObjectSource.localOnly, and a generated-store overlay reading original artifacts while writing exclusively to a fresh private scratch store. No network client or writable original-library handle existed. It retained each original UNKNOWN capture across explicit native preparation, checked its old row result and numeric values, compared every original saved artifact SHA-256 before/after and opened a fresh VERIFIED_NATIVE capture in the SAME overlay:

| Table | Original legacy graph | New native graph | Rows | Original artifacts unchanged |
| --- | --- | --- | ---: | ---: |
| UiMap | db65904c68944458c5c022b448cb72e6 | 70e9a9970319d3958e417b8f2e093770 |60|4|
| UiMapAssignment | 3f2c881612fda45d9bfbc8cab594d429 | f47c0e27541456d5cdc885b9002dc3af |61|4|

The original Map graph was already VERIFIED_NATIVE and was explicitly recorded as not needing an upgrade; no claim that it was UNKNOWN is made. Private proof source/logs remain under `/tmp/vimi-native-evidence-proof/`, with the successful frozen-classpath run in proof-final.log. Preparation observations were106/61ms respectively in this run; these are local diagnostics, not application latency/FPS promises. No game payload or artwork is committed. Independent test checkpoint8e9458c adds consumed-key fingerprint mutation and definition-provider mutation/recovery fixtures, with all35 CapturedRows tests passing. The definition mutation uses reflection only as a negative provider-change seam after genuine native preparation; it cannot mint positive evidence. No implicit consumer/GUI orchestration is approved here.

# Selected-table native preparation backend checkpoint

This backend-only API is explicit preparation, not a new capture or feature-facade behavior. `BuildTables.prepareNativeEvidence(ref, priority, CONFIGURED_SOURCE, progress, token)` defaults to UNSUPPORTED. CONFIGURED_SOURCE deliberately permits the owning Files/BuildFiles policy, including acquisition. It never claims local-only access. Callers requiring no network must construct an existing local-only source owner; there is no hidden network-policy inference.

The native implementation bypasses legacy fallback at both pre-decode and post-layout lookups. It always requests stored native output, never an interactive direct table or unfinished background build. Existing check/schema/page/prepareKnown keep their compatibility behavior. Native generated singleflight remains keyed by the native identity; legacy hits cannot fulfill this operation. A valid native hit needs no DB2 rebuild.

READY contains a detached graph/CK/layout/native-certificate/completion observation. After preparation the operation checks the current file CK, locally resolved block/options/source identity, and opens the exact current native graph under current key fingerprints. Source changes return SOURCE_CHANGED. Missing/protected/unsupported source outcomes retain the existing table entry as UNAVAILABLE; scheduler/producer failures and cancellation keep their existing exceptional behavior. PREPARING and VALIDATING are truthful phase notifications, not a fabricated percentage. Callback failures propagate through operation cleanup.

No legacy graph is deleted, recertified or rekeyed. Existing retained captures remain readable with UNKNOWN evidence. Strict consumers must still reacquire `capturePreparedLocal`, enforce their own physical profiles, strict row limits and final source stamp checks. This observation does not grant unrelated source-factory authority or promise future immutability of a mutable provider.

Preparation uses existing configured DB2 source/decoded-row/auxiliary/column limits, scheduler, generated writer and metadata owners. It can decode a whole table. Its preparation allocation envelope is separate from the 4MiB strict-row transient profile, the World texture helper ledger, prepared-manifest/schema metadata, JVM and lower file caches. No total48MiB preparation claim is made. No GUI, facade, Map/World auto-upgrade, inventory-cache rewrite or whole-workspace preparation is wired.

The authored same-store test seeds a real legacy generated graph, retains an UNKNOWN capture, proves ordinary check still selects it, decodes the original authored DB2 through the production reader into the SAME generated store, then verifies fresh VERIFIED_NATIVE capture, identical numeric values, retained UNKNOWN evidence, byte-identical old graph artifacts and stable repeated native preparation. No live credentials, network, user stores or game payloads are used.

```sh
./gradlew :formats-tables:check
```

This first checkpoint requires independent review before consumer promotion. Further race/cancellation/source-change/close tests are appropriate before GUI orchestration; no GUI delivery approval is inferred from coexistence.

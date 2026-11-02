# Community file names

`CommunityFileNames` implements the Files names boundary over `ObjectSource` and a
separate `GeneratedStore`. Inject a `FileNameSwitch`; without explicit enabled
consent and a generated store, names are Off. Status and lookups never fetch.
Paths are community labels, not evidence that a build contains a file.

A refresh records the release observation, stages missing 8 MiB parts in generated
storage, verifies the exact sizes, complete SHA-256 and strict CSV, then records a
full copy or a bounded VMLD delta through layer 2 before publishing the names index.
Downloads resume across engine restarts. A new tag with identical bytes records a
zero-op delta without downloading the CSV again. Generated storage can be wiped:
release observations and full-copy/delta history rebuild the index offline.

CSV parsing accepts canonical ascending unsigned32 FileDataIDs, strict UTF-8,
LF/CRLF and a final line without LF. It rejects control characters, removes bidi
formatting marks, and caps names at 1,024 bytes, 8 million entries and 512 MiB of
name bytes. Parsing retains one line. Index construction holds a 64 KiB feed slice,
record-writer blocks/segments and eight bytes per name for sorted path hashes.
Two portable generated record files hold names and folded-path hash candidates;
every hash hit is confirmed against the original name. The paths manifest commits
last. Reads load only the relevant compressed ranges, and failed generated reads
return no names and mark the snapshot for repair.

VMLD1 encoding and replay are streaming, retaining at most a 64 KiB CSV unit per
chain link plus the delta documents. Chains have at most 16 links. Delta documents
are limited to 16 MiB by the observation boundary; parsed Java operation objects
add memory overhead to those bytes. Published names always pass the target hash
and size checks. The index metadata and delta wire format exchange in both
directions with the pinned C# implementation.

Checks are due weekly; failed attempts back off 15 minutes. Unreadable history
loads retry after one minute, and a switch change or explicit update retries sooner.
A usable old copy remains available after a failed refresh. History traversal is
bounded to 1,000 pages, 4,096 retained release entries, 16 distinct release tags,
eight delta candidates per tag and 128 resolution attempts.

Async names work has 64 admission slots and runs on virtual threads; overflow is a
failed future with `RejectedExecutionException`. Explicit cancellation reaches
object access and generated operations. Canceling a returned future only stops
that observer. Generated segments can be shared: repair deletes broken segments,
never healthy segments shared by another index. Unlike the reference's opportunistic
old-index cleanup, healthy obsolete index manifests/segments are retained until a
reachability-aware collector exists. Successful updates delete staging and attempt
up to four compaction rounds, four sealed packs each. Native Windows certification
and real-data throughput testing remain deferred.

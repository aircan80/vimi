# Layer 1 network clients

Port of the pinned C# `VibeMiner.Cdn` primitives. Public boundaries are `CdnClient`,
`VersionClient` and `ListfileClient`; `NetworkClients` implements all three. Table
definitions are bundled with the application and never fetched. Public values contain keys, bytes, URLs and timestamps. There is no
object-store dependency, build interpretation, cache selection or observation
persistence. Those decisions belong to layer 2.

## Build and checks

Use a full Java 25 JDK and the checked-in Gradle wrapper. No system native library,
.NET SDK, credential, Blizzard account or live endpoint is needed for these tests.
Gradle downloads the pinned and locked HTTP/JSON/test dependencies.

```sh
./gradlew :network-clients:test
./gradlew :network-clients:check :network-clients:assemble
```

Fast tests cover keys/routes, strict version tables, range planning, admission,
lifecycle and shared fake contracts. Integration repeats those contracts against
isolated loopback HTTP servers, plus TLS/HTTP2 and protocol/deadline/cancellation
cases. Public addresses remain constrained; a package-private per-instance test
transport maps them to local fixture servers. No process-global test switch or
production option disables address restrictions or TLS verification.

The test-fixture variant publishes `MemorySources` (independent in-memory clients),
`SourceContracts` and loopback scenario helpers for future layer 2 consumer tests.
Use `testImplementation testFixtures(project(':network-clients'))`.

## Use

```java
NetworkClients clients = new NetworkClients();
CdnClient cdn = clients;
CdnRoute route = new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow");
var request = NetworkTypes.Request.whole(NetworkTypes.ObjectKind.CONFIG, key);
CompletableFuture<NetworkTypes.Reply> result = cdn.fetch(route, request);
// Consume asynchronously; do not join on the JavaFX thread.
// Await accepted work and close clients asynchronously when finished.
CompletableFuture<Void> closing = clients.closeAsync();
```

A request's optional `Check` returns a refusal code or null; layer 2 supplies the
appropriate verifier. A check receives owned bytes and cannot alter the reply.
Streaming `fetchTo` does not run whole-object checks; its sink receives owned
chunks, in order, and its future supplies backpressure. Sink failures remain the
caller's exception. Once any bytes have reached a sink, the client never switches
hosts, preventing mixed-source output.

Use this module's explicit `Cancellation` token. Canceling a result future alone
is not a substitute. Token cancellation aborts an active HTTP call or a pending
sink wait and removes queued work when possible. Futures publish away from the
bounded network workers, so continuations can call back into a client or close it.
Close drains accepted work, releases idle connections and rejects subsequent work.

## Objects and protocol policy

`CdnRoute` admits only the reference's official host names and constrained CDN
paths; keys are opaque 16-byte values. Hosts are tried in advertised order. Whole
replies require 200, and a range requires 206 and an exact single `Content-Range`,
including a matching total when the caller knows it. A 200 to a range is refused
at headers. Encoding, redirects, unexpected media types, wrong lengths, truncation
and over-limit bodies remain distinct failures. Header rejection cancels the call
before closing the unread body, avoiding implicit draining of a whole archive.

`CdnException` retains dated per-host attempts, the final failure, reference-compatible
overall failure precedence, verifier codes and retryability. Network errors never
become persistent object absence. Signed asset query strings are stripped from
failure URLs/messages and are never returned as listfile provenance.

The transport uses OkHttp for HTTP/1.1 and HTTP/2, with ordinary certificate and
host-name validation, no automatic redirects or connection retries, no HTTP cache,
and `Accept-Encoding: identity` to disable transparent decompression. Automatic
503/Retry-After follow-ups are disabled and a per-call guard refuses any hidden
second exchange. Unlike the C# handler's limited reconnect grants, Java exposes
one explicit attempt per host/hop; retries belong to a subsequent request or the
next advertised CDN host. Connection pools retain at most the engine concurrency
in idle connections with a five-minute idle timeout; this is an idle timeout,
not C#'s absolute pooled-connection lifetime.

## Other sources

- Version service: `versions`, `cdns`, `bgdl`, `summary` on `us/eu/kr/tw`, raw bytes,
  1 MiB maximum, no redirects/encoding/ranges; one total header-time budget covers
  the complete document. `VersionTable` separately parses strict UTF-8 pipe tables
  (64 columns, 10,000 rows, 16,384 characters per line) and extracts usable CDN routes.
- Wow-listfile: raw latest-release response (1 MiB) or 8 MiB parts of an asset no
  larger than 512 MiB. A part may follow exactly one HTTPS redirect to either
  approved GitHub asset host, on the default port with no user info. Every range
  and part size is checked. Provenance remains the canonical github.com asset URL.
  One fetch runs at a time; every hop/body shares a five-minute total budget.

The clients do not parse release JSON or listfile CSV into application models. Raw
empty releases are retained as the reference does; downstream parsers decide whether they are usable.

`RangePlanner` is pure: it deduplicates object declarations, selects archive
locations in configured archive order, refuses inconsistent sizes and overlaps,
merges within gap/target/cap bounds and splits replies into owned member bytes.
Index membership selects ranged vs loose, never object size. Its input/output
memory is proportional to the caller's plan; run large plans off the GUI thread.

## Bounds and timing

Defaults: eight active CDN/version operations and 64 queued operations; two active
WoWDBDefs operations and one active listfile operation, each with its own 64-slot
queue. Admission overflow reports `NETWORK_LIMIT`, closed clients `NETWORK_CLOSED`.
Queues do not grow or block caller threads waiting for room. Per-engine daemon
worker threads expire when idle. Accepted queued cancellation does no network work.

Whole CDN reads are at most 64 MiB, streamed objects/ranges at most 512 MiB; streaming
uses 64 KiB chunks and waits for the sink before reading more. Array ownership adds
copies, and returned results/sink-retained bytes are caller-owned/output-sized.
Header deadlines default to 20 seconds for CDN and 30 seconds for GitHub sources;
body idle deadlines reset after each read (30 seconds). Connect timeout is ten
seconds. Total source budgets begin after admission, use monotonic time and span
redirects/multiple requests. Wall-clock timestamps use an injectable `Clock`.
`NetworkOptions` can lower bounds/timeouts for an application or isolated tests.

Runtime dependencies are pinned in build.gradle and all resolved versions in
lockfiles. Dependency provenance and upstream Apache/third-party notices are under
`src/main/resources/META-INF/` and included in the built JAR. The component does not
publish releases, ship a GUI, or require separately installed native assets.

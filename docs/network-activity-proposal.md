# Global HTTP activity and mining indicator proposal

Status: design for API review; no production implementation. Audited against
`4c21343`. This complements the existing preparation/model/terrain indicators;
it does not replace their evidence of local work or accepted rendering.

## Audited coverage

A search of production Java sources found two HTTP implementations:

| Transport | Current entry points | Measurement boundary |
| --- | --- | --- |
| OkHttp in `network-clients/.../HttpEngine.java` | `NetworkClients`: CDN object/range, build/version metadata, definition revision/file, listfile release/range, TACT keys, EG links, Polestar | `HttpEngine.send` and every `Exchange.read` |
| JDK HttpClient in `questie/.../sources/QuestieSourceRepository.java` | Questie release metadata and archive acquisition, through QuestieRepository/DataSourcesService | `request` and `GuardedBody.read` |

The CDN, definitions and listfile HttpEngines have separate bounded queues.
Queue waiting is local scheduling, so an HTTP attempt starts after admission,
immediately before executing the transport, rather than when a job is queued.
OkHttp disables transparent gzip, automatic redirects and retries; listfile
redirects and higher-level alternate attempts are explicit. Questie implements
its own redirect loop and cancellation/deadline body guard. No additional
production URLConnection, URL.openStream or direct HttpClient paths were found.
External browser navigation and build tools are outside application coverage.
Injected third-party clients are not automatically observable.

`BuildApplication.open` currently creates the default NetworkClients, passes it
to caching/native preparation and DataSourcesService, and the latter separately
opens QuestieRepository. One observer must be explicitly threaded through both
chains, including startup metadata. Cache-only reads, decoding, archive expansion,
SQLite work and rendering must never manufacture network bytes/activity.

## Proposed shared API

Add a small JDK-only `network-activity` leaf module used by network-clients,
questie and GUI. This avoids adding the WoW/CDN client and OkHttp dependency to
Questie, or maintaining incompatible duplicate telemetry contracts. Update the
Gradle project/dependency graph and affected CI module selection with the code.

The public surface is intentionally bounded:

```java
interface TransferObserver {
    Attempt begin(Kind kind);
    interface Attempt extends AutoCloseable {
        void headersReceived();
        void readStarted();
        void readFinished(int count); // positive bytes, zero, or EOF (-1)
        void close();               // idempotent terminal
    }
}
```

`Kind` is a fixed enum: CDN, BUILD_METADATA, DEFINITIONS, LISTFILE, TACT_KEYS,
EG_LINK, POLESTAR, QUESTIE. No URI, headers, filenames, account identifiers,
exceptions, credentials or arbitrary job strings enter telemetry. Existing
constructors/open overloads retain no-op behavior; additive observer overloads
allow the application to supply one shared meter. Test injection constructors
remain compatible. Telemetry never owns/cancels a request or closes its body.

The meter supplies immutable snapshots of cumulative received bytes, active
attempt count, phase counts and optional fixed per-kind counters, stamped with
an injected monotonic nano clock. Updates use bounded primitive state and short
synchronized sections. One attempt object per actual request is acceptable;
there is no request registry, retained transport object or per-chunk allocation,
event queue, listener dispatch or JavaFX call. A no-op observer avoids work for
existing consumers. A safe observer adapter isolates RuntimeExceptions from
optional telemetry so instrumentation cannot fail a download; fatal VM Errors
are not suppressed. Counters saturate on overflow instead of throwing into IO.

### Lifecycle and phase semantics

`begin` enters WAITING_RESPONSE (DNS/connect/TLS/request/waiting headers).
`headersReceived` enters HANDOFF. `readStarted` enters WAITING_BODY; a successful
`readFinished(n)` adds positive n and returns to HANDOFF. EOF terminates the
attempt. An exception, cancellation, rejected response, redirect, close or failed
handoff terminates it in finally. All terminal paths are idempotent, including
concurrent cancellation/body-close. Terminal attempts reject phase resurrection.

HANDOFF means an HTTP body is open but the application is not currently asking
for more data. CDN sink writes can block here. It must not be described as a
server wait or as measured CPU work. WAITING_BODY means a client body read is
in progress; transport buffering can satisfy it immediately. These distinctions
are useful without pretending to measure CPU or socket state.

Byte accounting must survive a concurrent close: a positive read result is
recorded once even if termination raced that read, while phase state remains
terminal. Each transport invokes readFinished exactly once per successful read;
finally closes the attempt on a read failure. Thus a received chunk is counted
before Questie's post-read cancellation/deadline checks can discard its return.
No transport synchronization is held while executing observer methods.

An EOF is the authoritative normal end. A validated zero-length/bodyless HTTP
response can end immediately. Initially do not infer EOF solely from advertised
Content-Length: malformed/oversized-body validation and transport EOF behavior
must retain their current contracts. There can be a short HANDOFF interval after
the final positive read until EOF/close; local processing after EOF is not active
network work. Redirect bodies closed unread add zero bytes but still balance
their own attempt; the next hop begins a new one.

### What “received” means

Count actual positive bytes returned by HTTP response-body reads into VibeMiner.
This includes metadata JSON, compressed ZIP/BLTE payloads, unsuccessful partial
attempts, repeated downloads and bytes read before cancellation. It excludes
cache hits, declared-but-unread lengths, expanded/decoded sizes, headers, TLS,
TCP overhead and opaque transport prefetch/discard that the application never
reads. This is application-visible HTTP body throughput, not an operating-system
network monitor. UI help must say so. Do not use object counts, Content-Length,
archive output size or an invented total percentage as substitute measurements.

### Transport ownership details

OkHttp: begin inside send immediately before execute, retaining the attempt
through header validation. Transfer ownership to Exchange only on successful
handoff; otherwise close it in send's existing finally. Instrument Exchange.read
around the sole response-body read. Keep response, cancellation registration,
header deadline, retry policy, size validation and close ownership unchanged.
Exchange.close always ends telemetry in finally, even if response.close fails.

Questie: begin before sendAsync for each redirect hop. Retain ownership until
GuardedBody is constructed; close on failed headers, rejected redirects and all
unhanded-off branches. Existing late-future cleanup must close the body and end
the same attempt even when cancellation races response completion. GuardedBody
records positive reads before post-read checks, ends on EOF, and ends in its
idempotent close finally even if the underlying close fails. Its watcher and
repository shutdown/drain behavior remain intact. Single-byte reads delegate to
the already-instrumented array read and cannot count twice.

## GUI ownership and presentation

One app/stage-owned meter exists before BuildApplication.open and survives route
and build changes until shutdown. One FX-owned model samples at most 4 Hz using
a single 250 ms Timeline. No body-read callback touches FX or posts runLater.
Use a bounded trailing two-second cumulative-sample window (at most nine samples)
and actual nano elapsed time for `(bytesNow - bytesThen) / secondsElapsed`.
The first interval says “Measuring…”; zero/negative elapsed samples cannot divide.
No smoothing can invent bytes. Show binary units consistently (KiB/MiB).

The top-right compact widget shows a mining icon, status, measured speed and
session received total. Examples: “Waiting for network”, “Receiving”, “Waiting
for data”, or “Transfer open” for handoff-only activity. Several simultaneous
phases can show a dominant status with counts in an optional keyboard-accessible
detail popup. Idle shows a static icon and session total. Never label every
idle network interval “Processing”; the existing local pane indicators provide
that information. Short requests between samples may only change the total;
there is no fabricated active duration or progress percentage.

Reuse `gui/src/main/resources/branding/vibeminer.png`. A small JavaFX vector
pickaxe/ore overlay can swing and spark while active, preserving the branding
without generating new raster artwork. JavaFX 25.0.1 locally exposes
`Platform.getPreferences().isReducedMotion()` and reducedMotionProperty:
honor it with a static, visibly active icon and text. Accessible naming announces
meaningful phase changes, not every 250 ms speed update; status is never color-only.

VimiApp.header is rebuilt across routes and wrappers. Own one model, not one
poller per header. Create fresh disposable widget Nodes for each header and
explicitly dispose the old view before replacement; never reparent one shared
Node. Each view removes bindings/preference listeners, closes its popup and
stops its mining animation on disposal. Stop animation and sampling while stage
hidden/iconified and on shutdown. On resume reset the rate baseline (“Measuring…”)
without resetting the session total; do not show hidden-period catch-up spikes.
Network acquisition continues under existing ownership while the widget is hidden.

## Required deterministic verification

* Meter tests: fake monotonic clock, exact positive read counts, phase/terminal
  races, duplicate close, no resurrection, positive bytes racing termination,
  simultaneous kinds, no URL storage, saturation and throwing observer isolation.
* Rate tests: constant stream, zero bytes, short attempts, variable sample times,
  two-second window bound, zero elapsed, hidden/resume baseline and persistent total.
* Existing loopback OkHttp seams: held headers/body, actual metadata bytes,
  partial failure, truncation, oversized response, rejected unread body, sink
  backpressure, explicit redirects/retries, EOF/early close/cancel and failed
  close. Every terminal scenario returns active count to zero; no declared size
  contributes bytes. Existing protocol/cancellation tests must remain unchanged.
* Questie loopback: metadata/archive bytes, redirect and 304, late response after
  canceled future, mid-body cancel/deadline, positive-read/post-check race,
  underlying close failure and closeAsync drain; archive expansion adds no bytes.
* Authored JavaFX test: held activity, speed/total labels, static idle/reduced
  motion, hidden/shutdown stops, repeated route/header rebuilds with exactly one
  sampler and no parent/listener/timer leaks, keyboard-accessible details and no
  sensitive request data. Capture authored branding/UI screenshots if useful.
* Wiring audit: default startup/build/preparation/data-source/Questie paths use
  the same meter; cache/import/render-only operations leave network activity idle.

## Review boundary

Approve the neutral module/API, byte semantics, phase/ownership model and GUI
sampling lifecycle before production edits. Implement meter and transport tests
first, then application wiring/widget. This work does not change network consent,
source acquisition policy, cancellation semantics, credentials or persisted user
settings. It does not claim full machine network utilization or general CPU activity.

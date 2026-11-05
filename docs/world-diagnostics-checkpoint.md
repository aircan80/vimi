# World explorer failure diagnostics checkpoint (#166, first slice)

The reported freeze could not be diagnosed: World failures were collapsed to a
type name or a generic sentence, helper stderr was read and discarded, and later
inventory requests failed through the retirement future with a generic message.
This slice makes those failures visible in the application log and gives the GUI
a short reason. It does not change lifecycle ordering, protocol parsing or
rendering.

## What is now recorded

- **Helper stderr tail.** The World v2 session (`WorldStreamTransport`) and the
  cold model/terrain helper (`GpuPreviewService`) still drain stderr continuously,
  so a chatty helper cannot block on a full pipe. They now keep only the last 40
  lines, each cut to 240 characters (`HelperStderrTail`). Bytes outside printable
  ASCII become `?`, so terminal escapes or binary output cannot forge log lines.
  Nothing is persisted, parsed as protocol or shown in the UI.
- **Abnormal World helper end.** One WARNING record from
  `com.vibeminer.rendering.bgfx.WorldStreamTransport` gives the pid, the exit code,
  whether CLOSED arrived, the bounded failure message and the stderr tail. A clean
  CLOSED with exit 0 logs nothing. If the close deadline expires first, a separate
  record reports the stall with the tail so far, so a hung drain still leaves
  evidence. The protocol failure text now includes the exit code.
- **GUI failure chain.** Logger `com.vimi.gui.World` (`WorldDiagnostics`) records
  lifecycle, protocol, controller, frame-upload, GPU-request, retirement and
  catalog/inventory/tile request failures. Each record gives the cause chain (up to
  6 causes, messages cut to 200 characters, at most 2 KiB) and up to 4 code
  locations. Only JDK exception classes have their message, cause, suppressed and
  stack accessors read. Any other failure contributes just its class name, which
  follows the existing detached-failure rule in the World lifecycle. Many request
  failures are already detached to a class name upstream, so their records stay
  brief. The helpers never throw into lifecycle or FX code.
- **Cancellation.** A site that reports a failure always logs, even when the
  failure is a cancellation, and labels it `(cancellation)`; a FAILED viewer
  always leaves a record. Only sites that treat a clean cancellation as routine
  skip it: World requests, late exterior objects delivery and the water tables.
  "Clean" is exactly `CleanCancellation.is` (#230): up to 8 exact completion
  wrappers around up to 8 exact `CancellationException` links ending in a
  cause-free one, nothing suppressed.
- **Budgets.** Terminal and lifecycle records (including the record carrying the
  renderer stderr tail) have their own 30-a-minute budget. Request records and
  optional-part records each have a separate 20-a-minute budget, so neither can
  rate-limit a terminal record away. The next record admitted by a budget says
  how many that budget dropped.
- **Optional World parts.** Exterior objects, creatures and water degrade a tile
  to terrain only instead of failing World, so their failures used to leave only a
  class name in the status line. They now log through
  `WorldDiagnostics.degraded`: at most one record a minute per part (exterior
  objects, creatures, creature spawns, creature lookups, creature models, water),
  with a count of the similar failures skipped, so a part that fails on every tile
  cannot crowd out terminal records. Covered paths: exterior objects preparation,
  copy, rebuild and late delivery (including a delivery the helper reports
  FAILED); creature spawn index, model lookup, model read and objects-file checks,
  plus the NPC spawn overlay and model inspector; water preparation and the water
  tables. In the transport, a late objects file that cannot be written logs its
  first failure and every 64th after, per session. Clean refusals
  (`Source unavailable`, `water refused`) are expected outcomes and are not
  logged.
- **Unrelated helper stdout.** Only a stdout line that starts with the exact
  `WS2 ` prefix is protocol, and it is held to the same strict rules as before: a
  malformed record or a foreign nonce still ends the session. Any other line (for
  example JVM or JFR startup text) is ignored. It is never decoded, and it ends
  only at a newline, so nothing inside it can begin a record. The first such line
  of a session is logged once, redacted; with JFR on, that WARNING appears once
  per World session. A clean close logs the counts at debug level. The
  abnormal-end record adds the count and a bounded, redacted tail of these
  lines. More than 1 MiB of them ends the session with "World renderer flooded
  stdout". A noise line that contains `WS2 ` or the session's nonce anywhere is a
  mangled record (a lost prefix byte, a byte-order mark, a spliced write), not
  noise: it ends the session at once with "Mangled World status", so a lost
  record cannot stall it silently.
- **Original cause survives.** `WorldStreamGpuOwner` keeps the cause instead of
  replacing it. A queued request that is blocked because the previous World
  resources did not retire now fails with `RetirementBlocked`, which carries the
  original failure. Inventory, tile and catalog status show "Previous World
  renderer did not shut down cleanly (<reason>); reload the workspace."
  The retirement status line and the controller's FAILED detail also show the
  reason (at most 120 characters).

## Privacy

Records contain exception types, bounded messages, code locations, pid/exit status
and sanitized helper stderr. They never contain payload bytes, table rows or keys.
GUI records, status text and the helper stderr tail share one redaction
(`HelperStderrTail.redact`):

- URL query strings and fragments become `?<redacted>`, because signed query
  strings can be bearer capabilities. URL user-info becomes `<redacted>@`.
- First, literal sensitive values are replaced in the original text, longest
  first and in both slash styles: the home and temporary directories, the data
  directory and the folder of an imported client hotfix cache become `<path>`;
  this machine's user and host names become `<redacted>`. Names shorter than four
  characters and common words (`world`, `root`, `user` and so on) are not
  replaced as names, and names match only on Unicode word boundaries. The host
  name comes from `/etc/hostname` and the environment synchronously, and from the
  resolver on a daemon thread; the first redaction waits up to 250 ms for it.
- Then every path-like span becomes `<path>`: `file:` URLs, drive-letter, UNC,
  `\\?\` and `\\.\` paths, POSIX and `~/` paths, including a path right after
  `:` or `=`. A span runs to the next double quote or backtick, an unbalanced
  `)`, `]` or `>`, a `;` or `,`, or the end of the line, so spaces in
  `C:\Program Files (x86)\World of Warcraft` or `C:\Users\John Smith` do not end
  it. This over-redacts rather than leak. Relative names such as `World/maps/...`
  and URLs such as `https://host/a` stay.
- Helper stderr is decoded as UTF-8 and redacted before anything outside
  printable ASCII becomes `?`, so a non-ASCII account name is still recognised.
- Control and line-separator characters are replaced too. Input is cut to 1 KiB
  (GUI) or 4 KiB (redactor) first, and every pattern is bounded, so redaction
  stays cheap on the FX thread.

Review diagnostics before sharing them anyway, as
[data-flow-inventory.md](data-flow-inventory.md) advises: a name that is not a
path and not this machine's identity is not recognised.

## Tests

- `:rendering-bgfx:test`:
  - `HelperStderrTailTest` covers line and length bounds, the omitted-line count,
    the partial last line, control-byte neutralization, and redaction of install
    paths, account names, UNC hosts and URL user-info.
  - `WorldStreamTransportDiagnosticsTest` covers a helper crash with about 600 KB
    of stderr (exit code, failure, bounded and path-redacted tail), a clean
    CLOSED with no record, a no-child start failure, JFR-like text interleaved
    with valid records, a malformed or foreign-nonce record after noise, and a
    stdout flood.
  - `WorldStreamStdoutTest` covers near-misses that stay noise, mangled records
    that fail fast (a spliced byte, a byte-order mark, the prefix or nonce
    mid-line, after a lone CR or past 256 bytes), another session's nonce, a
    stream ending inside noise, and the flood cap.
- `:gui:worldDiagnosticsTest` covers cause-chain bounds, redaction (including
  Unicode line separators, file-system exception paths and the user name),
  pure versus mixed cancellation chains, per-part throttling of degraded-part
  records, cyclic causes, never calling accessors
  on untrusted exceptions, and `RetirementBlocked` carrying the original exit
  reason into the UI text. The `WorldView` inventory wiring itself is covered only
  by the hosted UI tasks.

## Not in this slice (remaining for #166)

- No stalled-frame watchdog yet. It needs rate limits, lifecycle awareness
  (hidden or idle camera must not alarm) and parent/child thread evidence, with
  no perpetual dump loop and no waits on the UI thread.
- If the helper exits before the parent attaches its stream workers, stderr is
  not read. The record then says "helper stderr not captured" instead of claiming
  that stderr was empty.

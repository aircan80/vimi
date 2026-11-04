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
  brief. The helpers never throw into lifecycle or FX code. Cancellation is not
  logged. Logging is limited to 30 records a minute,
  and the next record that gets through says how many were dropped.
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
In both the GUI records and the helper stderr tail, URL query strings and fragments
are replaced by `?<redacted>`, because signed query strings can be bearer
capabilities. Control and line-separator characters are replaced too. Helper stderr can still contain local
paths, for example from a JVM crash notice. That matches the existing guidance in
[data-flow-inventory.md](data-flow-inventory.md): review diagnostics before
sharing them.

## Tests

- `:rendering-bgfx:test`:
  - `HelperStderrTailTest` covers line and length bounds, the omitted-line count,
    the partial last line and control-byte neutralization.
  - `WorldStreamTransportDiagnosticsTest` covers a helper crash with about 600 KB
    of stderr (exit code, failure, bounded tail), a clean CLOSED with no record,
    and a no-child start failure.
- `:gui:worldDiagnosticsTest` covers cause-chain bounds, redaction (including
  Unicode line separators), cancellation, cyclic causes, never calling accessors
  on untrusted exceptions, and `RetirementBlocked` carrying the original exit
  reason into the UI text. The `WorldView` inventory wiring itself is covered only
  by the hosted UI tasks.

## Not in this slice (remaining for #166)

- Protocol stdout still rejects unrelated lines, such as JFR startup output.
  Fixing that needs a dedicated channel or an explicitly framed protocol, plus
  tests that tell bounded unrelated output apart from malformed real records.
- No stalled-frame watchdog yet. It needs rate limits, lifecycle awareness
  (hidden or idle camera must not alarm) and parent/child thread evidence, with
  no perpetual dump loop and no waits on the UI thread.
- If the helper exits before the parent attaches its stream workers, stderr is
  not read. The record then says "helper stderr not captured" instead of claiming
  that stderr was empty.

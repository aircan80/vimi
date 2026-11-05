# Handoff: continue development on Windows

Written 2026-11-05 from the Linux session. Main includes everything through #236. The
latest release is v0.1.0-alpha.26.

## Setup

Follow [continue-windows.md](continue-windows.md): native PowerShell (not WSL),
JDK 25, `.local\sqlite\sqlite3.dll`, the pinned .NET SDK and the `vibeminer`
reference checkout. Then:

```powershell
git switch main
git pull --ff-only
$sqliteLibrary = (Resolve-Path .local\sqlite\sqlite3.dll).Path
.\gradlew.bat :gui:run "-PsqliteLibrary=$sqliteLibrary"
```

Renderer override for A/B checks: `-PgpuBackend=vulkan|d3d11|default`, or
`$env:VIMI_GPU_BACKEND`. Opt-in creatures: `-Dvimi.world.creatures=true`.
Turn outdoor buildings off with `-Dvimi.world.exteriors=false`.

## Rules carried over from the Linux session

- No Claude attribution in commits or PRs: no `Co-Authored-By: Claude`, and no
  "Generated with Claude Code". This lives in the user's global CLAUDE.md on Linux.
  Copy it to the Windows machine's `~/.claude/CLAUDE.md` too.
- Never commit credentials, game data, local paths, account names or hostnames.
- Agents may download game data from the CDN for private checks, but never commit it.
- Durability policy: losing recent writes on power loss is acceptable; corruption
  never is. See [durability-policy.md](durability-policy.md).
- Work on branches and open PRs. Every PR gets an independent review, posted as a PR
  comment, before merge. Merge only when CI is green. Always pass an explicit PR
  number to `gh pr edit` and `gh pr comment`.
- Keep machine load bounded. The Linux box froze once from too many parallel agents
  and Gradle daemons. Run at most about 4 or 5 heavy jobs at once.

## State when Linux handed over

Every PR from the Linux session is merged, including #238 (JavaFX natives in the
app image, pinned deb `Depends`) and #241 (cn region CDN fallback). The Linux
session publishes **v0.1.0-alpha.27** from main. After that, nothing is in flight
on Linux. Pull main and continue here.

Packaging follow-ups from the #238 review (low priority):
- Add `libharfbuzz0b` to `DEB_DEPENDS`. On arm64, the glass and font natives link it directly.
- Consider `Recommends: libvulkan1`. bgfx `dlopen`s it, so `readelf` can't see it.
- Make the packager compute NEEDED sonames from every image ELF and the native JARs,
  and fail on unmapped ones.
- On macOS, `--image-only` skips `codesign --verify`.

## Windows issues to work on

1. **Audio: "Audio output is unavailable on this machine."**
   - Every `AudioFormat` in the app is big-endian (`bigEndian=true`): `VoicePlayback.java:76`,
     `WorldAudioPlayback.java:317`, and the `PROBE` format at `WorldAudioPlayback.java:31`.
     The Windows DirectSound mixer usually supports only little-endian 16-bit PCM.
   - Plan: open the line in little-endian, swap sample bytes before writing, and fall
     back to big-endian only if little-endian is refused.
   - Both catch blocks swallow the real exception. Log it through `WorldDiagnostics`
     or the logger.
   - Verify by playing a race voice clip and World area music.
2. **Zephras Isle (map 2991) shows no buildings.** Southshore does show them on
   Direct3D 11, so the renderer works. Record the World status line on Zephras Isle.
   #236 is now merged, so check again: its buildings may be doodads, which no real data
   drew before #236. They may also be files that could not be read
   ("building objects failed"), or a map that uses one large global model.
3. **Multi-minute walk.** Walk on Direct3D 11 and on Vulkan to confirm the #230 fix (the
   viewer closing on routine cancellation). If anything fails, the log now has the
   cause, redacted (#191).
4. **The object pass fails silently at startup.** `BgfxWorldStreamRenderer.objectPass`
   swallows any shader or texture-check failure, and then every tile reports
   "building objects failed". Make it log and show the reason.
5. **Packaging (#238, merged).** Install the Windows MSI from a prerelease and
   check that JavaFX natives load from the app folder, not `%USERPROFILE%\.openjfx\cache`.
   Run with `-Djavafx.verbose=true`.
6. **Windows CI flakes still seen after #235:** `gpuPreviewServiceTest` (orbit controls),
   `WorldStreamTransportCommitCloseTest` and object-store `WriterAdmissionTest` each failed once
   and passed on rerun. These are easier to chase on a real Windows box.
7. **Storage on native Windows: #24, #25, #26.** Long-pending; see continue-windows.md.

## Queued work, not started (needs no Windows; may stay on Linux)

- #174 step 1: linear filtering and mipmaps for the terrain atlas. Today it uses
  `MIN/MAG/MIP_POINT` in `BgfxWorldStreamRenderer`, and the atlas is 512 × 512 per tile.
- #174 step 2: shader-side layer splatting for sharp ground.
- #184: verify dungeon floors on a real dungeon.
- A full loading retiming with per-phase profiles. Cold TABLES is now about 39 s, down
  from 235 s.
- Real-data follow-ups:
  - creatures on by default, after measuring them;
  - Worgen idle;
  - WMO set doodads crowded out by the 512 doodad cap;
  - races: temporary table states in `RacesService.schema` should throw `Unreadable` so
    partial clothing isn't cached (see the #237 follow-up review).

## Useful references

- Speed-up report and harness: #208, #209, #215, #228, #231, #239 and their PR bodies.
- World diagnostics and redaction: #191, #233.
- Direct3D 11 instance semantics: #204 and `docs/windows-d3d11-terrain-handoff.md`.
  Instanced shaders must use `TEXCOORD31/30/29`, and DX11 binaries come from the
  Windows shader CI job.

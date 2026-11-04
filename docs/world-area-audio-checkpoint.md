# World explorer area music and ambience checkpoint

Issues: [#176](https://github.com/aircan80/vimi/issues/176) (area and subzone music) and
[#178](https://github.com/aircan80/vimi/issues/178) (recorded ambient audio).

## What plays

The World explorer status line has **Music** and **Ambience** toggles, one volume slider
per channel, and a **Day/Night** choice. Both channels start **off**. Nothing is read or
decoded until the user enables a channel.

When a channel is on, the explorer's existing current-area result
(`WorldCurrentArea`, recorded ADT terrain area plus the exact catalog parent chain) is
debounced for 750 ms and resolved off the FX thread:

| Relationship | Source fields |
| --- | --- |
| Area to music | `AreaTable.ZoneMusic` → `ZoneMusic.Sounds[slot]`, `SilenceIntervalMin[slot]`, `SilenceIntervalMax[slot]` |
| Area to ambience | `AreaTable.AmbienceID` → `SoundAmbience.AmbienceID[slot]` |
| Sound kit to files | `SoundKitEntry` rows with `SoundKitID = kit` → `FileDataID` (bounded to 64 entries) |

`slot` is 0 for day and 1 for night. The most specific chain member with a non-zero
relationship wins, independently for music and ambience. A subzone can therefore
override the zone's ambience and still inherit the zone's music. Every field must be a
verified-name integer of the expected shape, and each reference must be a recorded
foreign key (`ZoneMusic.Sounds` keeps its historical `SoundEntries` label). Otherwise the
channel shows that the relationship is unavailable. The other channel is unaffected.

Playback:

- Each channel has one owned worker thread. It owns its original-file read, its decoder
  child process and its audio line. FX code only publishes the desired track and state.
- Starting fades in over 1.5 s. A different sound kit fades the old track out before the
  next one starts. Moving between areas that resolve to the same kit (usually a subzone
  inheriting zone music) keeps the current track playing.
- Music picks one file at random from the kit. After it ends, the channel waits the
  recorded silence interval (capped at 10 minutes) and then picks again.
- Ambience loops on one line. A single-file kit whose decoded PCM fits in 16 MiB (about
  95 s of 44.1 kHz stereo) replays an in-memory copy without a gap. A multi-file kit, or a
  longer file, reopens a random entry and has an audible gap while it is read and decoded.
- A stream that decodes no audio is reported and is not retried until the area, kit or
  toggle changes. Turning a channel on clears its previous track and waits for the
  current area, which also retries an earlier failure.
- Volume changes and toggling a channel off ramp the gain, so changes do not click.
  Turning a channel off releases its decoder and line. Leaving the World view, resetting
  it or closing it stops both channels.
- Missing, protected, oversized and undecodable files appear in the status text, for
  example `Audio file #… is NOT_FOUND.` or `cannot be decoded: …`. They never become
  silent success.

## Decoding

`formats-audio` adds `AudioStreamDecoder`/`PcmStream`/`AudioStreamWorker`:

- The container is identified from bytes only: `OggS` for Vorbis, and an optional
  ID3v2 tag followed by an MPEG-1 frame sync for MPEG audio. RIFF/WAVE, MPEG-2/2.5 and
  everything else are reported as unsupported.
- Encoded input is capped at 16 MiB and decoded output at 15 minutes. Sample rates must be
  8–48 kHz and there must be one or two channels. Ogg framing/CRC preflight reuses
  `OggVoiceInput`.
- The codec runs in a child JVM (`-Xmx128m`, 48 MiB metaspace) under `java.home`, using an
  argument list. Stdout carries only a 12-byte header and big-endian PCM. A decoder that
  produces nothing for 8 s fails. Closing the stream destroys the process.
- Vorbis uses the existing J-Ogg 1.0.6 dependency. MPEG uses
  [JavaMP3](https://github.com/delthas/JavaMP3) 1.0.1 (`fr.delthas:javamp3`, MIT), a
  small pure-Java decoder with no transitive dependencies. It is unmaintained since 2017,
  which is one reason it runs only inside the bounded child. The license is retained in
  `formats-audio/src/main/resources/META-INF/NOTICE-formats-audio`.

The existing 30-second `VoiceDecoder`/`PcmClip` path for Races voice previews is
unchanged.

## Storage and privacy

World audio stores nothing. The original encoded bytes are held in memory only while
the channel decodes them. Decoded PCM streams through a pipe to the audio line.
A single-file ambience loop retains at most one 16 MiB in-memory PCM copy until the
area changes or the channel stops (the transient peak while building it is higher). Unlike Races voice previews, no PCM derivative is
written to the generated store. The [data-flow inventory](data-flow-inventory.md) records
this.

## Not evaluated

These limits are stated in the UI tooltip and every resolved plan:

- game time (the user chooses the day or night slot);
- WMO interior areas (only terrain area is tracked), underwater variants
  (`UwZoneMusic`/`UwAmbience`) and intro music (`IntroSound`, `ZoneIntroMusicTable`);
- player conditions, SoundKit volume/mixing/randomization rules, SoundKit advanced
  settings, `SoundAmbience` start/stop kits and flavor/filter sounds;
- 3D positional emitters, and any claim that this matches the game client.

## Validation

- `:formats-audio:check`: authored Vorbis and MPEG tone fixtures (no game audio), frame
  alignment, big-endian output, malformed/oversized/canceled input, closed streams and the
  stalled-decoder bound.
- `:concepts-world:test --tests '*WorldAreaAudioTest*'`: authored `AreaTable`,
  `ZoneMusic`, `SoundAmbience` and `SoundKitEntry` rows. Covers subzone override and
  inheritance, day/night slots, kits recorded as absent, kits without file entries,
  unknown areas and unverified schema shapes.
- `:gui:worldAudioPlaybackTest` (part of `:gui:check`): controlled lines and authored PCM
  only. Covers fade-in, keeping the same kit, fade-out on replacement, gapless ambience
  from memory, recorded music silence, toggling off, visible missing/undecodable/empty
  sources and owned close. Kit continuity in `WorldAudioControls` relies on one stable
  source per build handle; the JavaFX controls themselves are not unit-tested.

Not run: an audible check on actual game data. A local display was not available for the
opt-in JavaFX World UI tests either.

`authored-tone.ogg` is an authored 440 Hz sine tone, 0.2 seconds at 22,050 Hz mono.
It contains no game, downloaded or third-party sound recording.

Regenerate with FFmpeg (a fixture-authoring tool, not a build/runtime dependency):

```sh
ffmpeg -v error -f lavfi -i 'sine=frequency=440:sample_rate=22050:duration=0.2' -c:a libvorbis -y authored-tone.ogg
```

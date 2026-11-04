`authored-tone.ogg` is an authored 440 Hz sine tone, 0.2 seconds at 22,050 Hz mono.
`authored-tone.mp3` is an authored 440 Hz sine tone, 0.5 seconds at 44,100 Hz stereo,
MPEG-1 Layer III with an empty ID3v2.3 tag.
They contain no game, downloaded or third-party sound recording.

Regenerate with FFmpeg (a fixture-authoring tool, not a build/runtime dependency):

```sh
ffmpeg -v error -f lavfi -i 'sine=frequency=440:sample_rate=22050:duration=0.2' -c:a libvorbis -y authored-tone.ogg
ffmpeg -v error -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=0.5' -ac 2 \
  -c:a libmp3lame -b:a 64k -id3v2_version 3 -write_xing 0 -map_metadata -1 \
  -fflags +bitexact -flags:a +bitexact -y authored-tone.mp3
```

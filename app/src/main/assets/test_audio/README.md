# Test Audio Assets

This directory contains audio files used for regression testing of the music playback system.

## Required Files

### **test_song.mp3**
- **Purpose**: Primary test audio for music playback tests
- **Duration**: 10-30 seconds (short for fast test execution)
- **Used in tests**:
  - `pause_music_while_playing`
  - `resume_music_while_paused`
  - `next_track`
  - `previous_track`
  - `now_playing`
  - `stop_music`

### **test_song_2.mp3** (Optional)
- **Purpose**: Secondary test audio for playlist/queue testing
- **Duration**: 10-30 seconds
- **Used in tests**: Future playlist tests

## Audio Requirements

**Format**: MP3 (supported by Android MediaPlayer)
**Sample Rate**: 44.1kHz recommended
**Bit Rate**: 128kbps or higher
**Channels**: Stereo or mono

## Generating Test Audio

You can generate simple test audio using:

### Option 1: ffmpeg (sine wave tone)
```bash
# Generate 15-second 440Hz tone
ffmpeg -f lavfi -i "sine=frequency=440:duration=15" \
  -codec:a libmp3lame -b:a 128k test_song.mp3
```

### Option 2: Text-to-Speech (more realistic)
```bash
# macOS
say -o test_song.aiff "This is a test audio file for regression testing"
ffmpeg -i test_song.aiff -codec:a libmp3lame test_song.mp3
rm test_song.aiff

# Linux (requires espeak)
espeak "This is a test audio file for regression testing" -w test.wav
ffmpeg -i test.wav -codec:a libmp3lame test_song.mp3
rm test.wav
```

### Option 3: Use Creative Commons Audio
Download short audio clips from:
- [FreeSound.org](https://freesound.org) (CC0 licensed)
- [Incompetech](https://incompetech.com/music/royalty-free/music.html) (CC BY)

## How Test Audio is Used

During regression tests, the `RegressionTestExecutor`:

1. **Copies audio from assets to cache**:
   ```kotlin
   val testFile = copyAssetToCache(
       assetPath = "test_audio/test_song.mp3",
       cacheDir = context.cacheDir
   )
   ```

2. **Loads and plays the audio**:
   ```kotlin
   musicPlayerService.loadAndPlay(testFile.absolutePath)
   ```

3. **Polls until playback starts**:
   ```kotlin
   pollUntil(timeout = 2000) {
       musicPlayerService.getMediaPlayer()?.isPlaying() == true
   }
   ```

4. **Executes test workflow** (pause, resume, next, etc.)

5. **Verifies state changes** (Level 2 verification)

## Asset Location in APK

When the app is built, these files are included in:
```
app.apk/assets/test_audio/
```

Accessible via:
```kotlin
context.assets.open("test_audio/test_song.mp3")
```

## Troubleshooting

**Issue**: `FileNotFoundException` during test
- **Cause**: Audio file missing from assets
- **Solution**: Ensure `test_song.mp3` exists in `app/src/main/assets/test_audio/`

**Issue**: `MediaPlayer` preparation fails
- **Cause**: Invalid MP3 format
- **Solution**: Re-encode with ffmpeg using `-codec:a libmp3lame`

**Issue**: Test times out waiting for playback
- **Cause**: Audio file too large or corrupt
- **Solution**: Use short (10-30s) files, verify with media player

## Git Tracking

**IMPORTANT**: These audio files should be tracked in git because:
- Tests require consistent audio files
- CI/CD needs reproducible test results
- File sizes are small (10-30s = ~300KB each)

Do NOT add `*.mp3` to `.gitignore` in this directory.

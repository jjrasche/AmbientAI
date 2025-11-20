# DEBUG_API.md

**AmbientAI Debug Server API**

The app runs a debug HTTP server on port 8080 for testing and development.

See also: [TESTING.md](TESTING.md) for complete testing guide.

---

## Setup

```bash
# Forward port from device to local machine
adb forward tcp:8080 tcp:8080

# Test connection
curl http://localhost:8080/api/ping
```

---

## STT Simulation & Routing Tests

### Test a single partial transcript

```bash
curl -X POST http://localhost:8080/api/test/partial \
  -H "Content-Type: application/json" \
  -d '{"text": "start task", "elapsed_ms": 2333, "confidence": 0.85}'
```

### Test a sequence (simulates real STT flow)

```bash
curl -X POST http://localhost:8080/api/test/sequence \
  -H "Content-Type: application/json" \
  -d '{
    "partials": [
      {"text": "start", "elapsed_ms": 1500, "confidence": 0.80},
      {"text": "start task", "elapsed_ms": 2300, "confidence": 0.88},
      {"text": "start task grocery shopping", "elapsed_ms": 4100, "confidence": 0.94}
    ]
  }' | jq
```

### Get predefined test scenarios

```bash
curl http://localhost:8080/api/test/scenarios | jq
```

### Test UtteranceEnd (final transcript)

```bash
curl -X POST http://localhost:8080/api/test/utterance_end \
  -H "Content-Type: application/json" \
  -d '{"text": "start task grocery shopping", "elapsed_ms": 4500}' | jq
```

### View current routing configuration

```bash
curl http://localhost:8080/api/config | jq
```

---

## Understanding Test Results

Test responses show the routing decision path:

```json
{
  "text": "start task",
  "elapsed_ms": 2333,
  "word_count": 2,
  "decision": "WAIT",
  "reason": "Utterance appears incomplete",
  "would_trigger": false,
  "matched_workflow": null,
  "tier": "QUICK",
  "confidence": 0.85,
  "decision_path": [
    "TIMING_OK: 2333ms >= 1500ms",
    "NO_CANCELLATION",
    "INCOMPLETE_UTTERANCE"
  ]
}
```

**Key fields:**
- `decision`: WAIT | EXECUTE | CANCEL | CONVERSATIONAL | NO_MATCH
- `would_trigger`: Whether workflow would execute
- `matched_workflow`: Workflow name if matched
- `tier`: INSTANT | QUICK | COMPLEX | CONVERSATIONAL
- `decision_path`: Step-by-step routing logic

---

## Iterating on Routing Logic

1. **Test hypothesis**: Modify routing logic in code
2. **Run test scenarios**: `curl http://localhost:8080/api/test/scenarios | jq`
3. **Analyze results**: Check if decisions match expectations
4. **Refine**: Adjust thresholds/heuristics in `RoutingConfig.kt`
5. **Rebuild & test**: `./gradlew installDebug && curl ...`

---

## Regression Testing

### Run all regression tests

```bash
curl -X POST http://localhost:8080/api/regression/run | jq
```

### Run single test by ID

```bash
# Run a specific test by ID (faster for debugging)
curl -X POST http://localhost:8080/api/regression/run/next_track | jq
curl -X POST http://localhost:8080/api/regression/run/previous_track | jq
curl -X POST http://localhost:8080/api/regression/run/pause_music_while_playing | jq
```

### Get regression test scenarios

```bash
curl http://localhost:8080/api/regression/scenarios | jq
```

### Test result format

```json
{
  "test_id": "pause_music_while_playing",
  "status": "PASSED",
  "duration_ms": 1523,
  "workflow_execution": {
    "id": 456,
    "workflow_name": "pause_music",
    "success": true
  },
  "actions_executed": [
    {"action": "music.pause", "success": true, "latency_ms": 45},
    {"action": "tts.speak", "success": true, "latency_ms": 890}
  ],
  "db_changes": {
    "WorkflowExecution": 1,
    "ActionExecution": 2
  },
  "service_state": {
    "music_player_playing": false,
    "media_player_playing": false  // Level 2 check
  }
}
```

---

## Other Debug Endpoints

### Service status

```bash
curl http://localhost:8080/api/status
```

### List all workflows

```bash
curl http://localhost:8080/api/workflows
```

### Recent transcripts

```bash
curl http://localhost:8080/api/transcripts?limit=5
```

### Trigger workflow directly

```bash
curl -X POST http://localhost:8080/api/workflow/trigger/play_music
```

---

## Testing Requirements Before Completion

When Claude implements a feature, the work is NOT COMPLETE until:

### 1. Build Succeeds

```bash
./gradlew installDebug
# Must complete without errors
```

### 2. App Installs and Runs

```bash
adb shell am force-stop com.ambientai
adb shell am start -n com.ambientai/.MainActivity
# Must launch without crashes
```

### 3. Test Scenarios Pass

```bash
# For voice routing changes
curl http://localhost:8080/api/test/scenarios | jq

# Run each scenario and verify:
# - Expected behaviors match actual results
# - No false positives (triggers when it shouldn't)
# - No false negatives (doesn't trigger when it should)
```

### 4. Results Documented

Create a summary showing:
- What was changed
- Test scenarios executed
- Pass/fail status for each scenario
- Any edge cases discovered
- Recommended next steps

### 5. Documentation Updated

If new test endpoints or workflows were added:
- Document the new endpoints
- Provide curl examples
- Explain what the tests validate

---

## Audio File Management

### Transferring Audio Files from Device

Audio recordings are stored in the app's private storage. To pull binary files correctly:

```bash
# CORRECT: Use exec-out for binary files
adb exec-out run-as com.ambientai cat files/audio_1732123456789.wav > output.wav

# WRONG: shell cat corrupts binary files (especially on Windows)
# adb shell run-as com.ambientai cat files/audio_1732123456789.wav > output.wav
```

### List Audio Files on Device

```bash
adb shell run-as com.ambientai ls -la files/ | grep audio
```

### Copy Audio to Project Test Assets

```bash
# Pull specific recording to test assets
adb exec-out run-as com.ambientai cat files/audio_1732123456789.wav > app/src/main/assets/test_audio/my_test.wav
```

### Verify Audio on Device

You can also verify audio directly on the device through the Database screen in the app:
1. Open the app → Database tab → Transcripts
2. Click on any transcript card with audio (shows "▶ Play" button)
3. Audio will play on device speakers

This helps verify that recordings are stored correctly before pulling them to the project.

---

## Testing Anti-Patterns to Avoid

❌ **Don't**: "I've implemented the feature, please test it"
✅ **Do**: "I've implemented and tested the feature. Here are the results..."

❌ **Don't**: Implement complex logic without testability
✅ **Do**: Build simulator/test harness first, then implement

❌ **Don't**: Rely solely on unit tests that mock everything
✅ **Do**: Test with real components integrated (via debug API)

❌ **Don't**: Test only the happy path
✅ **Do**: Test edge cases, incomplete inputs, cancellations, timing issues

❌ **Don't**: Change core behavior without validating all scenarios still pass
✅ **Do**: Re-run full test suite after any change to routing/workflow logic

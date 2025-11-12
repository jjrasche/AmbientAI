# AmbientAI Debug API

## Setup

### 1. Build and Install
```bash
./gradlew installDebug
```

### 2. Start the Voice Service
Launch the app or long-press the power button to start the voice service

### 3. Set up Port Forwarding (for HTTP API)
```bash
adb forward tcp:8080 tcp:8080
```

---

## Phase 1: ADB Broadcast Commands

### Test Connection
```bash
adb shell am broadcast -a com.ambientai.DEBUG_COMMAND -e cmd "ping"
```

### Get Status
```bash
adb shell am broadcast -a com.ambientai.DEBUG_COMMAND -e cmd "status"
```

### List Workflows
```bash
adb shell am broadcast -a com.ambientai.DEBUG_COMMAND -e cmd "workflows"
```

### Get Recent Transcripts
```bash
adb shell am broadcast -a com.ambientai.DEBUG_COMMAND -e cmd "transcripts:5"
```

### Get Help
```bash
adb shell am broadcast -a com.ambientai.DEBUG_COMMAND -e cmd "help"
```

### View Results
Results are logged to logcat:
```bash
adb logcat -s DebugCommand:D
```

---

## Phase 2: HTTP API

### Base URL
```
http://localhost:8080
```

### Test Connection
```bash
curl http://localhost:8080/api/ping
```

### Get Status
```bash
curl http://localhost:8080/api/status
```

### List Workflows
```bash
curl http://localhost:8080/api/workflows
```

### Get Recent Transcripts
```bash
curl http://localhost:8080/api/transcripts?limit=10
```

### Execute Debug Command
```bash
curl -X POST http://localhost:8080/api/command \
  -H "Content-Type: application/json" \
  -d '{"cmd":"status"}'
```

### Trigger Workflow
```bash
curl -X POST http://localhost:8080/api/workflow/trigger/play_music
```

### View API Documentation
```bash
# In browser (after port forwarding)
http://localhost:8080/
```

---

## Monitoring Logs

### Watch All Logs
```bash
adb logcat -s VoiceService:D WorkflowExecutor:D DeepgramSTT:D DebugCommand:D DebugServer:D
```

### Watch Debug Logs Only
```bash
adb logcat -s DebugCommand:D DebugServer:D
```

### Watch Workflow Execution
```bash
adb logcat -s WorkflowExecutor:D
```

---

## Example Workflows for Claude

### Get Current System State
```bash
curl http://localhost:8080/api/status && \
curl http://localhost:8080/api/workflows && \
curl http://localhost:8080/api/transcripts?limit=5
```

### Trigger and Monitor
```bash
# Trigger workflow
curl -X POST http://localhost:8080/api/workflow/trigger/play_music

# Watch logs
adb logcat -s WorkflowExecutor:D MusicPlayer:D
```

### Continuous Monitoring
```bash
# Terminal 1: Watch logs
adb logcat -s VoiceService:D WorkflowExecutor:D DeepgramSTT:D

# Terminal 2: Poll status every 2 seconds
while true; do curl -s http://localhost:8080/api/status | jq; sleep 2; done
```

---

## Future Enhancements

- [ ] Add log buffer for `/api/logs` endpoint
- [ ] Stream logs via WebSocket
- [ ] Add workflow execution history endpoint
- [ ] Add real-time transcript stream
- [ ] Add database query endpoints
- [ ] Add task/timer management endpoints

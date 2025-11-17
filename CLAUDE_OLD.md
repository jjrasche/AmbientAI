# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Core Development Mindset: Zero-Based Thinking

**START HERE**: Before implementing ANY feature, ask from first principles:

### The Fundamental Question
**"How will I know this works?"**

If the answer is "the user will test it manually," you're doing it wrong.

### Zero-Based Implementation Philosophy

1. **Testability is not optional** - It's the first requirement, not an afterthought
2. **Manual testing is failure** - If you can't automate testing, you haven't finished designing
3. **Ship tested code** - Your work is not done until you've validated it yourself
4. **Build confidence, not hope** - "I think this works" → "I tested this works"

### The Test-First Question Flow

Before writing implementation code, answer these:

1. **What are the inputs to this system?**
   - Can I simulate them without manual interaction?
   - Do I need to build a simulator?

2. **What are the outputs/behaviors?**
   - How do I observe them programmatically?
   - Do I need to add debug endpoints?

3. **What are the edge cases?**
   - Incomplete inputs, timing issues, state transitions
   - Can I generate test scenarios that cover these?

4. **How will I iterate?**
   - Can I change code → rebuild → retest in under 60 seconds?
   - Is my feedback loop fast enough?

If you can't answer these questions, **STOP**. Build the testing infrastructure first.

## Project Overview

AmbientAI is a voice-first AI assistant Android app with workflow automation, context awareness, and task tracking capabilities. It uses button-activated speech recognition, LLM-powered workflow execution, and TTS. Voice activation is triggered via long-press power button for optimal battery life and privacy.

## Development Philosophy: Test-Driven Implementation

When implementing changes to core functionality (especially voice pipeline, routing, workflow execution), you MUST:

1. **Build testability infrastructure FIRST** before implementing the actual change
2. **Test your implementation** using the debug server API before declaring work complete
3. **Document test results** to validate the change meets requirements

### Test-Driven Implementation Workflow

#### Step 1: Identify Testability Gaps
Before implementing a feature, ask:
- Can I test this change without manual interaction?
- Do I need to add test endpoints to the debug server?
- Can I simulate the inputs this code receives?

#### Step 2: Build Testing Infrastructure
Create the testing tools you need:
- Add debug API endpoints (`DebugServer.kt`)
- Create simulator classes (e.g., `SttSimulator.kt` for testing voice routing)
- Add test scenario generators for common cases
- Make configuration tunable at runtime via API

#### Step 3: Implement & Test
- Make your changes
- Install to device: `./gradlew installDebug`
- Run automated tests via debug API
- Validate results match expectations
- Iterate based on test feedback

#### Step 4: Document
- Update CLAUDE.md with new test endpoints
- Add examples of how to test the feature
- Document expected behaviors and edge cases

### Example: Voice Routing Implementation

**Wrong approach** ❌:
1. Implement routing heuristics in VoiceListeningService
2. Deploy and wait for user to manually test by speaking
3. Discover issues days later

**Correct approach** ✅:
1. Create `SttSimulator.kt` to simulate Deepgram partial transcripts
2. Add `/api/test/partial` and `/api/test/sequence` endpoints
3. Generate predefined test scenarios (quick commands, parameterized commands, incomplete utterances)
4. Implement routing logic with `IncompletenessDetector.kt`
5. Run all test scenarios via curl and validate results
6. Document timing thresholds and decision logic
7. Ship with confidence

### When to Apply Test-Driven Approach

**Always test-driven**:
- Voice pipeline changes (STT, routing, workflow triggering)
- Workflow execution logic
- Context assembly and LLM prompt construction
- Music player state transitions
- Task timing and session tracking

**Can skip testing infrastructure**:
- UI layout changes (visually verify)
- Simple data model additions
- Dependency updates
- Documentation updates

### Real Example: Voice Routing Fix (2025-01-13)

**Problem**: "start task" was triggering immediately before user could say "grocery shopping"

**Solution Process**:

1. **Built test infrastructure** (`SttSimulator.kt`, debug API endpoints)
2. **Created test scenarios**:
   - Parameterized commands: "start task grocery shopping"
   - Instant commands: "pause"
   - Incomplete utterances: "place on"
   - Cancellations: "start task wait no"

3. **Implemented context-aware incompleteness detection**:
   - Check workflow `requiresInput` flag
   - Require 2+ words after trigger phrase
   - Detect partial trigger phrases ("start" is part of "start task")

4. **Tested via API**:
   ```bash
   curl -X POST http://localhost:8080/api/test/sequence -d '{
     "partials": [
       {"text": "start", "elapsed_ms": 1500, "confidence": 0.8},
       {"text": "start task", "elapsed_ms": 2300, "confidence": 0.88},
       {"text": "start task grocery shopping", "elapsed_ms": 4100, "confidence": 0.94}
     ]
   }'
   ```

5. **Validated results**: All scenarios passing
   - "start" → WAIT (incomplete)
   - "start task" → WAIT (requires input)
   - "start task grocery shopping" → EXECUTE ✅

6. **Documented** in CLAUDE.md with examples

**Key insight**: Testing infrastructure enabled rapid iteration (6 test cycles) without manual voice testing. Changed logic, rebuilt, tested via curl, fixed issues, repeat.

## Build Commands

### Standard Development
```bash
./gradlew build                    # Full build
./gradlew clean                    # Clean build artifacts
./gradlew assembleDebug           # Build debug APK
./gradlew installDebug            # Install debug APK to device
```

### Testing
```bash
./gradlew test                    # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (requires device/emulator)
./gradlew testDebugUnitTest      # Run debug unit tests only
```

### Code Quality
The project uses ktlint/detekt (configuration pending). Manual style checks against STYLE_GUIDE.md required.

## Required Configuration

API keys must be set in `local.properties` (not tracked in git):
```properties
groq.apiKey=your_key_here
brave.searchApiKey=your_key_here
```

## Architecture

### Layer Structure

**Data Layer** (`com.ambientai.data`)
- ObjectBox entities (NOT Room/SQLite)
- Repository pattern with `IRepository` interfaces
- All repositories return `Flow<List<Entity>>` for reactive UI
- Key entities: Transcript, WorkflowDefinition, WorkflowExecution, ActionExecution, Task, Narrative

**Core Layer** (`com.ambientai.core`)
- Business logic services injected via Hilt singletons
- Workflow system: Router → Executor → ActionHandler
- AI services: GroqLlmService, NarrativeManager
- Audio pipeline: VoiceListeningService, SpeechRecognizer, TextToSpeechService
- Music playback: MusicPlayerService with MediaPlayer and audio focus management
- Context: ContextManager with pluggable ContextProvider implementations

**UI Layer** (`com.ambientai.ui`)
- Jetpack Compose with Flow/StateFlow
- Screens: TimelineScreen, DatabaseScreen, WorkflowReviewScreen
- ViewModels collect repository Flows as StateFlow

### Voice Pipeline Flow

1. User long-presses power button → `VoiceListeningService.startListening()` triggered
2. **Audio feedback**: Ascending start tone (800→1000Hz, 150ms) plays
3. Music auto-pauses if playing
4. `DeepgramSttService` starts STT streaming
5. Partial transcripts → `WorkflowRouter` checks for early triggers (tier-based routing)
6. Final transcript saved to DB → broadcast to UI
7. **Audio feedback**: Descending stop tone (400→200Hz, 150ms) plays when recording stops
8. `WorkflowRouter` matches transcript to workflow via trigger phrases
9. `WorkflowExecutor` executes JSON workflow steps
10. Actions handled by domain services (LLM, TTS, Task, Search, Music, etc.)
11. `TextToSpeechService` speaks response
12. Music auto-resumes with volume fade-in if it was playing before

### Workflow System

Workflows are **JSON-defined** (not code) with:
- Trigger phrases for routing
- Step-based execution with conditional logic
- Variable resolution: `$variable`, `$object.field`, `$array[0]`
- Action namespaces: `llm.prompt`, `tts.speak`, `task.start`, `search.query`, `workflow.getExecutionData`, `music.play`, `music.pause`, `music.next`
- Comprehensive logging: WorkflowExecution + ActionExecution with latency tracking

**Conversational Default**: If no workflow matches, synthetic "conversational_default" workflow created for general LLM interaction.

### Dependency Injection

Hilt modules in `com.ambientai.di`:
- `DatabaseModule`: ObjectBox singleton
- `RepositoryModule`: Repository interface bindings
- `CoreServiceModule`: Core services (TTS, etc.)

All services are `@Singleton` scoped.

### ObjectBox Specifics

- Embedded NoSQL database (faster than Room for object storage)
- Entities use `@Entity` annotation with `@Id var id: Long = 0`
- Relationships: `ToMany<Entity>` (see Task ↔ TaskSession)
- Query builder API: `box.query().equal(...).order(...).build().find()`
- Reactive support via Flow (see repository implementations)

## Code Style (CRITICAL)

Follow `STYLE_GUIDE.md` rigorously:

### Core Principles
1. **DRY above all else** - eliminate repetition
2. **Single-expression functions** - no braces, no return statements
3. **Function composition** - chain operations, avoid intermediate variables
4. **Horizontal compression** - multiple operations on single lines
5. **Functional patterns** - use `.also`, `.let`, `.takeIf`, `.run`

### Style Examples

✅ **Good**:
```kotlin
fun save(transcript: Transcript) = transcript.also { box.put(it) }
fun toggleExclude(id: Long) = box.get(id)?.let { it.excludeFromContext = !it.excludeFromContext; box.put(it) }
```

❌ **Bad**:
```kotlin
fun save(transcript: Transcript): Transcript {
    box.put(transcript)
    return transcript
}
```

### Spacing Rules
- One blank line after class properties
- Zero blank lines between functions
- No logging unless truly necessary
- No comments for self-explanatory code

**Exemplar files**: `ContextManager.kt`, `TimeExtensions.kt`

## Key Implementation Patterns

### Repository Pattern
```kotlin
interface IRepository<T> {
    fun save(entity: T): T
    fun getAll(): Flow<List<T>>
    fun getById(id: Long): T?
    fun delete(id: Long)
}
```

All repositories inject ObjectBox and expose Flow for reactive UI.

### Action Handlers
Each domain service implements `execute(action: String, input: JSONObject): JSONObject`
- Returns results as JSONObject
- Actions use namespace prefix (e.g., "llm.prompt", "task.start")
- Centrally registered in WorkflowExecutor

### Reactive UI Updates
```kotlin
// Repository
fun getAll(): Flow<List<Transcript>> = callbackFlow {
    val subscription = box.subscribe().observer { trySend(box.all) }
    awaitClose { subscription.cancel() }
}

// ViewModel
val transcripts = repository.getAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

// UI
val transcripts by viewModel.transcripts.collectAsState()
```

### Context Exclusion
Transcripts have `excludeFromContext` flag. Recent context for LLM prompts assembled via:
```kotlin
box.query().equal(Transcript_.excludeFromContext, false).order(...).find()
```

## Planned Features

See `strategy_coach_feature_spec.md` for comprehensive strategic coaching feature design:
- Goal hierarchy (Vision → Mission → Objectives → KRIs → KPIs)
- Multi-turn coaching sessions with state tracking
- Daily planner, retrospectives, strategy reviews
- All defined as workflows using existing workflow system

## Development Notes

- **minSdk = 31** (Gemini Nano requirement)
- **targetSdk = 36**
- Kotlin 1.9.24 with Compose compiler 1.5.14
- ObjectBox Gradle plugin must be applied **last** in build.gradle.kts
- Foreground service requires FOREGROUND_SERVICE_MICROPHONE permission
- Voice activation via long-press power button (ACTION_ASSIST intent)
- Music player uses FOREGROUND_SERVICE_MEDIA_PLAYBACK with MediaSession for system integration

## Debug Server & STT Simulation

The app runs a debug HTTP server on port 8080 for testing and development.

### Setup
```bash
# Forward port from device to local machine
adb forward tcp:8080 tcp:8080

# Test connection
curl http://localhost:8080/api/ping
```

### STT Simulation & Routing Tests

**Test a single partial transcript:**
```bash
curl -X POST http://localhost:8080/api/test/partial \
  -H "Content-Type: application/json" \
  -d '{"text": "start task", "elapsed_ms": 2333, "confidence": 0.85}'
```

**Test a sequence (simulates real STT flow):**
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

**Get predefined test scenarios:**
```bash
curl http://localhost:8080/api/test/scenarios | jq
```

**Test UtteranceEnd (final transcript):**
```bash
curl -X POST http://localhost:8080/api/test/utterance_end \
  -H "Content-Type: application/json" \
  -d '{"text": "start task grocery shopping", "elapsed_ms": 4500}' | jq
```

**View current routing configuration:**
```bash
curl http://localhost:8080/api/config | jq
```

### Understanding Test Results

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

### Iterating on Routing Logic

1. **Test hypothesis**: Modify routing logic in code
2. **Run test scenarios**: `curl http://localhost:8080/api/test/scenarios | jq`
3. **Analyze results**: Check if decisions match expectations
4. **Refine**: Adjust thresholds/heuristics in `RoutingConfig.kt`
5. **Rebuild & test**: `./gradlew installDebug && curl ...`

### Testing Requirements Before Completion

When Claude implements a feature, the work is NOT COMPLETE until:

#### 1. Build Succeeds
```bash
./gradlew installDebug
# Must complete without errors
```

#### 2. App Installs and Runs
```bash
adb shell am force-stop com.ambientai
adb shell am start -n com.ambientai/.MainActivity
# Must launch without crashes
```

#### 3. Test Scenarios Pass
```bash
# For voice routing changes
curl http://localhost:8080/api/test/scenarios | jq

# Run each scenario and verify:
# - Expected behaviors match actual results
# - No false positives (triggers when it shouldn't)
# - No false negatives (doesn't trigger when it should)
```

#### 4. Results Documented
Create a summary showing:
- What was changed
- Test scenarios executed
- Pass/fail status for each scenario
- Any edge cases discovered
- Recommended next steps

#### 5. Claude.md Updated
If new test endpoints or workflows were added:
- Document the new endpoints
- Provide curl examples
- Explain what the tests validate

### Testing Anti-Patterns to Avoid

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

### Other Debug Endpoints

```bash
# Service status
curl http://localhost:8080/api/status

# List all workflows
curl http://localhost:8080/api/workflows

# Recent transcripts
curl http://localhost:8080/api/transcripts?limit=5

# Trigger workflow directly
curl -X POST http://localhost:8080/api/workflow/trigger/play_music

# Run regression tests
curl -X POST http://localhost:8080/api/regression/run | jq

# Get regression test scenarios
curl http://localhost:8080/api/regression/scenarios | jq
```

---

## AI Development Protocol

### Overview

This protocol defines how AI (Claude Code) develops features for AmbientAI using **E2E-First Test-Driven Development**.

**Core Principle:** Executable tests ARE the requirements document.

**Benefits:**
- Tests describe expected behavior in code (no ambiguity)
- AI validates its own work (run test, see results)
- Human validates once (review test, then check UX)
- Regression prevention (tests catch future breaks)

---

### Red-Green-Refactor Workflow

Every feature or bug fix follows this workflow:

#### **Step 1: Human Describes Feature**

Human provides plain-language description:
```
Feature: User can pause music while it's playing
When user says "pause" and music is playing
Then music should pause and confirm via TTS
```

#### **Step 2: AI Writes E2E Test (RED Phase)**

AI creates `RegressionTestScenario` in `RegressionTestScenarios.kt`:

```kotlin
private fun pauseMusicWhilePlaying() = RegressionTestScenario(
    testId = "pause_music_while_playing",
    category = "music",
    description = "Pause music when music is playing",
    input = TestInput(
        utterance = "pause",
        elapsedMs = 1200,
        preconditions = mapOf(
            "music_playing" to "test_song.mp3"
        )
    ),
    expected = TestExpectations(
        workflowMatched = "pause_music",
        workflowExecuted = true,
        workflowSuccess = true,
        actionsExecuted = listOf("music.pause", "tts.speak"),
        databaseChanges = mapOf(
            "WorkflowExecution" to DatabaseAssertion(count = 1),
            "ActionExecution" to DatabaseAssertion(count = 2)
        ),
        serviceStateChanges = mapOf(
            "music_player_playing" to false
        ),
        shouldNotExecute = listOf("music.play", "music.next")
    )
)
```

**AI must explain what each assertion validates:**
- `workflowMatched = "pause_music"` → Verifies router selected correct workflow
- `actionsExecuted = ["music.pause", "tts.speak"]` → Verifies action sequence and order
- `music_player_playing = false` → Verifies music actually paused (MediaPlayer state check)
- `shouldNotExecute = ["music.play"]` → Verifies wrong actions didn't execute

#### **Step 3: Human Reviews Test**

Human confirms:
- ✅ Does scenario description match my request?
- ✅ Are assertions validating the right things?
- ✅ Are negative cases covered?

If approved → continue. If not → AI revises.

#### **Step 4: AI Runs Test (Must Fail - RED)**

AI deploys to device and runs test:

```bash
./gradlew installDebug
adb forward tcp:8080 tcp:8080
curl -X POST http://localhost:8080/api/regression/run \
  -d '{"test_id": "pause_music_while_playing"}' | jq
```

**Expected: FAIL** (workflow doesn't exist yet)

**AI reports:**
```
❌ Test FAILED (expected - RED phase)
Reason: No workflow matched for utterance "pause"
Next: Implementing pause_music workflow
```

**If test passes before implementation → TEST IS WRONG** (validating nothing).

#### **Step 5: AI Implements Feature**

AI writes minimum code to make test pass:
- Create workflow JSON definition
- Implement action handlers if needed
- Follow STYLE_GUIDE.md principles

**No test-specific code in services.** All preconditions use real production methods.

#### **Step 6: AI Runs Test (Must Pass - GREEN)**

AI redeploys and runs test:

```bash
./gradlew installDebug
curl -X POST http://localhost:8080/api/regression/run \
  -d '{"test_id": "pause_music_while_playing"}' | jq
```

**Expected: PASS**

**AI reports:**
```
✅ Test PASSED (GREEN phase)
Duration: 1,523ms
WorkflowExecution ID: 456
Actions executed: music.pause (45ms), tts.speak (890ms)
DB changes: +1 WorkflowExecution, +2 ActionExecution
Service state: music_player_playing = false ✅
MediaPlayer.isPlaying() = false ✅ (Level 2 verification)
```

If test fails, AI debugs:
- Check WorkflowExecution.success and error message
- Check which ActionExecution failed
- Check DB state vs expected
- Fix and re-run

#### **Step 7: AI Refactors (If Needed)**

AI improves code while keeping test green:
- Extract helper functions
- Apply DRY principles
- Follow STYLE_GUIDE.md

**After each refactor, re-run test to ensure still GREEN.**

#### **Step 8: Human Validates UX**

Human tests actual workflow on device:
- Speak "pause" while music playing
- Confirm music pauses
- Confirm TTS response sounds good

**Definition of DONE:**
- ✅ Test written and approved by human
- ✅ Test failed before implementation (RED)
- ✅ Code implemented
- ✅ Test passes after implementation (GREEN)
- ✅ All existing tests still pass (no regressions)
- ✅ Human validated UX on device

---

### E2E Test Requirements

#### **Test Structure**

Every E2E test must have:

```kotlin
RegressionTestScenario(
    testId = "unique_descriptive_id",
    category = "domain",  // music, tasks, time, logging, conversational
    description = "Human-readable: what this validates",
    input = TestInput(
        utterance = "what user says",
        elapsedMs = realistic_timing,  // Simulate STT timing
        preconditions = mapOf(...)  // Initial state setup
    ),
    expected = TestExpectations(
        // Positive assertions (what SHOULD happen)
        workflowMatched = "workflow_name",
        workflowExecuted = true,
        workflowSuccess = true,
        actionsExecuted = listOf("action.1", "action.2"),
        databaseChanges = mapOf(...),
        serviceStateChanges = mapOf(...),

        // Negative assertions (what should NOT happen)
        shouldNotExecute = listOf(...),
        shouldNotCreate = listOf(...),
        shouldNotModify = listOf(...)
    )
)
```

#### **Assertion Guidelines**

**Positive assertions (what SHOULD happen):**
1. **Workflow routing** → Correct workflow matched
2. **Execution success** → Workflow completed without errors
3. **Action sequence** → Correct actions executed in correct order
4. **Database side effects** → Entities created/modified with correct values
5. **Service state** → Services in correct state after execution

**Negative assertions (what should NOT happen):**
1. **Wrong actions** → Other workflows' actions didn't execute
2. **Unwanted creation** → Entities that shouldn't exist weren't created
3. **Unwanted modification** → Entities that shouldn't change didn't

**Example with both:**
```kotlin
expected = TestExpectations(
    // Positive
    workflowMatched = "start_task",
    actionsExecuted = listOf("llm.prompt", "task.start", "tts.speak"),
    databaseChanges = mapOf(
        "Task" to DatabaseAssertion(
            count = 1,
            nameContains = "groceries"
        )
    ),

    // Negative
    shouldNotExecute = listOf("music.play", "timer.set"),
    shouldNotCreate = listOf("Timer", "LogEntry")
)
```

---

### Test Infrastructure

#### **In-Memory Database Per Test**

Every test gets fresh, isolated ObjectBox database:

```kotlin
@Before
fun setupTest() {
    // Create ephemeral in-memory database
    testObjectBox = MyObjectBox.builder()
        .androidContext(context)
        .inMemory("test-${UUID.randomUUID()}")
        .build()

    // Inject into repositories via Hilt TestModule
    // All repositories now use isolated test DB
}

@After
fun teardownTest() {
    // Close DB → all data destroyed automatically
    testObjectBox.close()

    // Reset service state (see Service Reset below)
    resetAllServices()
}
```

**Benefits:**
- ✅ Complete test isolation (no state pollution)
- ✅ Auto-cleanup (closing DB destroys all data)
- ✅ Enables parallel test execution
- ✅ Fast (no disk I/O)

#### **Service Reset (No Test Hooks)**

Services need reset capability, but **ZERO test-specific code** in production services.

**How to reset service state:**

```kotlin
// ✅ GOOD: Use normal service methods to reset
class MusicPlayerService {
    fun stop() {
        mediaPlayer.stop()
        mediaPlayer.reset()
        currentMediaPath = null
        playbackState = PlaybackState.IDLE
    }

    fun isPlaying(): Boolean = playbackState == PlaybackState.PLAYING

    fun getMediaPlayer(): MediaPlayer = mediaPlayer  // For Level 2 verification
}

// In test teardown:
musicPlayerService.stop()  // Uses REAL method, not test hook
```

**No `@VisibleForTesting`, no `BuildConfig.DEBUG` checks, no test mode.**

---

### Preconditions System

Preconditions set up initial state before test execution using **real production methods only**.

#### **Precondition Types**

**1. Database state** (entities that should exist):
```kotlin
preconditions = mapOf(
    "media_in_library" to listOf(
        mapOf(
            "title" to "Test Song",
            "artist" to "Test Artist",
            "filePath" to "test_song.mp3"
        )
    ),
    "active_task" to "Existing task name"
)
```

**2. Service state** (non-DB state):
```kotlin
preconditions = mapOf(
    "music_playing" to "test_song.mp3",
    "timer_running" to 300000  // 5 minutes in ms
)
```

#### **Precondition Implementation**

```kotlin
private fun applyPreconditions(preconditions: Map<String, Any>) {
    preconditions.forEach { (key, value) ->
        when (key) {
            // Database state - direct entity creation
            "media_in_library" -> {
                val mediaList = value as List<Map<String, Any>>
                mediaList.forEach { mediaData ->
                    // Copy test audio from assets to device
                    val audioFile = copyAssetToCache(
                        assetPath = "test_audio/${mediaData["filePath"]}",
                        cacheDir = context.cacheDir
                    )

                    // Create Media entity with real file path
                    mediaRepo.save(Media(
                        filePath = audioFile.absolutePath,
                        title = mediaData["title"] as String,
                        artist = mediaData["artist"] as String
                    ))
                }
            }

            "active_task" -> {
                // Use REAL task creation action (no test hooks)
                taskManager.execute("task.start", JSONObject().apply {
                    put("name", value as String)
                })
            }

            // Service state - use REAL methods
            "music_playing" -> {
                // Copy test audio from assets
                val testFile = copyAssetToCache(
                    assetPath = "test_audio/$value",
                    cacheDir = context.cacheDir
                )

                // Use REAL playback method (not test hook)
                musicPlayerService.loadAndPlay(testFile.absolutePath)

                // Poll until ACTUALLY playing (Level 2 verification)
                val playing = pollUntil(timeout = 2000) {
                    musicPlayerService.getMediaPlayer().isPlaying()  // Deep check
                }

                if (!playing) {
                    throw PreconditionFailedException(
                        "Music failed to start within 2 seconds"
                    )
                }
            }

            "timer_running" -> {
                // Use REAL timer action (no test hooks)
                timeManager.execute("timer.set", JSONObject().apply {
                    put("minutes", (value as Long) / 60000)
                })
            }
        }
    }
}

private fun copyAssetToCache(assetPath: String, cacheDir: File): File {
    val outputFile = File(cacheDir, assetPath.substringAfterLast("/"))
    context.assets.open(assetPath).use { input ->
        outputFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return outputFile
}
```

**Key principle:** Preconditions only use real production code paths. No test hooks, no special modes.

---

### Async Handling: Polling Instead of Fixed Delays

**Problem:** `delay(2000)` is unreliable - might be too short or too long.

**Solution:** Poll until workflow completes or timeout.

```kotlin
private suspend fun waitForWorkflowCompletion(
    startTime: Long,
    timeout: Long = 5000
): WorkflowExecution {
    val deadline = System.currentTimeMillis() + timeout

    while (System.currentTimeMillis() < deadline) {
        val recent = workflowExecRepo.getRecent(1).firstOrNull()

        // Check if execution started after test began and completed
        if (recent != null &&
            recent.startTime >= startTime &&
            recent.endTime != null) {
            return recent  // Workflow completed
        }

        delay(100)  // Poll every 100ms
    }

    throw TimeoutException(
        "Workflow did not complete within ${timeout}ms"
    )
}
```

---

### Unit Test Requirements (Core Logic)

While E2E tests cover most functionality, **core routing logic requires unit tests** for:
- Faster feedback (no device deployment)
- Easier debugging (isolated components)
- Edge case coverage (many scenarios)

#### **Components Requiring Unit Tests**

**1. WorkflowRouter**
- Trigger phrase matching
- Query cleaning (removing trigger words)
- LLM intent extraction fallback
- Conversational default creation
- Multiple match detection

**2. IncompletenessDetector**
- Preposition/conjunction detection
- Workflow-specific incompleteness (requiresInput flag)
- Cancellation phrase detection
- Word count thresholds

**3. Voice Pipeline Routing Integration** ← **Critical**
- Partial transcript sequences (simulating Deepgram)
- IncompletenessDetector + WorkflowRouter integration
- Timing-based routing decisions
- UtteranceEnd behavior
- Covers the gap between unit tests and E2E tests

**4. WorkflowExecutor** (already has tests ✅)
- Variable resolution
- Conditional logic
- Error handling

#### **Voice Pipeline Routing Tests Example**

This is the **critical integration test** that validates partial transcript routing:

```kotlin
// Tests the WHEN to route (not just WHICH workflow)
class VoiceRoutingIntegrationTest {

    private lateinit var incompletenessDetector: IncompletenessDetector
    private lateinit var router: WorkflowRouter
    private lateinit var fakeWorkflowRepo: FakeWorkflowDefinitionRepository

    @Before
    fun setup() {
        // Real components (not mocked)
        incompletenessDetector = IncompletenessDetector()
        fakeWorkflowRepo = FakeWorkflowDefinitionRepository()
        router = WorkflowRouter(fakeWorkflowRepo, mockLlm)

        // Seed workflow requiring input
        fakeWorkflowRepo.save(WorkflowDefinition(
            name = "start_task",
            definition = """{"requiresInput": true, "triggers": {"keywords": ["start task"]}}"""
        ))
        router.loadWorkflows()
    }

    @Test
    fun `partial sequence waits until complete`() {
        val partials = listOf(
            Partial("start", elapsedMs = 1500, confidence = 0.80),
            Partial("start task", elapsedMs = 2300, confidence = 0.88),
            Partial("start task grocery shopping", elapsedMs = 4100, confidence = 0.94)
        )

        partials.forEach { partial ->
            val wordCount = partial.text.split("\\s+".toRegex()).size
            val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
            val isIncomplete = incompletenessDetector.isIncomplete(
                partial.text, wordCount, match?.definition
            )

            when (partial.text) {
                "start" -> assertTrue(isIncomplete, "Should wait")
                "start task" -> assertTrue(isIncomplete, "Should wait (requires input)")
                "start task grocery shopping" -> {
                    assertFalse(isIncomplete, "Should be complete")
                    assertNotNull(match, "Should match start_task")
                }
            }
        }
    }

    @Test
    fun `instant command triggers immediately`() {
        fakeWorkflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        val partial = Partial("pause", elapsedMs = 1200, confidence = 0.95)
        val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
        val isIncomplete = incompletenessDetector.isIncomplete(
            partial.text, wordCount = 1, match?.definition
        )

        assertFalse(isIncomplete, "Single word 'pause' should be complete")
        assertNotNull(match, "Should match pause_music")
    }

    @Test
    fun `cancellation phrase stops routing`() {
        val cancelled = incompletenessDetector.detectCancellation(
            "start task wait"
        )
        assertTrue(cancelled, "Should detect 'wait' as cancellation")
    }
}

data class Partial(val text: String, val elapsedMs: Long, val confidence: Float)
```

**What this tests:**
- ✅ Partial transcript sequences (simulating Deepgram behavior)
- ✅ IncompletenessDetector + WorkflowRouter integration
- ✅ Timing-based decisions (elapsedMs thresholds)
- ✅ Workflow-specific incompleteness (requiresInput flag)
- ✅ Cancellation detection

**This bridges the gap** between unit tests (isolated logic) and E2E tests (full workflows).

---

### Validation Protocol

#### **What AI Validates**

After running test, AI checks:

**1. Workflow Execution**
```kotlin
val execution = workflowExecRepo.getRecent(1).firstOrNull()
✅ execution.workflowName == expected.workflowMatched
✅ execution.success == expected.workflowSuccess
✅ execution.endTime != null  // Completed
```

**2. Action Executions**
```kotlin
val actions = actionExecRepo.getRecent(10)
✅ actions.map { it.actionName } == expected.actionsExecuted
✅ actions.all { it.success }
✅ actions in correct order
```

**3. Database Side Effects**
```kotlin
val taskCount = finalState.taskCount - initialState.taskCount
✅ taskCount == expected.count

val createdTask = taskRepo.getRecent(1).firstOrNull()
✅ createdTask.name.contains(expected.nameFragment)
✅ createdTask.status == TaskStatus.ACTIVE
```

**4. Service State Changes (Level 2 Verification)**
```kotlin
// Level 1: Service wrapper
✅ musicPlayerService.isPlaying() == expected.playing

// Level 2: Actual Android component (deeper verification)
✅ musicPlayerService.getMediaPlayer().isPlaying() == expected.playing
```

**5. Negative Assertions**
```kotlin
val executedActions = actions.map { it.actionName }
expected.shouldNotExecute.forEach { forbidden ->
    ✅ !executedActions.contains(forbidden)
}
```

#### **AI Reporting Format**

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

### Test Coverage & Gaps

#### **What E2E Workflow Tests Cover**

✅ **Workflow routing logic**
- Trigger phrase matching
- Query cleaning
- LLM fallback
- Conversational default

✅ **Workflow execution**
- Step-by-step execution
- Variable resolution
- Conditional logic
- Error handling

✅ **Action handlers**
- Business logic in all action services
- Input validation
- Output formatting

✅ **Database operations**
- Entity creation/modification
- Repository queries
- Reactive Flow emissions

✅ **Service orchestration**
- Music playback control
- Task management
- Timer management
- TTS invocation

#### **What E2E Workflow Tests DON'T Cover**

❌ **Real-time voice pipeline timing**
- Covered by **Voice Pipeline Routing Integration Tests** ✅
- Simulates Deepgram partial transcripts
- Tests WHEN to route (not just WHICH workflow)

❌ **Deepgram STT accuracy** (real audio → text)
- Requires manual testing or recorded audio playback
- Trust Deepgram SDK correctness
- Test logic around STT, not STT itself

❌ **Audio feedback quality** (beeps, TTS voice)
- Can test that beeps/TTS are invoked
- Quality requires manual listening

❌ **Network failures** (Groq/Deepgram timeouts)
- Can add error scenario tests with mocking

❌ **Android lifecycle edge cases** (app killed, backgrounded)
- Requires specialized instrumented tests
- Lower priority

#### **Recommended Testing Split**

**Tier 1: E2E workflow tests** (80% of effort)
- Cover all workflows
- Cover all action handlers
- Happy paths + error cases

**Tier 2: Unit tests** (15% of effort)
- WorkflowRouter, IncompletenessDetector
- Voice Pipeline Routing Integration
- Complex business logic

**Tier 3: Manual validation** (5% of effort)
- Voice interaction quality
- TTS/audio feedback
- Edge cases from production

---

### AI Autonomy Boundaries

#### **AI Can Decide Independently**

✅ How to implement feature (following STYLE_GUIDE.md)
✅ Code structure and organization
✅ Helper functions and utilities
✅ Variable names and formatting
✅ Minor refactorings (extract method, rename)
✅ Which existing services/repositories to use

#### **AI Must Ask Permission For**

⚠️ **Architectural changes**
- New layer patterns
- New dependency injection modules
- Major package restructuring

⚠️ **New dependencies**
- Adding libraries to build.gradle.kts
- New Android permissions
- New external APIs

⚠️ **Breaking changes**
- Changing public API signatures
- Removing existing functionality
- Schema migrations

⚠️ **Trade-off decisions**
- Performance vs. correctness
- Simplicity vs. robustness

⚠️ **Large refactorings**
- Changes affecting >100 lines
- Changes spanning multiple packages
- Changes requiring test updates

#### **AI Signals Uncertainty By**

1. **Proposing multiple options** with trade-offs explained
2. **Asking clarifying questions** about requirements
3. **Highlighting unclear aspects** of specifications
4. **Showing impact** of different approaches

---

### Bug Fix Protocol

When user reports a bug:

#### **Step 1: Understand Bug**

AI asks:
- What behavior did you expect?
- What behavior did you observe?
- How to reproduce?

#### **Step 2: Write Failing Test**

AI creates test that reproduces bug (should FAIL):

```kotlin
private fun bugReproduction() = RegressionTestScenario(
    testId = "bug_start_task_triggers_immediately",
    description = "Bug: 'start task' triggers before user finishes",
    input = TestInput(
        utterance = "start task",
        elapsedMs = 2300
    ),
    expected = TestExpectations(
        workflowExecuted = false,  // Should NOT execute yet
        decision = RoutingDecision.WAIT
    )
)
```

#### **Step 3: Confirm Test Reproduces Bug**

```
❌ Test FAILED
Expected: workflowExecuted = false
Actual: workflowExecuted = true
Bug confirmed
```

#### **Step 4: Fix Bug**

AI implements fix (e.g., improve IncompletenessDetector)

#### **Step 5: Verify Test Passes**

```
✅ Test PASSED
Bug fixed: Workflow now waits correctly
```

#### **Step 6: Add to Regression Suite**

Add test to `RegressionTestScenarios.kt` to prevent future regression.

---

### Common Failure Modes & Mitigations

**Failure Mode 1: Plausible But Wrong Code**
- **Mitigation:** RED phase ensures test can fail
- **Mitigation:** Test explains what assertions validate
- **Mitigation:** Human reviews test before implementation

**Failure Mode 2: Tests That Always Pass**
- **Mitigation:** RED phase proves test can fail
- **Mitigation:** Negative assertions (shouldNotExecute)
- **Mitigation:** Level 2 verification (MediaPlayer.isPlaying())

**Failure Mode 3: Environment Mismatch**
- **Mitigation:** Tests run on real device (Instrumented)
- **Mitigation:** Use real services (not all mocked)
- **Mitigation:** Human validates UX after GREEN

**Failure Mode 4: Incomplete Validation**
- **Mitigation:** Test boundary conditions
- **Mitigation:** Test error cases
- **Mitigation:** Negative assertions

**Failure Mode 5: Over-Engineering**
- **Mitigation:** AI asks permission for architectural changes
- **Mitigation:** Follow STYLE_GUIDE.md
- **Mitigation:** Implement minimum code to pass test

---

### Development Checklist

Before declaring feature/bug fix **DONE**, verify:

- [ ] E2E test written and human-approved
- [ ] Test ran and FAILED (RED phase confirmed)
- [ ] Code implemented following STYLE_GUIDE.md
- [ ] Test ran and PASSED (GREEN phase confirmed)
- [ ] All existing tests still pass (no regressions)
- [ ] Code refactored if needed (while keeping GREEN)
- [ ] AI reported results (WorkflowExecution/ActionExecution details)
- [ ] Human validated UX on device

For **core routing logic changes**, also:
- [ ] Unit tests written (WorkflowRouter, IncompletenessDetector)
- [ ] Voice Pipeline Integration tests updated
- [ ] Edge cases covered (prepositions, cancellations)

---

### Test Execution Commands

**Run all tests:**
```bash
# Unit tests (fast, no device)
./gradlew test

# E2E regression tests (device required)
./gradlew installDebug
adb forward tcp:8080 tcp:8080
curl -X POST http://localhost:8080/api/regression/run | jq

# Both
./gradlew test && ./gradlew installDebug && \
  curl -X POST http://localhost:8080/api/regression/run | jq
```

**Run single test:**
```bash
# Single E2E test
curl -X POST http://localhost:8080/api/regression/run \
  -H "Content-Type: application/json" \
  -d '{"test_id": "pause_music_while_playing"}' | jq

# Single unit test
./gradlew test --tests "WorkflowRouterTest"
```

**View scenarios:**
```bash
curl http://localhost:8080/api/regression/scenarios | jq
```

---

### Summary

**Remember:**
1. **Test-first always** (RED → GREEN → REFACTOR)
2. **E2E tests are the contract** (define expected behavior)
3. **Unit tests for core logic** (routing, incompleteness detection)
4. **Voice pipeline integration tests** (partial transcript sequences)
5. **No test hooks in services** (use real methods only)
6. **Level 2 verification** (check actual Android components)
7. **Validate execution-based** (run tests, check results)
8. **Human validates UX** (AI validates logic, human validates experience)

**The goal:** Every feature has executable tests proving it works, preventing regressions, and documenting expected behavior.

---

## Audio Feedback System

**Purpose:** Provide subtle audio cues for recording start/stop without being intrusive.

### Implementation (`AudioFeedbackService.kt`)

**Start Tone:**
- Ascending chirp: 800Hz → 1000Hz over 150ms
- Plays immediately when recording starts
- Indicates microphone is active

**Stop Tone:**
- Descending chirp: 400Hz → 200Hz over 150ms
- Plays when `handleRecordingStopped()` callback fires
- Indicates recording has ended

**Tone Generation:**
- Pure sine waves with amplitude fade-out
- 30% initial amplitude, fades to 15% by end
- 44.1kHz sample rate, 16-bit PCM
- AudioTrack with USAGE_ASSISTANCE_SONIFICATION

### Reliability Considerations

**Stop tone WILL play in these scenarios:**
- Normal workflow completion
- User cancellation (e.g., says "stop")
- Deepgram timeout or network error
- Manual stop via notification button
- VAD detects end of speech

**Stop tone WON'T play (rare):**
- Process killed by Android (low memory, force stop)
- Native crash in Deepgram SDK
- System shutdown/reboot
- Hard JVM crash

**Design rationale:** Simple tones are preferred over continuous ambient noise because:
1. Less annoying if stop tone fails to play
2. Clear indicators without background distraction
3. Minimal battery impact
4. No risk of leftover sound on crash

### Manual Stop via Notification

When recording is active, the foreground notification includes a "Stop" action button that works from:
- Lock screen
- Home screen
- Other apps
- Notification shade

Implementation: `VoiceListeningService.createNotification()` adds action when `deepgramStt?.isRecording() == true`

## Common Tasks

### Adding a New Entity
1. Create data class with `@Entity` annotation in `data/entities/`
2. Add `@Id var id: Long = 0`
3. Define relationships with `ToMany<T>` if needed
4. Create repository interface extending `IRepository<T>`
5. Implement repository with ObjectBox in `data/repositories/`
6. Bind in `RepositoryModule`

### Adding a New Workflow Action
1. Add action handler method to appropriate service (or create new service)
2. Method signature: `execute(action: String, input: JSONObject): JSONObject`
3. Register in `WorkflowExecutor.executeAction()` when/switch
4. Action namespace should match service domain (e.g., "llm.*", "task.*")

### Adding Context Provider
1. Implement `ContextProvider` interface
2. Return structured context string
3. Register in `ContextManager`
4. Context automatically included in LLM prompts

## Workflow JSON Structure

```json
{
  "name": "workflow_name",
  "triggerPhrases": ["phrase one", "phrase two"],
  "steps": [
    {
      "action": "llm.prompt",
      "prompt": "You are helpful. User said: $transcript",
      "outputVariable": "response"
    },
    {
      "action": "tts.speak",
      "text": "$response"
    }
  ]
}
```

Variables can reference: `$variable`, `$object.field`, `$array[0]`, `$nested.object.field`

## Lyrics Semantic Search & Enrichment System

### Overview

The app enriches music library songs with lyrics from Genius API and creates semantic embeddings for intelligent search. Located at http://localhost:8080/lyrics for web UI.

### Architecture

**Key Components:**
- `MediaEnrichmentService.kt` - Background enrichment orchestration
- `SmartSegmenter.kt` - Lyrics segmentation and embedding generation
- `TextEmbedder.kt` - TensorFlow Lite embedding model wrapper
- `LyricsSemanticSearch.kt` - Semantic search via HNSW vector index
- `EnrichmentCleanupService.kt` - Data cleanup and maintenance

**Embedding Model:**
- Model: Universal Sentence Encoder (MediaPipe official)
- Dimensions: 100D (float32)
- File: `app/src/main/assets/all_minilm_l6_v2.tflite` (6MB)
- Vector Index: HNSW (Hierarchical Navigable Small World) via ObjectBox

### Lyrics Segmentation Strategy

**Multi-Level Semantic Segmentation** (`SmartSegmenter.kt:30`):

1. **Clean metadata** - Remove Genius annotations (`[Verse]`, `[Chorus]`, contributor counts)
2. **Split by verses** - Double newlines (`\n\n`) separate major sections
3. **Split verses by lines** - Single newlines within each verse
4. **Group lines semantically** - Cosine similarity < 0.7 threshold creates segment breaks
5. **Filter tiny segments** - Must have > 2 words

**Result:** Songs typically have 1-5 semantically coherent segments per song, each capturing distinct themes/imagery.

**Example ("Purple Gas" by Zach Bryan):**
- Segment 1: Philosophical opening about hills and pride (4 lines)
- Segment 2: Rural imagery (fence wire, Fargo truck, rye bottle) (6 lines)
- Segment 3: Chorus refrain about purple gas plates (2 lines)

### Testing Lyrics Search

**Web UI:** http://localhost:8080/lyrics

**API Endpoint:**
```bash
curl -X POST http://localhost:8080/api/lyrics/search \
  -H "Content-Type: application/json" \
  -d '{"query": "small town rural farming life", "max_results": 5}'
```

**Test Queries That Work Well:**
- "small town rural life" → Finds "Boons" (0.87 similarity)
- "hope for better days" → Finds hopeful songs (0.91-0.93 similarity)
- "late night bar drinking" → Finds "Whiskey Fever" (0.91 similarity)
- "driving at night feeling free" → Finds driving-themed songs

**Test Cases:** See `lyrics_search_test_cases.json` for 20 comprehensive test queries covering emotions, imagery, relationships, and abstract concepts.

### Enrichment Management

**Start/Stop Enrichment:**
```bash
# Start background enrichment (respects Genius API rate limits)
curl -X POST http://localhost:8080/api/enrichment/start

# Stop enrichment
curl -X POST http://localhost:8080/api/enrichment/stop

# Check status
curl http://localhost:8080/api/enrichment/status
```

**Data Management:**
```bash
# Force delete all lyrics transcripts/segments (for re-enrichment)
curl -X POST http://localhost:8080/api/enrichment/force_delete_lyrics

# Fix segments with mediaId=0
curl -X POST http://localhost:8080/api/enrichment/fix_mediaids

# Clean bad embeddings (wrong dimensions, missing model tracking)
curl -X POST http://localhost:8080/api/enrichment/cleanup

# View all transcript data
curl http://localhost:8080/api/media/transcripts
```

### Debugging Segmentation Issues

**Check segmentation quality:**
```bash
# Get recent enrichment with segmentation details
curl http://localhost:8080/api/media/transcripts | \
  python -c "import json, sys; data=json.load(sys.stdin); \
  lyrics=[t for t in data['transcripts'] if t['source']=='lyrics']; \
  print(f'Total: {len(lyrics)} enriched songs'); \
  print(f'Avg segments: {sum(t[\"segment_count\"] for t in lyrics)/len(lyrics):.1f}')"
```

**Watch enrichment logs:**
```bash
adb logcat -s SmartSegmenter:D MediaEnrichment:D
```

Look for:
- `Topic shift: X verses → Y semantic segments` - Shows verse splitting
- `Created X/Y lyrics segments with embeddings` - Confirms embedding generation
- Embedding dimensions should be 100D
- Model should be "Universal Sentence Encoder"

**Common Issues:**
1. **mediaId=0 errors** - Run `/api/enrichment/fix_mediaids` or re-enrich
2. **Duplicate results** - Known issue with HNSW query, filter by unique segment IDs
3. **Wrong segmentation** - Check Genius metadata cleaning (`cleanGeniusMetadata()`)
4. **No results** - Verify segments have embeddings (`has_embedding: true`)

### Enrichment Process Flow

1. `MediaEnrichmentService` queries unenriched Media entities
2. For each song, fetch lyrics from Genius API (rate limited: 2 requests/sec)
3. `SmartSegmenter.segmentLyrics()` processes lyrics:
   - Clean Genius metadata
   - Split into verses (double newlines)
   - For each verse: split by lines, group by semantic similarity
   - Generate 100D embedding for each segment via `TextEmbedder`
   - Save `TranscriptSegment` with embedding + model tracking
4. HNSW index automatically built by ObjectBox for vector search
5. `LyricsSemanticSearch.search()` embeds query and finds nearest neighbors

**Rate Limiting:** Genius API allows ~100 requests/min. Enrichment takes ~2-3 seconds per song.

### Embedding Model Tracking

**Schema:** `TranscriptSegment.embeddingModel` field tracks which model generated embeddings.

**Why:** Allows future model upgrades (e.g., to 384D or 768D models) without mixing incompatible embeddings.

**Current:** All segments should have `embeddingModel: "Universal Sentence Encoder"` and 100 dimensions.

### Known Limitations

1. **Genius metadata pollution** - Some segments may still contain structure markers if Genius format changes
2. **Short songs** - Songs under ~50 words may produce only 1 segment
3. **Similarity threshold** - 0.7 cosine similarity may need tuning per genre/artist
4. **No timing data** - Lyrics segments have `startMs=0, endMs=0` (no timestamps)
5. **Duplicate segments** - Same segment ID can appear multiple times in search results (needs deduplication)

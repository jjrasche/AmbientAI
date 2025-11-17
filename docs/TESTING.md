# TESTING.md

**AmbientAI Testing Guide**

Complete testing documentation for E2E-First Test-Driven Development.

See also: [DEBUG_API.md](DEBUG_API.md) for debug server endpoints.

---

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
- Update documentation with new test endpoints
- Add examples of how to test the feature
- Document expected behaviors and edge cases

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

AI creates `RegressionTestScenario` in `RegressionTestScenarios.kt` with:
- Test ID and description
- Input (utterance, timing, preconditions)
- Expected outcomes (workflow matched, actions executed, DB changes, service state)
- Negative assertions (what should NOT happen)

**AI must explain what each assertion validates.**

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
curl -X POST http://localhost:8080/api/regression/run -d '{"test_id": "..."}' | jq
```

**Expected: FAIL** (workflow doesn't exist yet)

**If test passes before implementation → TEST IS WRONG** (validating nothing).

#### **Step 5: AI Implements Feature**

AI writes minimum code to make test pass:
- Create workflow JSON definition
- Implement action handlers if needed
- Follow STYLE_GUIDE.md principles

**No test-specific code in services.** All preconditions use real production methods.

#### **Step 6: AI Runs Test (Must Pass - GREEN)**

AI redeploys and runs test - **Expected: PASS**

If test fails, AI debugs and fixes until GREEN.

#### **Step 7: AI Refactors (If Needed)**

AI improves code while keeping test green.

**After each refactor, re-run test to ensure still GREEN.**

#### **Step 8: Human Validates UX**

Human tests actual workflow on device to confirm UX.

**Definition of DONE:**
- ✅ Test written and approved by human
- ✅ Test failed before implementation (RED)
- ✅ Code implemented
- ✅ Test passes after implementation (GREEN)
- ✅ All existing tests still pass (no regressions)
- ✅ Human validated UX on device

---

## E2E Test Requirements

### Test Structure

Every E2E test must have:

```kotlin
RegressionTestScenario(
    testId = "unique_descriptive_id",
    category = "domain",  // music, tasks, time, logging, conversational
    description = "Human-readable: what this validates",
    input = TestInput(
        utterance = "what user says",
        elapsedMs = realistic_timing,
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

### Assertion Guidelines

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

---

## Test Infrastructure

### In-Memory Database Per Test

Every test gets fresh, isolated ObjectBox database:

```kotlin
@Before
fun setupTest() {
    testObjectBox = MyObjectBox.builder()
        .androidContext(context)
        .inMemory("test-${UUID.randomUUID()}")
        .build()
}

@After
fun teardownTest() {
    testObjectBox.close()  // Auto-cleanup
    resetAllServices()
}
```

**Benefits:**
- ✅ Complete test isolation (no state pollution)
- ✅ Auto-cleanup (closing DB destroys all data)
- ✅ Enables parallel test execution
- ✅ Fast (no disk I/O)

### Service Reset (No Test Hooks)

Services need reset capability, but **ZERO test-specific code** in production services.

```kotlin
// ✅ GOOD: Use normal service methods to reset
class MusicPlayerService {
    fun stop() {
        mediaPlayer.stop()
        mediaPlayer.reset()
        currentMediaPath = null
        playbackState = PlaybackState.IDLE
    }
}

// In test teardown:
musicPlayerService.stop()  // Uses REAL method, not test hook
```

**No `@VisibleForTesting`, no `BuildConfig.DEBUG` checks, no test mode.**

---

## Preconditions System

Preconditions set up initial state before test execution using **real production methods only**.

### Precondition Types

**1. Database state** (entities that should exist):
```kotlin
preconditions = mapOf(
    "media_in_library" to listOf(
        mapOf("title" to "Test Song", "filePath" to "test_song.mp3")
    ),
    "active_task" to "Existing task name"
)
```

**2. Service state** (non-DB state):
```kotlin
preconditions = mapOf(
    "music_playing" to "test_song.mp3",
    "timer_running" to 300000L  // 5 minutes in ms
)
```

### Precondition Implementation

```kotlin
private fun applyPreconditions(preconditions: Map<String, Any>) {
    preconditions.forEach { (key, value) ->
        when (key) {
            "music_playing" -> {
                // Copy test audio from assets
                val testFile = copyAssetToCache("test_audio/$value", context.cacheDir)

                // Use REAL playback method (not test hook)
                musicPlayerService.loadAndPlay(testFile.absolutePath)

                // Poll until ACTUALLY playing (Level 2 verification)
                pollUntil(timeout = 2000) {
                    musicPlayerService.getMediaPlayer().isPlaying()
                }
            }
        }
    }
}
```

**Key principle:** Preconditions only use real production code paths. No test hooks, no special modes.

---

## Async Handling: Polling Instead of Fixed Delays

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

        if (recent != null && recent.startTime >= startTime && recent.endTime != null) {
            return recent  // Workflow completed
        }

        delay(100)  // Poll every 100ms
    }

    throw TimeoutException("Workflow did not complete within ${timeout}ms")
}
```

---

## Unit Test Requirements (Core Logic)

While E2E tests cover most functionality, **core routing logic requires unit tests** for:
- Faster feedback (no device deployment)
- Easier debugging (isolated components)
- Edge case coverage (many scenarios)

### Components Requiring Unit Tests

**1. WorkflowRouter**
- Trigger phrase matching
- Query cleaning (removing trigger words)
- LLM intent extraction fallback
- Conversational default creation

**2. IncompletenessDetector**
- Preposition/conjunction detection
- Workflow-specific incompleteness (requiresInput flag)
- Cancellation phrase detection

**3. Voice Pipeline Routing Integration** ← **Critical**
- Partial transcript sequences (simulating Deepgram)
- IncompletenessDetector + WorkflowRouter integration
- Timing-based routing decisions
- Covers the gap between unit tests and E2E tests

---

## Test Coverage & Gaps

### What E2E Workflow Tests Cover

✅ **Workflow routing logic**
✅ **Workflow execution**
✅ **Action handlers**
✅ **Database operations**
✅ **Service orchestration**

### What E2E Workflow Tests DON'T Cover

❌ **Real-time voice pipeline timing** - Covered by Voice Pipeline Integration Tests
❌ **Deepgram STT accuracy** - Trust Deepgram SDK
❌ **Audio feedback quality** - Manual testing
❌ **Network failures** - Can add with mocking
❌ **Android lifecycle edge cases** - Lower priority

### Recommended Testing Split

- **Tier 1: E2E workflow tests** (80% of effort)
- **Tier 2: Unit tests** (15% of effort)
- **Tier 3: Manual validation** (5% of effort)

---

## Bug Fix Protocol

When user reports a bug:

1. **Understand Bug**: Ask for expected vs actual behavior
2. **Write Failing Test**: Create test that reproduces bug (should FAIL)
3. **Confirm Reproduction**: Verify test fails as expected
4. **Fix Bug**: Implement fix
5. **Verify Test Passes**: Confirm test now passes
6. **Add to Regression Suite**: Prevent future regression

---

## Test Execution Commands

**Run all tests:**
```bash
# Unit tests (fast, no device)
./gradlew test

# E2E regression tests (device required)
./gradlew installDebug
adb forward tcp:8080 tcp:8080
curl -X POST http://localhost:8080/api/regression/run | jq
```

**Run single test:**
```bash
curl -X POST http://localhost:8080/api/regression/run \
  -d '{"test_id": "pause_music_while_playing"}' | jq
```

**View scenarios:**
```bash
curl http://localhost:8080/api/regression/scenarios | jq
```

---

## Development Checklist

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

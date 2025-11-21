# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**📚 Full Documentation:**
- **[TESTING.md](docs/TESTING.md)** - E2E-First TDD, AI Development Protocol, test infrastructure
- **[DEBUG_API.md](docs/DEBUG_API.md)** - Debug server endpoints, STT simulation, regression testing
- **[STYLE_GUIDE.md](STYLE_GUIDE.md)** - Code style requirements (DRY, single-expression functions)

---

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

**See [TESTING.md](docs/TESTING.md) for complete testing guide.**

---

## Project Overview

AmbientAI is a voice-first AI assistant Android app with workflow automation, context awareness, and task tracking capabilities.

**Key Features:**
- Button-activated speech recognition (long-press power button)
- LLM-powered workflow execution
- Text-to-speech responses
- Music playback with auto-pause/resume
- Task tracking with session timing
- Voice-driven logging (food, medication, workouts)

**Technology Stack:**
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Database**: ObjectBox (NoSQL, embedded)
- **DI**: Hilt
- **STT**: Deepgram
- **LLM**: Groq
- **TTS**: Android TextToSpeech

---

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
./gradlew test                           # Run unit tests
./gradlew connectedAndroidTest          # Run instrumented tests
curl -X POST http://localhost:8080/api/regression/run | jq  # Run E2E tests
```

See [DEBUG_API.md](docs/DEBUG_API.md) for complete testing endpoints.

---

## Required Configuration

API keys must be set in `local.properties` (not tracked in git):
```properties
groq.apiKey=your_key_here
brave.searchApiKey=your_key_here
```

---

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

**Workflow Definitions**: See `WorkflowSeeder.kt` for all production workflows.

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

---

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

---

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

---

## Development Notes

- **minSdk = 31** (Gemini Nano requirement)
- **targetSdk = 36**
- Kotlin 1.9.24 with Compose compiler 1.5.14
- ObjectBox Gradle plugin must be applied **last** in build.gradle.kts
- Foreground service requires FOREGROUND_SERVICE_MICROPHONE permission
- Voice activation via long-press power button (ACTION_ASSIST intent)
- Music player uses FOREGROUND_SERVICE_MEDIA_PLAYBACK with MediaSession for system integration

---

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

---

## Workflow JSON Structure

```json
{
  "triggers": {
    "keywords": ["phrase one", "phrase two"],
    "conditions": {"playbackActive": true}
  },
  "requiresInput": true,
  "steps": [
    {
      "action": "llm.prompt",
      "input": {
        "systemPrompt": "You are helpful.",
        "userPrompt": "User said: $transcript"
      },
      "output": "response"
    },
    {
      "action": "tts.speak",
      "input": {"text": "$response.response"}
    }
  ]
}
```

Variables can reference: `$variable`, `$object.field`, `$array[0]`, `$nested.object.field`

**See `WorkflowSeeder.kt` for complete workflow examples.**

---

## Testing Quick Reference

**Run unit tests:**
```bash
./gradlew test
```

**Run E2E regression tests:**
```bash
./gradlew installDebug
adb forward tcp:8080 tcp:8080
curl -X POST http://localhost:8080/api/regression/run | jq
```

**Run single E2E test (faster for debugging):**
```bash
curl -X POST http://localhost:8080/api/regression/run/get_current_time | jq
curl -X POST http://localhost:8080/api/regression/run/play_music_in_library | jq
```

**Test voice routing:**
```bash
curl -X POST http://localhost:8080/api/test/sequence \
  -d '{"partials": [{"text": "pause", "elapsed_ms": 1200}]}' | jq
```

**View all test scenarios:**
```bash
curl http://localhost:8080/api/regression/scenarios | jq
```

**For complete testing documentation, see:**
- [TESTING.md](docs/TESTING.md) - AI Development Protocol, E2E-First TDD
- [DEBUG_API.md](docs/DEBUG_API.md) - Debug server endpoints

---

## Audio Feedback System

**Purpose:** Provide subtle audio cues for recording start/stop without being intrusive.

**Start Tone:**
- Ascending chirp: 800Hz → 1000Hz over 150ms
- Plays immediately when recording starts

**Stop Tone:**
- Descending chirp: 400Hz → 200Hz over 150ms
- Plays when recording ends

**Reliability:** Stop tone plays in all normal scenarios (workflow completion, cancellation, timeout, VAD end-of-speech). Only fails on process kill or system crashes.

**Implementation:** `AudioFeedbackService.kt` - Pure sine waves with amplitude fade-out, 44.1kHz sample rate, 16-bit PCM.

---

## Special Features

### Lyrics Semantic Search & Enrichment

The app enriches music library songs with lyrics from Genius API and creates semantic embeddings for intelligent search. Located at http://localhost:8080/lyrics for web UI.

**Key Components:**
- `MediaEnrichmentService.kt` - Background enrichment orchestration
- `SmartSegmenter.kt` - Lyrics segmentation and embedding generation
- `TextEmbedder.kt` - TensorFlow Lite embedding model wrapper (Universal Sentence Encoder, 100D)
- `LyricsSemanticSearch.kt` - Semantic search via HNSW vector index

**Test Queries:**
```bash
curl -X POST http://localhost:8080/api/lyrics/search \
  -d '{"query": "small town rural life", "max_results": 5}' | jq
```

**Enrichment Management:**
```bash
# Start enrichment
curl -X POST http://localhost:8080/api/enrichment/start

# Check status
curl http://localhost:8080/api/enrichment/status
```

---

## Planned Features

See `strategy_coach_feature_spec.md` for comprehensive strategic coaching feature design:
- Goal hierarchy (Vision → Mission → Objectives → KRIs → KPIs)
- Multi-turn coaching sessions with state tracking
- Daily planner, retrospectives, strategy reviews
- All defined as workflows using existing workflow system

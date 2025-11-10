# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AmbientAI is a voice-first AI assistant Android app with workflow automation, context awareness, and task tracking capabilities. It uses wake word detection, speech recognition, LLM-powered workflow execution, and TTS for a hands-free experience.

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
picovoice.accessKey=your_key_here
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
- Audio pipeline: VoiceListeningService, WakeWordDetector, SpeechRecognizer, TextToSpeechService
- Context: ContextManager with pluggable ContextProvider implementations

**UI Layer** (`com.ambientai.ui`)
- Jetpack Compose with Flow/StateFlow
- Screens: TimelineScreen, DatabaseScreen, WorkflowReviewScreen
- ViewModels collect repository Flows as StateFlow

### Voice Pipeline Flow

1. `WakeWordDetector` (Porcupine) listens for wake word
2. Wake word detected → `SpeechRecognizer` starts STT
3. Transcript saved to DB → broadcast to UI
4. `WorkflowRouter` matches transcript to workflow via trigger phrases
5. `WorkflowExecutor` executes JSON workflow steps
6. Actions handled by domain services (LLM, TTS, Task, Search, etc.)
7. `TextToSpeechService` speaks response
8. Return to wake word listening

### Workflow System

Workflows are **JSON-defined** (not code) with:
- Trigger phrases for routing
- Step-based execution with conditional logic
- Variable resolution: `$variable`, `$object.field`, `$array[0]`
- Action namespaces: `llm.prompt`, `tts.speak`, `task.start`, `search.query`, `workflow.getExecutionData`
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
- Quick Settings tile (WakeWordTileService) for easy wake word toggle

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

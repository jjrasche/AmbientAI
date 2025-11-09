# Architecture Strengthening - Remaining Work

This document tracks remaining architectural improvements identified during the Hilt DI implementation.

---

## ✅ Completed

- [x] Add Hilt dependencies to build.gradle
- [x] Annotate AmbientAIApp with @HiltAndroidApp
- [x] Create DatabaseModule (provides BoxStore)
- [x] Create RepositoryModule (binds all 7 repositories)
- [x] Create repository interfaces (ITranscriptRepository, etc.)
- [x] Update all 7 repositories with @Inject constructors
- [x] Migrate MainActivity to use @AndroidEntryPoint with injected repositories
- [x] Create TimelineViewModel POC (reference for future MVVM migration)

---

## 🚧 High Priority - DI Completion

### 1. Update VoiceListeningService for Hilt Injection
**Current state:** Service manually creates all dependencies in `initializeComponents()`

**Location:** `/app/src/main/java/com/ambientai/core/VoiceListeningService.kt`

**Current problem:**
```kotlin
class VoiceListeningService : Service() {
    private fun initializeComponents() {
        ttsService = TextToSpeechService(applicationContext, ::handleTtsError)
        wakeWordDetector = WakeWordDetector(applicationContext, ::handleWakeWord)
        speechRecognizer = SpeechRecognizer(applicationContext, ...)
        workflowRouter = WorkflowRouter()
        workflowExecutor = WorkflowExecutor(applicationContext)
        // ... manual creation of all dependencies
    }
}
```

**Solution approach:**
```kotlin
// Option A: Use @AndroidEntryPoint (simpler, if Service supports it)
@AndroidEntryPoint
class VoiceListeningService : Service() {
    @Inject lateinit var workflowRouter: WorkflowRouter
    @Inject lateinit var workflowExecutor: WorkflowExecutor
    // ...
}

// Option B: Use @EntryPoint (required for bound services)
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VoiceListeningServiceEntryPoint {
    fun workflowRouter(): WorkflowRouter
    fun workflowExecutor(): WorkflowExecutor
}

class VoiceListeningService : Service() {
    private lateinit var workflowRouter: WorkflowRouter
    private fun initializeComponents() {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            VoiceListeningServiceEntryPoint::class.java
        )
        workflowRouter = entryPoint.workflowRouter()
        // ...
    }
}
```

**Files to create:**
- Service modules in `/app/src/main/java/com/ambientai/di/ServiceModule.kt`

**Dependencies to provide:**
- TextToSpeechService
- WakeWordDetector
- SpeechRecognizer
- WorkflowRouter
- WorkflowExecutor

---

### 2. Update WorkflowExecutor to Use Injected Repositories
**Current state:** Creates repositories manually

**Location:** `/app/src/main/java/com/ambientai/core/workflow/WorkflowExecutor.kt`

**Current problem:**
```kotlin
class WorkflowExecutor(private val context: Context) {
    private val executionRepo = WorkflowExecutionRepository()
    private val workflowRepo = WorkflowDefinitionRepository()
    private var tts = TextToSpeechService(context)
    private val tasks = TaskManager()
    private val llm = GroqLlmService()
    // ... manual creation
}
```

**Solution:**
```kotlin
@Singleton
class WorkflowExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executionRepo: IWorkflowExecutionRepository,
    private val workflowRepo: IWorkflowDefinitionRepository,
    private val tts: TextToSpeechService,
    private val tasks: TaskManager,
    private val llm: GroqLlmService,
    private val search: SearchService,
    private val logs: LogManager
) {
    // Clean constructor, all dependencies injected
}
```

**Related services to update:**
- TaskManager
- GroqLlmService
- SearchService
- LogManager
- NarrativeManager

---

### 3. Create Service-Level Hilt Modules
**Create:** `/app/src/main/java/com/ambientai/di/CoreServiceModule.kt`

**Provides:**
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object CoreServiceModule {

    @Provides
    @Singleton
    fun provideGroqLlmService(): GroqLlmService = GroqLlmService()

    @Provides
    @Singleton
    fun provideSearchService(): SearchService = SearchService()

    @Provides
    @Singleton
    fun provideTaskManager(taskRepo: ITaskRepository): TaskManager = TaskManager()

    @Provides
    fun provideTextToSpeechService(
        @ApplicationContext context: Context
    ): TextToSpeechService = TextToSpeechService(context)

    @Provides
    fun provideWakeWordDetector(
        @ApplicationContext context: Context
    ): WakeWordDetector = WakeWordDetector(context)

    @Provides
    fun provideSpeechRecognizer(
        @ApplicationContext context: Context
    ): SpeechRecognizer = SpeechRecognizer(context)
}
```

---

## 🔬 Medium Priority - Testing Infrastructure

### 4. Add Repository Unit Tests
**Create:** `/app/src/test/java/com/ambientai/data/repositories/`

**Example test:**
```kotlin
class TranscriptRepositoryTest {
    private lateinit var boxStore: BoxStore
    private lateinit var repository: ITranscriptRepository

    @Before
    fun setup() {
        // Use in-memory ObjectBox for testing
        boxStore = MyObjectBox.builder()
            .directory(File.createTempFile("objectbox", "test"))
            .build()
        repository = TranscriptRepository(boxStore)
    }

    @Test
    fun `save transcript returns transcript with ID`() {
        val transcript = Transcript(text = "Hello", timestamp = 0L)
        val saved = repository.save(transcript)

        assertNotNull(saved.id)
        assertEquals("Hello", saved.text)
    }

    @Test
    fun `toggleExcludeFromContext flips boolean`() {
        val transcript = repository.save(Transcript(text = "Test", timestamp = 0L))
        assertFalse(transcript.excludeFromContext)

        repository.toggleExcludeFromContext(transcript.id)

        val updated = repository.getById(transcript.id)
        assertTrue(updated?.excludeFromContext ?: false)
    }
}
```

**Tests needed for:**
- TranscriptRepository (CRUD, context toggling, flows)
- TaskRepository (start/pause/complete task lifecycle)
- WorkflowDefinitionRepository (enable/disable workflows)
- WorkflowExecutionRepository (execution + action logging)
- All other repositories

---

### 5. Add Workflow Execution Tests
**Create:** `/app/src/test/java/com/ambientai/core/workflow/`

**Mock dependencies:**
```kotlin
class FakeTranscriptRepository : ITranscriptRepository {
    val transcripts = mutableListOf<Transcript>()
    override fun save(transcript: Transcript) = transcript.also { transcripts.add(it) }
    // ... implement interface
}

class WorkflowExecutorTest {
    private lateinit var executor: WorkflowExecutor
    private lateinit var fakeRepo: FakeTranscriptRepository

    @Test
    fun `workflow executes LLM action successfully`() {
        val workflow = WorkflowDefinition(
            name = "Test",
            definition = """{"steps": [{"action": "llm.prompt", "input": {"prompt": "Hello"}}]}"""
        )

        val result = runBlocking { executor.execute(WorkflowMatch(workflow, context)) }

        assertTrue(result is WorkflowResult.Success)
    }
}
```

---

## 🎨 Optional - UI Architecture (MVVM)

### 6. Migrate to ViewModels (Deferred)
**When to implement:**
- Configuration change bugs appear (rotation loses state)
- Adding 3rd screen
- Need to test screen logic

**Reference:** See `/app/src/main/java/com/ambientai/ui/screens/TimelineViewModel.kt` POC

**Migration steps:**
1. Create `TimelineViewModel` (already exists as POC)
2. Create `DatabaseViewModel`
3. Update `MainActivity` to inject ViewModels via `hiltViewModel()`
4. Move repository calls from Composables to ViewModels
5. Update Composables to collect ViewModel StateFlows

**Benefits:**
- State survives configuration changes
- Testable screen logic
- Cleaner separation of concerns

---

## 📋 Low Priority - Code Quality

### 7. Add Workflow JSON Schema Validation
**Approach:** JSON Schema + validation script (not Kotlin DSL)

**Create:** `/workflows/schema.json`
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["name", "triggers", "steps"],
  "properties": {
    "name": {"type": "string"},
    "triggers": {"type": "array", "items": {"type": "string"}},
    "steps": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["action", "input"],
        "properties": {
          "action": {
            "type": "string",
            "enum": ["llm.prompt", "tts.speak", "task.start", "task.pause",
                     "task.complete", "search.query", "log.write", "control.if"]
          },
          "input": {"type": "object"},
          "output": {"type": "string", "pattern": "^[a-zA-Z_][a-zA-Z0-9_]*$"}
        }
      }
    }
  }
}
```

**Create:** `/scripts/verify-workflows.sh`
```bash
#!/bin/bash
# Requires: npm install -g ajv-cli
for workflow in workflows/*.json; do
  echo "Validating $workflow..."
  ajv validate -s workflows/schema.json -d "$workflow" || exit 1
done
echo "✅ All workflows valid"
```

**Add to CI:** GitHub Actions workflow validation step

---

### 8. Add Structured Logging
**Replace:** Ad-hoc println/Log statements

**With:** Timber or similar logging framework
```kotlin
dependencies {
    implementation("com.jakewharton.timber:timber:5.0.1")
}

// In AmbientAIApp.onCreate()
if (BuildConfig.DEBUG) {
    Timber.plant(Timber.DebugTree())
}

// Usage
Timber.d("Workflow executed: %s", workflowName)
Timber.e(exception, "Failed to execute workflow")
```

---

### 9. Add Error Handling Strategy
**Create:** `/app/src/main/java/com/ambientai/core/errors/`

**Domain-specific exceptions:**
```kotlin
sealed class WorkflowException(message: String) : Exception(message)
class WorkflowNotFoundException(id: Long) : WorkflowException("Workflow $id not found")
class ActionExecutionException(action: String, cause: Throwable) : WorkflowException("Action $action failed: ${cause.message}")
class MissingVariableException(varName: String) : WorkflowException("Variable not found: \$$varName")
```

**Result types for operations:**
```kotlin
sealed class Result<out T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Failure(val error: DomainError) : Result<Nothing>()
}
```

---

## 📊 Summary

| Category | Priority | Items | Status |
|----------|----------|-------|--------|
| **DI Completion** | High | 3 | 0/3 complete |
| **Testing** | Medium | 2 | 0/2 complete |
| **UI Architecture** | Optional | 1 | POC exists |
| **Code Quality** | Low | 3 | 0/3 complete |

---

## 🎯 Recommended Implementation Order

1. **VoiceListeningService DI** (biggest remaining manual DI area)
2. **WorkflowExecutor DI** (second biggest manual DI area)
3. **CoreServiceModule** (enables #1 and #2)
4. **Repository unit tests** (validate DI works correctly)
5. **Workflow execution tests** (validate business logic)
6. Everything else as needed

---

## 📝 Notes

- **ViewModels:** Deferred until configuration change bugs or 3rd screen
- **Workflow DSL:** Decided on JSON + schema validation (not Kotlin DSL) for runtime flexibility
- **Repository count:** Keeping all 7 repositories (each has domain-specific operations)
- **Testing:** Infrastructure is configured (JUnit, Espresso) but no tests written yet

---

## 🔗 Key Files Reference

**DI Configuration:**
- `/app/src/main/java/com/ambientai/di/DatabaseModule.kt`
- `/app/src/main/java/com/ambientai/di/RepositoryModule.kt`

**Repositories (all with @Inject):**
- `/app/src/main/java/com/ambientai/data/repositories/TranscriptRepository.kt`
- `/app/src/main/java/com/ambientai/data/repositories/TaskRepository.kt`
- ... (5 more)

**Services needing DI:**
- `/app/src/main/java/com/ambientai/core/VoiceListeningService.kt`
- `/app/src/main/java/com/ambientai/core/workflow/WorkflowExecutor.kt`
- `/app/src/main/java/com/ambientai/core/task/TaskManager.kt`
- `/app/src/main/java/com/ambientai/core/llm/GroqLlmService.kt`
- `/app/src/main/java/com/ambientai/core/search/SearchService.kt`
- `/app/src/main/java/com/ambientai/core/log/LogManager.kt`

**POCs for reference:**
- `/app/src/main/java/com/ambientai/ui/screens/TimelineViewModel.kt` (MVVM pattern example)

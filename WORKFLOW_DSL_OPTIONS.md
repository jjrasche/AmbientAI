# Typed Workflow DSL Options

## THE CONFUSION I CAUSED

I mixed two incompatible approaches:
- ❌ "Define workflows in Kotlin DSL" + "serialize to JSON for DB storage"
- This is nonsense - if you're writing Kotlin, just execute Kotlin!

Let me separate the **real** options:

---

## **OPTION A: JSON Workflows + Verification Script (Least disruption)**

### What stays the same:
- Workflows stored as JSON in ObjectBox database
- Runtime execution via `WorkflowExecutor`
- Can be toggled enabled/disabled at runtime
- Could theoretically be created via UI later

### What changes:
Add a **JSON Schema + verification script** that runs in CI/pre-commit hooks

```json
// workflow-schema.json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["name", "triggers", "steps"],
  "properties": {
    "name": {"type": "string"},
    "triggers": {
      "type": "array",
      "items": {"type": "string"}
    },
    "steps": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["action", "input"],
        "properties": {
          "action": {
            "type": "string",
            "enum": ["llm.prompt", "tts.speak", "task.start", "task.pause", "task.complete", "search.query", "log.write", "control.if"]
          },
          "input": {"type": "object"},
          "output": {"type": "string", "pattern": "^[a-zA-Z_][a-zA-Z0-9_]*$"}
        }
      }
    }
  }
}
```

```bash
# verify-workflows.sh
#!/bin/bash
# Run this in CI or pre-commit hook

for workflow in workflows/*.json; do
  echo "Validating $workflow..."
  # Use ajv-cli or similar JSON schema validator
  ajv validate -s workflow-schema.json -d "$workflow" || exit 1
done

echo "✅ All workflows valid"
```

**What you get:**
- ✅ Schema validation (catches typos, wrong action names)
- ✅ Validates variable names (no spaces, valid identifiers)
- ✅ Validates structure (required fields)
- ✅ Fast feedback in CI
- ✅ Keep runtime flexibility (workflows in DB)

**What you DON'T get:**
- ❌ No compile-time type safety
- ❌ No variable path validation (`$llmResponse.response` not checked)
- ❌ No IDE autocomplete
- ❌ Errors still possible at runtime

**Implementation effort:** 2-4 hours

---

## **OPTION B: Pure Kotlin Workflows (Maximum type safety)**

### Complete paradigm shift:
- ❌ Remove WorkflowDefinition table from DB
- ❌ Remove JSON parsing from WorkflowExecutor
- ✅ Workflows are Kotlin source files
- ✅ Compile-time type safety
- ✅ IDE autocomplete

### How it works:

```kotlin
// app/src/main/java/com/ambientai/workflows/ConversationWorkflow.kt
class ConversationWorkflow : Workflow {
    override val name = "Default Conversation"
    override val triggers = listOf("*")

    override suspend fun execute(context: WorkflowContext): WorkflowResult {
        // Type-safe actions
        val llmResponse: LlmResponse = context.llm.prompt(
            systemPrompt = "You are a helpful assistant",
            userPrompt = context.transcript, // ✅ Compiler knows this exists
            temperature = 0.7
        )

        context.tts.speak(
            text = llmResponse.response // ✅ Compiler validates property exists
        )

        return WorkflowResult.Success
    }
}

// Register workflows in code
object WorkflowRegistry {
    val all: List<Workflow> = listOf(
        ConversationWorkflow(),
        TaskWorkflow(),
        SearchWorkflow(),
        // ... add new workflows here
    )

    val enabled: List<Workflow> = all.filter { it.isEnabled }
}

// WorkflowRouter becomes simple
class WorkflowRouter {
    fun route(transcript: String): Workflow? {
        return WorkflowRegistry.enabled.firstOrNull { workflow ->
            workflow.triggers.any { trigger ->
                if (trigger == "*") true
                else transcript.contains(trigger, ignoreCase = true)
            }
        }
    }
}

// WorkflowExecutor becomes trivial
class WorkflowExecutor {
    suspend fun execute(workflow: Workflow, context: WorkflowContext): WorkflowResult {
        return workflow.execute(context) // Just call the method!
    }
}
```

**What you get:**
- ✅ Compile-time type safety (typo in `llmResponse.respose` → compile error)
- ✅ Full IDE autocomplete
- ✅ Refactoring tools work (rename `LlmResponse.response` → updates all workflows)
- ✅ Stack traces point to exact workflow line
- ✅ Can use Kotlin's full power (loops, conditionals, helpers)
- ✅ No regex variable resolution
- ✅ No JSON parsing overhead

**What you LOSE:**
- ❌ Can't modify workflows without recompiling
- ❌ Can't toggle workflows on/off at runtime (must change code)
- ❌ Can't build a UI to create workflows
- ❌ Workflows not in database (can't query "show me all workflows that use LLM")

**Implementation effort:** 1-2 days (rewrite WorkflowExecutor, migrate workflows)

---

## **OPTION C: Hybrid (The confusion I created)**

Don't do this. It's the worst of both worlds:
- Define in Kotlin → serialize to JSON → store in DB → deserialize → execute
- All the complexity of Kotlin + all the runtime overhead of JSON
- No runtime modification (since source is Kotlin)
- Why serialize if you're not getting runtime modification benefits?

---

## **MY RECOMMENDATION**

### For your app RIGHT NOW:

**Go with Option A: JSON + Verification Script**

**Why?**
1. **Minimal disruption** - Your current architecture works
2. **Catches 80% of bugs** - Schema validation catches most JSON errors
3. **Keeps flexibility** - You can still build workflow UI later
4. **Fast to implement** - 2-4 hours vs 2 days
5. **Database queries still work** - Can track workflow usage, stats
6. **Runtime toggling preserved** - Can enable/disable via DB

### When to switch to Option B:

If you reach these conditions:
- You have 30+ workflows (managing JSON files becomes painful)
- Variable bugs are frequent (`$llmResponse.respone` typos)
- You're certain you'll NEVER need runtime workflow modification
- You want to refactor action signatures (e.g., rename `llm.prompt` → `llm.query`)

---

## **DETAILED COMPARISON**

| Aspect | Option A (JSON + Schema) | Option B (Pure Kotlin) |
|--------|--------------------------|------------------------|
| **Type Safety** | Schema validation only | Full compile-time checking ✅ |
| **Variable Validation** | Regex pattern only | Compiler validates paths ✅ |
| **IDE Support** | JSON editor | Full Kotlin autocomplete ✅ |
| **Runtime Modification** | Yes (DB storage) ✅ | No (code changes required) |
| **Workflow UI** | Possible later ✅ | Not possible ❌ |
| **Database Queries** | Yes (query by trigger, etc.) ✅ | No (workflows are code) ❌ |
| **Toggle Enable/Disable** | Runtime (via DB) ✅ | Must change code ❌ |
| **Debugging** | JSON parse errors | Stack traces to source ✅ |
| **Refactoring** | Manual find/replace | IDE refactoring tools ✅ |
| **Performance** | JSON parsing + regex | Direct execution ✅ |
| **Implementation Time** | 2-4 hours ✅ | 1-2 days |
| **Learning Curve** | JSON Schema (simple) ✅ | Kotlin DSL design (complex) |
| **Migration Effort** | None ✅ | Rewrite all workflows ❌ |

---

## **IMPLEMENTATION ROADMAP (OPTION A)**

### Step 1: Create JSON Schema (30 min)
```bash
npm install -g ajv-cli
# Create workflow-schema.json
```

### Step 2: Export existing workflows to JSON files (1 hour)
```kotlin
// One-time script to export DB workflows to files
fun exportWorkflows() {
    val repo = WorkflowDefinitionRepository()
    repo.getAll().forEach { workflow ->
        val file = File("workflows/${workflow.name.replace(" ", "_")}.json")
        file.writeText(workflow.definition)
    }
}
```

### Step 3: Create verification script (30 min)
```bash
# verify-workflows.sh
#!/bin/bash
for workflow in workflows/*.json; do
  ajv validate -s workflow-schema.json -d "$workflow" || exit 1
done
```

### Step 4: Add to CI (15 min)
```yaml
# .github/workflows/verify.yml
- name: Verify workflows
  run: ./verify-workflows.sh
```

### Step 5: Add pre-commit hook (15 min)
```bash
# .git/hooks/pre-commit
./verify-workflows.sh || exit 1
```

---

## **IMPLEMENTATION ROADMAP (OPTION B)**

### Step 1: Design workflow interfaces (2 hours)
```kotlin
interface Workflow {
    val name: String
    val triggers: List<String>
    suspend fun execute(context: WorkflowContext): WorkflowResult
}

interface WorkflowContext {
    val transcript: String
    val llm: LlmActions
    val tts: TtsActions
    val task: TaskActions
}
```

### Step 2: Implement action classes (4 hours)
```kotlin
class LlmActions(private val service: GroqLlmService) {
    suspend fun prompt(systemPrompt: String? = null, userPrompt: String, temperature: Double = 0.7): LlmResponse
}
```

### Step 3: Migrate existing workflows (2 hours)
Convert each JSON workflow to Kotlin class

### Step 4: Update WorkflowRouter (1 hour)
Remove JSON parsing, use WorkflowRegistry

### Step 5: Update WorkflowExecutor (1 hour)
Remove JSON parsing, just call workflow.execute()

### Step 6: Remove WorkflowDefinition table (30 min)
Database migration

---

## **THE BOTTOM LINE**

**Your question: "Why not just have a workflow verifier script if that's all the typed is doing?"**

**Answer: You're exactly right!**

If you want to keep workflows in JSON (for runtime modification, UI creation later, DB queries), then:
- **Schema validation script** gives you 80% of the benefits
- **Full Kotlin DSL** gives you 100% of type safety but loses runtime flexibility

The serialization approach I suggested was confused thinking. The real choice is:

**JSON workflows + Schema validation (runtime flexible)**
vs
**Kotlin workflows (compile-time safe, runtime fixed)**

For a voice assistant where workflows might evolve frequently and you might want to toggle them on/off, **JSON + schema validation** is the pragmatic choice.

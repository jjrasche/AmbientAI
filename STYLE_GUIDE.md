# AmbientAI Code Style Guide

## Core Principles

**DRY! Above all else - eliminate repetition**

1. **Single-expression functions** - No braces, no return statements
2. **Function composition** - Chain operations, avoid intermediate variables
3. **Horizontal compression** - Multiple operations on single lines
4. **Minimal vertical whitespace** - Functions flow together
5. **Functional patterns** - Pure functions, higher-order functions, immutability
6. **Self-documenting** - Clear naming eliminates comments

## Spacing Rules

- One blank line after class properties
- Zero blank lines between functions
- No blank lines for logical grouping

## Examples

### ✅ Good: Concise, Functional, DRY

```kotlin
class TranscriptRepository {
    private val box: Box<Transcript> = AmbientAIApp.boxStore.boxFor()

    fun save(transcript: Transcript) = transcript.also { box.put(it) }
    fun getById(id: Long) = box.get(id)
    fun delete(id: Long) = box.remove(id)
    fun count() = box.count()
    fun getAll() = box.query().order(Transcript_.timestamp, OrderFlags.DESCENDING).build().find()
    fun toggleExcludeFromContext(id: Long) = box.get(id)?.let { it.excludeFromContext = !it.excludeFromContext; box.put(it) }
}
```

**Why it's good:**
- Single-expression functions throughout
- Uses `.also` to return after mutation
- Uses `.let` for null-safe chaining
- Horizontal chaining for query operations
- No intermediate variables
- No logging/comments (code is self-explanatory)

### ❌ Bad: Verbose, Imperative

```kotlin
class TranscriptRepository {
    private val box: Box<Transcript> = AmbientAIApp.boxStore.boxFor()

    companion object {
        private const val TAG = "TranscriptRepository"
    }

    fun save(transcript: Transcript): Transcript {
        box.put(transcript)
        Log.d(TAG, "Saved transcript ${transcript.id}")
        return transcript
    }

    fun toggleExcludeFromContext(id: Long) {
        val transcript = box.get(id) ?: run {
            Log.w(TAG, "Transcript $id not found")
            return
        }

        val oldValue = transcript.excludeFromContext
        transcript.excludeFromContext = !transcript.excludeFromContext
        box.put(transcript)

        Log.d(TAG, "Toggled: $oldValue -> ${transcript.excludeFromContext}")
    }
}
```

**Why it's bad:**
- Unnecessary braces and return statements
- Logging that adds no value
- Intermediate variables (oldValue, transcript)
- Verbose null handling
- Comments explaining obvious code

## Functional Patterns

### Use functional operators

```kotlin
// ✅ Good
fun updateGrade(id: Long, grade: Int) =
    box.get(id)?.let { it.grade = grade; box.put(it); true } ?: false

// ❌ Bad
fun updateGrade(id: Long, grade: Int): Boolean {
    val entity = box.get(id) ?: return false
    entity.grade = grade
    box.put(entity)
    return true
}
```

### Chain operations

```kotlin
// ✅ Good
fun getRecentContext(chunks: Int) = box.query()
    .equal(Transcript_.excludeFromContext, false)
    .order(Transcript_.timestamp, OrderFlags.DESCENDING)
    .build()
    .find(0, chunks.toLong())
    .takeIf { it.isNotEmpty() }
    ?.reversed()
    ?.joinToString("\n") { "[${dateFormat.format(Date(it.timestamp))}] ${it.text}" } ?: ""

// ❌ Bad
fun getRecentContext(chunks: Int): String {
    val transcripts = box.query()
        .equal(Transcript_.excludeFromContext, false)
        .order(Transcript_.timestamp, OrderFlags.DESCENDING)
        .build()
        .find(0, chunks.toLong())

    if (transcripts.isEmpty()) return ""

    return transcripts.reversed().joinToString("\n") { transcript ->
        "[${dateFormat.format(Date(transcript.timestamp))}] ${transcript.text}"
    }
}
```

## Control Flow

Use expressions over statements:

```kotlin
// ✅ Good - Expression-based
fun getStatus(task: Task) = when {
    task.completedAt != null -> "Completed"
    task.status == PAUSED -> "Paused"
    else -> "Active"
}

// ❌ Bad - Statement-based
fun getStatus(task: Task): String {
    if (task.completedAt != null) {
        return "Completed"
    } else if (task.status == PAUSED) {
        return "Paused"
    } else {
        return "Active"
    }
}
```

## Exemplar Files

Reference these for style compliance:
- `ContextManager.kt` - Perfect functional composition
- `TimeExtensions.kt` - Expression-based when statements
- All entity files - Clean data classes

## Tool Configuration

TODO: Configure ktlint/detekt to enforce:
- No empty constructors
- Single-expression function conversion
- Unused import removal
- Max function length warnings

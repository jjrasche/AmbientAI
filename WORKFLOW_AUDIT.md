# Workflow Audit & Test Behavioral Assumptions

## Usage Analysis (Based on Recent Transcripts)

**High Usage** (Keep & Test):
- `play_music` - 7 recent uses ("play taylor swift", "play kanye west", "play stick season")
- `start_task` - 5 recent uses ("start task programming project")
- `set_timer` - 3 recent uses ("set timer for twelve o'clock", "set timer for ten minutes")
- `pause_music` - 1 use ("pause")
- `log_entry` - 1 use ("log this food...")

**No Recent Usage** (Candidates for deletion/deprecation):
- `pause_task`, `complete_task`, `task_status`, `switch_task`
- `get_time`
- `resume_music`, `next_song`, `previous_song`, `whats_playing`, `list_all_songs`
- `search_youtube`, `download_media`, `search_library`
- `web_search`, `remember_this`

---

## Behavioral Assumptions by Workflow

### TIER 1: Critical Workflows (Test These First)

#### 1. `play_music`
**Trigger:** "play [artist/song]"
**Requires Input:** Yes (artist/song name)

**Test Cases:**

```json
{
  "test_id": "play_music_by_artist",
  "input": {
    "utterance": "play taylor swift",
    "elapsed_ms": 3000
  },
  "expected": {
    "workflow_matched": "play_music",
    "workflow_executed": true,
    "actions": [
      {
        "action": "llm.prompt",
        "output_variable": "query",
        "output_should_contain": "taylor swift"
      },
      {
        "action": "music.play",
        "input_should_contain": {"query": "taylor swift"}
      }
    ],
    "database": {
      "WorkflowExecution": {
        "count": 1,
        "status": "completed",
        "workflow_name": "play_music"
      },
      "ActionExecution": {"count": 2}
    },
    "side_effects": {
      "music_player_state": "playing",
      "currently_playing_contains": "taylor swift"
    }
  }
}
```

```json
{
  "test_id": "play_music_by_song_title",
  "input": {
    "utterance": "play stick season",
    "elapsed_ms": 2800
  },
  "expected": {
    "workflow_matched": "play_music",
    "workflow_executed": true,
    "side_effects": {
      "music_player_state": "playing"
    }
  }
}
```

**Assumptions:**
- LLM extracts query correctly (removes "play", keeps artist/song)
- `music.play` action searches library and starts playback
- Music player state changes to "playing"
- If no match found, should TTS speak error (need to verify this behavior)

**Questions for You:**
1. What should happen if the song/artist isn't in the library?
2. Should it automatically search YouTube and download?
3. Should it use semantic search or exact match?

---

#### 2. `start_task`
**Trigger:** "start task [name]", "working on [name]", "begin task [name]"
**Requires Input:** Yes (task name)

**Test Cases:**

```json
{
  "test_id": "start_task_with_name",
  "input": {
    "utterance": "start task programming project",
    "elapsed_ms": 3500
  },
  "expected": {
    "workflow_matched": "start_task",
    "workflow_executed": true,
    "actions": [
      {
        "action": "llm.prompt",
        "output_variable": "taskName",
        "output_should_contain": "programming project"
      },
      {
        "action": "task.start",
        "input_should_match": {"name": "programming project"}
      },
      {
        "action": "tts.speak",
        "input_should_contain": "Started programming project"
      }
    ],
    "database": {
      "WorkflowExecution": {"count": 1, "status": "completed"},
      "ActionExecution": {"count": 3},
      "Task": {
        "count": 1,
        "name": "programming project",
        "status": "active"
      }
    },
    "side_effects": {
      "active_task_name": "programming project",
      "tts_spoken": "Started programming project"
    }
  }
}
```

**Assumptions:**
- LLM extracts task name after trigger phrase
- Task entity created with status="active"
- TTS speaks confirmation with task name
- Only one task can be active at a time (or can multiple?)

**Questions for You:**
1. Can multiple tasks be active simultaneously?
2. What happens if you "start task X" when task X already exists?
3. Should it auto-pause the current active task before starting a new one?

---

#### 3. `pause_music`
**Trigger:** "pause", "pause music", "stop music"
**Requires Input:** No

**Test Cases:**

```json
{
  "test_id": "pause_music_while_playing",
  "preconditions": {
    "music_player_state": "playing"
  },
  "input": {
    "utterance": "pause",
    "elapsed_ms": 1200
  },
  "expected": {
    "workflow_matched": "pause_music",
    "workflow_executed": true,
    "actions": [
      {"action": "music.pause"},
      {"action": "tts.speak", "input_should_contain": "Music paused"}
    ],
    "side_effects": {
      "music_player_state": "paused",
      "tts_spoken": "Music paused"
    }
  }
}
```

```json
{
  "test_id": "pause_music_while_not_playing",
  "preconditions": {
    "music_player_state": "stopped"
  },
  "input": {
    "utterance": "pause",
    "elapsed_ms": 1200
  },
  "expected": {
    "workflow_matched": "pause_music",
    "workflow_executed": false,
    "reason": "Condition failed: playbackActive must be true"
  }
}
```

**Assumptions:**
- Workflow has condition `playbackActive: true` so it only triggers when music is playing
- If music not playing, workflow shouldn't match (or should it give feedback?)

**Questions for You:**
1. Should "pause" when not playing give user feedback?
2. Or should it silently fail/route to conversational default?

---

#### 4. `set_timer`
**Trigger:** "set a timer", "timer for [duration]"
**Requires Input:** Yes (duration)

**Test Cases:**

```json
{
  "test_id": "set_timer_minutes",
  "input": {
    "utterance": "set timer for ten minutes",
    "elapsed_ms": 3000
  },
  "expected": {
    "workflow_matched": "set_timer",
    "workflow_executed": true,
    "actions": [
      {
        "action": "llm.prompt",
        "output_should_match": {"minutes": 10, "seconds": 0}
      },
      {
        "action": "timer.set",
        "input_should_match": {"minutes": 10, "seconds": 0}
      },
      {
        "action": "tts.speak",
        "input_should_contain": "Timer set for"
      }
    ],
    "side_effects": {
      "timer_active": true,
      "timer_duration_ms": 600000
    }
  }
}
```

```json
{
  "test_id": "set_timer_absolute_time",
  "input": {
    "utterance": "set timer for twelve o'clock",
    "elapsed_ms": 3200
  },
  "expected": {
    "workflow_matched": "set_timer",
    "workflow_executed": true,
    "note": "LLM should convert absolute time to relative duration"
  }
}
```

**Assumptions:**
- LLM can parse natural language durations ("ten minutes" → 10)
- LLM can convert absolute times to relative ("twelve o'clock" → minutes until 12:00)
- Timer actually triggers notification/TTS when it expires

**Questions for You:**
1. Does the timer action exist yet? (I don't see `timer.set` in ActionHandler registration)
2. What happens when timer expires? TTS notification? Android notification?
3. Should timers persist across app restarts?

---

#### 5. `log_entry`
**Trigger:** "log this [content]"
**Requires Input:** Yes (what to log)

**Test Cases:**

```json
{
  "test_id": "log_food_entry",
  "input": {
    "utterance": "log this food I ate lima beans and sweet potatoes and chicken probably 500 600 G overall",
    "elapsed_ms": 6000
  },
  "expected": {
    "workflow_matched": "log_entry",
    "workflow_executed": true,
    "actions": [
      {
        "action": "llm.prompt",
        "output_should_match": {
          "type": "food",
          "data": {
            "name": "lima beans and sweet potatoes and chicken",
            "quantity": "500-600g"
          }
        }
      },
      {
        "action": "log.write",
        "input_should_contain": {"type": "food"}
      },
      {
        "action": "tts.speak",
        "input_should_contain": "Logged food"
      }
    ],
    "database": {
      "LogEntry": {
        "count": 1,
        "type": "food",
        "has_transcript_id": true
      }
    }
  }
}
```

**Assumptions:**
- LLM classifies log type (food, medication, activity, symptom, etc.)
- LLM extracts structured data based on type
- LogEntry entity exists and is linked to Transcript

**Questions for You:**
1. Does the `LogEntry` entity exist? (I don't see it in the codebase)
2. Is `log.write` action implemented?
3. What's the use case for this? Health tracking?

---

### TIER 2: Moderate Usage (Test If Time Permits)

#### 6. `get_time`
**Status:** No recent usage, but simple workflow

**Test Case:**
```json
{
  "test_id": "get_current_time",
  "input": {
    "utterance": "what's the time",
    "elapsed_ms": 1800
  },
  "expected": {
    "workflow_matched": "get_time",
    "workflow_executed": true,
    "side_effects": {
      "tts_spoken_contains": ["AM", "PM"]
    }
  }
}
```

**Questions:**
1. Keep or delete? (Trivial workflow, but might be useful)

---

#### 7-9. Music Control Workflows
- `resume_music`, `next_song`, `previous_song`, `whats_playing`, `list_all_songs`

**Status:** No recent usage, but likely needed for complete music UX

**Recommendation:** Keep but lower testing priority. Test after core workflows pass.

---

### TIER 3: Zero Usage (Candidates for Deletion)

#### Task Management Workflows
- `pause_task` - No usage data
- `complete_task` - No usage data
- `task_status` - No usage data
- `switch_task` - Complex, no usage

**Recommendation:**
- DELETE if task feature is not actively used
- OR keep `complete_task` only (most essential)
- Switch_task is very complex with LLM matching - delete unless proven necessary

#### Media Workflows
- `search_youtube` - No usage
- `download_media` - No usage
- `search_library` - No usage (semantic search)

**Recommendation:**
- DELETE `search_youtube` and `download_media` (redundant with auto-download?)
- KEEP `search_library` if semantic lyrics search is a core feature you want to preserve

#### Utility Workflows
- `web_search` - No usage
- `remember_this` - No usage

**Recommendation:** DELETE both unless you have specific plans for them

---

## Summary Recommendations

### Keep & Test (Tier 1 - 5 workflows):
1. ✅ `play_music` - High usage, core feature
2. ✅ `pause_music` - Core music control
3. ✅ `start_task` - High usage
4. ✅ `set_timer` - Moderate usage
5. ✅ `log_entry` - Used, health tracking feature

### Keep But Don't Test Yet (Tier 2 - 6 workflows):
6. `get_time` - Simple, might be useful
7. `resume_music` - Needed for music UX
8. `next_song` - Needed for music UX
9. `previous_song` - Needed for music UX
10. `whats_playing` - Needed for music UX
11. `list_all_songs` - Debug utility

### Delete (Tier 3 - 9 workflows):
12. ❌ `pause_task`
13. ❌ `complete_task`
14. ❌ `task_status`
15. ❌ `switch_task` (complex, unused)
16. ❌ `search_youtube`
17. ❌ `download_media`
18. ❌ `search_library` (unless semantic search is core)
19. ❌ `web_search`
20. ❌ `remember_this`

---

## Questions Before Building Tests

### 1. Music Workflows
- What should `play_music` do if song not found? Search YouTube? Error message?
- Is semantic search enabled for music? Or exact text match?
- Should music auto-resume after voice interaction?

### 2. Task Workflows
- Can multiple tasks be active simultaneously?
- What happens if you start a task that already exists?
- Do you actually use tasks? Or should we delete all task workflows?

### 3. Timer Workflow
- Is `timer.set` action implemented? I don't see it in the code.
- What happens when timer expires?

### 4. Log Entry Workflow
- Is `LogEntry` entity + `log.write` action implemented?
- What's the use case? Health tracking?

### 5. General
- Should I delete the Tier 3 workflows now, or wait until after testing?
- Any workflows I missed that you DO use regularly?

---

## Next Steps

Once you confirm the behavioral assumptions and answer the questions:

1. I'll delete unused workflows from the database
2. Build test infrastructure (TestScenario schema, executor, debug endpoint)
3. Write test cases for Tier 1 workflows (5 workflows × 2-3 cases each = ~12 tests)
4. Run tests and document results
5. Fix any failures
6. Add Tier 2 workflows if time permits

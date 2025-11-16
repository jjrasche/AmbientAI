# Workflow Consolidation Summary

**Date:** 2025-01-15
**Goal:** Clean up workflows and prepare for regression testing

## Changes Made

### 1. Deleted Workflows (5 total)

**Removed from database and WorkflowSeeder.kt:**
- `web_search` - Unused, no recent triggers
- `remember_this` - Redundant with `log_entry` workflow
- `search_youtube` - Consolidated into smart `play_music`
- `download_media` - Consolidated into smart `play_music`
- `search_library` - Consolidated into smart `play_music`

**Rationale:** These were standalone workflows exposing internal actions (`media.searchAndSelect`, `media.download`, `media.searchLibrary`). User intent is always "I want to listen to X" - the implementation details (library vs YouTube vs semantic search) should be handled by the play_music workflow orchestrator.

### 2. Enhanced play_music Workflow

**Old behavior:**
```json
{
  "steps": [
    {"action": "llm.prompt", "output": "query"},
    {"action": "music.play", "input": {"query": "$query.response"}}
  ]
}
```

**New smart behavior:**
```json
{
  "steps": [
    {"action": "llm.prompt", "output": "query"},
    {"action": "music.play", "output": "musicResult"},
    {"action": "control.if", "condition": "$musicResult.success === false", "then": [
      {"action": "media.searchAndSelect"},  // Search YouTube
      {"action": "control.if", "then": [
        {"action": "media.download"},       // Download selected
        {"action": "music.play"},           // Play downloaded
        {"action": "tts.speak"}
      ]}
    ]}
  ]
}
```

**Flow:**
1. Try exact match in music library (artist/title)
2. If not found → Search YouTube with user selection UI
3. Download selected video as audio
4. Play the downloaded media
5. Speak confirmation

### 3. Final Workflow Count

**Before:** 20 workflows
**After:** 15 enabled workflows + 2 disabled (`thinker`, `review_workflow`)

**Remaining workflows:**
- **Tasks (5):** start_task, pause_task, complete_task, task_status, switch_task
- **Music (6):** play_music, pause_music, resume_music, next_song, previous_song, whats_playing, list_all_songs
- **Logging (1):** log_entry
- **Time (2):** get_time, set_timer

### 4. Code Changes

**Files modified:**
1. `app/src/main/java/com/ambientai/data/WorkflowSeeder.kt`
   - Removed `seedMediaWorkflows()` function
   - Removed `web_search` and `remember_this` workflow definitions
   - Enhanced `play_music` workflow with YouTube fallback logic

2. `app/src/main/java/com/ambientai/debug/DebugServer.kt`
   - Added `DELETE /api/workflows/{id}` endpoint for workflow deletion

## Testing Implications

### Workflows to Test (Priority Order)

**Tier 1 - High Usage:**
1. `play_music` - High usage (7 recent), now has complex fallback logic
2. `start_task` - High usage (5 recent), has auto-pause behavior
3. `set_timer` - Moderate usage (3 recent)
4. `pause_music` - Used (1 recent)
5. `log_entry` - Used (1 recent)

**Tier 2 - Lower Priority:**
6. `get_time` - Simple, no recent usage
7. `resume_music`, `next_song`, `previous_song`, `whats_playing`, `list_all_songs` - Needed for music UX
8. `pause_task`, `complete_task`, `task_status`, `switch_task` - Task management

### Test Scenarios Needed

**play_music workflow:**
- ✅ Song exists in library → plays immediately
- ✅ Song not in library → searches YouTube → user selects → downloads → plays
- ✅ Song not on YouTube → speaks "Not found"
- ✅ Download fails → speaks "Download failed"

**start_task workflow:**
- ✅ Start new task → creates task, auto-pauses previous
- ✅ Start existing task → resumes or creates new?

**set_timer workflow:**
- ✅ "set timer for 10 minutes" → creates timer
- ✅ "set timer for twelve o'clock" → converts absolute time to relative duration

## Next Steps

1. ✅ Delete unused workflows (DONE)
2. ✅ Enhance play_music with smart search (DONE)
3. ⏳ Build regression test infrastructure
4. ⏳ Write test scenarios for Tier 1 workflows
5. ⏳ Run tests and validate

## Notes

- All core features (tasks, logging, timer, music) are fully implemented in the codebase
- `TaskManager.kt:16` already auto-pauses previous task when starting a new one
- `TimeManager.kt` implements timer.set with Android AlarmManager
- `LogManager.kt` implements log.write with LogEntry entity
- Media actions (`media.searchAndSelect`, `media.download`, `media.searchLibrary`) are fully functional

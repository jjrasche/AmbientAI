package com.ambientai.testing

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegressionTestScenarios @Inject constructor() {

    fun getAllScenarios() = listOf(
        // TIER 1: Critical workflows
        playMusicInLibrary(),
        playMusicYoutubeFallback(),
        startTaskNew(),
        setTimerMinutes(),
        pauseMusicWhilePlaying(),
        logFoodEntry()
    )

    private fun playMusicInLibrary() = RegressionTestScenario(
        testId = "play_music_in_library",
        category = "music",
        description = "User says 'play taylor swift' and song exists in library",
        input = TestInput(
            utterance = "play taylor swift",
            elapsedMs = 3000
        ),
        expected = TestExpectations(
            workflowMatched = "play_music",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("music.play"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(minCount = 1)
                // Note: MediaHistory only created when song completes or is paused
            )
        )
    )

    private fun playMusicYoutubeFallback() = RegressionTestScenario(
        testId = "play_music_youtube_fallback",
        category = "music",
        description = "User says 'play believer' - not in library, should trigger YouTube fallback",
        input = TestInput(
            utterance = "play believer",
            elapsedMs = 3000
        ),
        expected = TestExpectations(
            workflowMatched = "play_music",
            workflowExecuted = true,
            workflowSuccess = true,  // YouTube fallback should succeed
            actionsExecuted = listOf("music.play", "media.searchAndSelect", "media.download", "music.play", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(minCount = 5)  // First play fails, then fallback chain
            )
        )
    )

    private fun playMusicNotFound() = RegressionTestScenario(
        testId = "play_music_not_found",
        category = "music",
        description = "User says 'play obscure artist' that doesn't exist in library",
        input = TestInput(
            utterance = "play NONEXISTENT_ARTIST_12345_ZZZZZ",  // Guaranteed not in library
            elapsedMs = 3000
        ),
        expected = TestExpectations(
            workflowMatched = "play_music",
            workflowExecuted = true,
            workflowSuccess = false,  // Will fail because not in library
            actionsExecuted = listOf("llm.prompt", "music.play"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(minCount = 2)
            )
        )
    )

    private fun startTaskNew() = RegressionTestScenario(
        testId = "start_task_new",
        category = "tasks",
        description = "Start a new task, should auto-pause previous if exists",
        input = TestInput(
            utterance = "start task regression test task",
            elapsedMs = 3500
        ),
        expected = TestExpectations(
            workflowMatched = "start_task",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("llm.prompt", "task.start", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 3),
                "Task" to DatabaseAssertion(
                    count = 1,
                    nameContains = "regression"
                )
            )
        )
    )

    private fun setTimerMinutes() = RegressionTestScenario(
        testId = "set_timer_minutes",
        category = "time",
        description = "Set timer for duration in minutes",
        input = TestInput(
            utterance = "set timer for 5 minutes",
            elapsedMs = 3000
        ),
        expected = TestExpectations(
            workflowMatched = "set_timer",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("llm.prompt", "timer.set", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 3)
            )
        )
    )

    private fun pauseMusicWhilePlaying() = RegressionTestScenario(
        testId = "pause_music_while_playing",
        category = "music",
        description = "Pause music when music is playing",
        input = TestInput(
            utterance = "pause",
            elapsedMs = 1200,
            preconditions = mapOf("music_player_state" to "playing")
        ),
        expected = TestExpectations(
            workflowMatched = "pause_music",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("music.pause", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2)
            )
        )
    )

    private fun logFoodEntry() = RegressionTestScenario(
        testId = "log_food_entry",
        category = "logging",
        description = "Log a food entry with details",
        input = TestInput(
            utterance = "log this food I ate chicken and rice",
            elapsedMs = 5000
        ),
        expected = TestExpectations(
            workflowMatched = "log_entry",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("llm.prompt", "log.write", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 3)
            )
        )
    )
}

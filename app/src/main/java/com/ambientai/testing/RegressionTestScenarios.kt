package com.ambientai.testing

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegressionTestScenarios @Inject constructor() {

    fun getAllScenarios() = listOf(
        // Music workflows
        playMusicInLibrary(),
        playMusicNotFound(),
        pauseMusicWhilePlaying(),
        resumeMusicWhilePaused(),
        nextTrack(),
        previousTrack(),
        nowPlaying(),
        stopMusic(),

        // Task workflows
        startTaskNew(),
        completeTask(),
        pauseTask(),
        resumeTask(),

        // Time workflows
        setTimerMinutes(),
        setTimerSeconds(),
        cancelTimer(),
        getCurrentTime(),

        // Logging workflows
        logFoodEntry(),
        logWorkout(),

        // Conversational workflows
        generalQuestion(),
        weatherQuery()
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
            preconditions = mapOf("music_playing" to "test_song.mp3")
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
            serviceStateChanges = mapOf("music_player_playing" to false),
            shouldNotExecute = listOf("music.play", "music.next", "music.previous")
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

    // ===== MUSIC WORKFLOWS =====

    private fun resumeMusicWhilePaused() = RegressionTestScenario(
        testId = "resume_music_while_paused",
        category = "music",
        description = "Resume music when music is paused",
        input = TestInput(
            utterance = "resume",
            elapsedMs = 1200,
            preconditions = mapOf("music_playing" to "test_song.mp3")  // Will be paused by test setup
        ),
        expected = TestExpectations(
            workflowMatched = "resume_music",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("music.resume", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2)
            ),
            serviceStateChanges = mapOf("music_player_playing" to true),
            shouldNotExecute = listOf("music.play", "music.pause")
        )
    )

    private fun nextTrack() = RegressionTestScenario(
        testId = "next_track",
        category = "music",
        description = "Skip to next track while music is playing",
        input = TestInput(
            utterance = "next",
            elapsedMs = 1000,
            preconditions = mapOf("music_playing" to "test_song.mp3")
        ),
        expected = TestExpectations(
            workflowMatched = "next_track",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("music.next"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 1)
            ),
            serviceStateChanges = mapOf("music_player_playing" to true),
            shouldNotExecute = listOf("music.previous", "music.pause")
        )
    )

    private fun previousTrack() = RegressionTestScenario(
        testId = "previous_track",
        category = "music",
        description = "Skip to previous track while music is playing",
        input = TestInput(
            utterance = "previous",
            elapsedMs = 1200,
            preconditions = mapOf("music_playing" to "test_song.mp3")
        ),
        expected = TestExpectations(
            workflowMatched = "previous_track",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("music.previous"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 1)
            ),
            serviceStateChanges = mapOf("music_player_playing" to true),
            shouldNotExecute = listOf("music.next", "music.pause")
        )
    )

    private fun nowPlaying() = RegressionTestScenario(
        testId = "now_playing",
        category = "music",
        description = "Query what song is currently playing",
        input = TestInput(
            utterance = "what's playing",
            elapsedMs = 2000,
            preconditions = mapOf("music_playing" to "test_song.mp3")
        ),
        expected = TestExpectations(
            workflowMatched = "now_playing",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("music.getNowPlaying", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2)
            ),
            shouldNotExecute = listOf("music.play", "music.pause", "music.next")
        )
    )

    private fun stopMusic() = RegressionTestScenario(
        testId = "stop_music",
        category = "music",
        description = "Stop music playback completely",
        input = TestInput(
            utterance = "stop",
            elapsedMs = 1000,
            preconditions = mapOf("music_playing" to "test_song.mp3")
        ),
        expected = TestExpectations(
            workflowMatched = "stop_music",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("music.stop", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2)
            ),
            serviceStateChanges = mapOf("music_player_playing" to false),
            shouldNotExecute = listOf("music.play", "music.pause")
        )
    )

    // ===== TASK WORKFLOWS =====

    private fun completeTask() = RegressionTestScenario(
        testId = "complete_task",
        category = "tasks",
        description = "Complete the currently active task",
        input = TestInput(
            utterance = "complete task",
            elapsedMs = 2000,
            preconditions = mapOf("active_task" to "Test active task")
        ),
        expected = TestExpectations(
            workflowMatched = "complete_task",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("task.complete", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2),
                "Task" to DatabaseAssertion(statusEquals = "COMPLETED")
            ),
            shouldNotExecute = listOf("task.start", "task.pause")
        )
    )

    private fun pauseTask() = RegressionTestScenario(
        testId = "pause_task",
        category = "tasks",
        description = "Pause the currently active task",
        input = TestInput(
            utterance = "pause task",
            elapsedMs = 2000,
            preconditions = mapOf("active_task" to "Test active task")
        ),
        expected = TestExpectations(
            workflowMatched = "pause_task",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("task.pause", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2),
                "Task" to DatabaseAssertion(statusEquals = "PAUSED")
            ),
            shouldNotExecute = listOf("task.start", "task.complete")
        )
    )

    private fun resumeTask() = RegressionTestScenario(
        testId = "resume_task",
        category = "tasks",
        description = "Resume a paused task",
        input = TestInput(
            utterance = "resume task",
            elapsedMs = 2000,
            preconditions = mapOf("paused_task" to "Test paused task")
        ),
        expected = TestExpectations(
            workflowMatched = "resume_task",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("task.resume", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2),
                "Task" to DatabaseAssertion(statusEquals = "ACTIVE")
            ),
            shouldNotExecute = listOf("task.start", "task.complete")
        )
    )

    // ===== TIME WORKFLOWS =====

    private fun setTimerSeconds() = RegressionTestScenario(
        testId = "set_timer_seconds",
        category = "time",
        description = "Set timer with seconds specified",
        input = TestInput(
            utterance = "set timer for 30 seconds",
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
            ),
            serviceStateChanges = mapOf("timer_active" to true),
            shouldNotExecute = listOf("timer.cancel")
        )
    )

    private fun cancelTimer() = RegressionTestScenario(
        testId = "cancel_timer",
        category = "time",
        description = "Cancel an active timer",
        input = TestInput(
            utterance = "cancel timer",
            elapsedMs = 2000,
            preconditions = mapOf("timer_running" to 300000L)  // 5 minutes
        ),
        expected = TestExpectations(
            workflowMatched = "cancel_timer",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("timer.cancel", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2)
            ),
            serviceStateChanges = mapOf("timer_active" to false),
            shouldNotExecute = listOf("timer.set")
        )
    )

    private fun getCurrentTime() = RegressionTestScenario(
        testId = "get_current_time",
        category = "time",
        description = "Ask for the current time",
        input = TestInput(
            utterance = "what time is it",
            elapsedMs = 2500
        ),
        expected = TestExpectations(
            workflowMatched = "get_time",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("time.get", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2)
            ),
            shouldNotExecute = listOf("timer.set", "timer.cancel")
        )
    )

    // ===== LOGGING WORKFLOWS =====

    private fun logWorkout() = RegressionTestScenario(
        testId = "log_workout",
        category = "logging",
        description = "Log a workout session",
        input = TestInput(
            utterance = "log workout ran 5 miles",
            elapsedMs = 4000
        ),
        expected = TestExpectations(
            workflowMatched = "log_entry",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("llm.prompt", "log.write", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 3)
            ),
            shouldNotExecute = listOf("task.start", "timer.set")
        )
    )

    // ===== CONVERSATIONAL WORKFLOWS =====

    private fun generalQuestion() = RegressionTestScenario(
        testId = "general_question",
        category = "conversational",
        description = "Ask a general question that doesn't match any specific workflow",
        input = TestInput(
            utterance = "what is the capital of france",
            elapsedMs = 4000
        ),
        expected = TestExpectations(
            workflowMatched = "conversational_default",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("llm.prompt", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2)
            ),
            shouldNotExecute = listOf("music.play", "task.start", "timer.set")
        )
    )

    private fun weatherQuery() = RegressionTestScenario(
        testId = "weather_query",
        category = "conversational",
        description = "Ask about weather (should trigger conversational default with LLM)",
        input = TestInput(
            utterance = "what's the weather like today",
            elapsedMs = 3500
        ),
        expected = TestExpectations(
            workflowMatched = "conversational_default",
            workflowExecuted = true,
            workflowSuccess = true,
            actionsExecuted = listOf("llm.prompt", "tts.speak"),
            databaseChanges = mapOf(
                "WorkflowExecution" to DatabaseAssertion(count = 1),
                "ActionExecution" to DatabaseAssertion(count = 2)
            ),
            shouldNotExecute = listOf("music.play", "task.start", "timer.set")
        )
    )
}

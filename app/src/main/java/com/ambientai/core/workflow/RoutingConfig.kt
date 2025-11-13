package com.ambientai.core.workflow

object RoutingConfig {
    // Tier timeouts (milliseconds of silence required)
    const val TIER1_TIMEOUT_MS = 300  // Instant commands (pause, stop)
    const val TIER2_TIMEOUT_MS = 500  // Quick commands (set timer 5 minutes)
    const val TIER3_TIMEOUT_MS = 800  // Complex commands (start task with parameters)
    const val TIER4_TIMEOUT_MS = 1200 // Conversational/fallback

    // Minimum elapsed time before attempting any routing
    const val MIN_ELAPSED_BEFORE_ROUTING_MS = 1500

    // Word count thresholds
    const val INSTANT_COMMAND_MAX_WORDS = 3
    const val QUICK_COMMAND_MAX_WORDS = 6
    const val COMPLEX_COMMAND_MAX_WORDS = 12
    const val CONVERSATIONAL_MIN_WORDS = 13

    // Confidence thresholds
    const val MIN_CONFIDENCE_INSTANT = 0.90f
    const val MIN_CONFIDENCE_QUICK = 0.75f
    const val MIN_CONFIDENCE_COMPLEX = 0.70f
    const val AMBIGUITY_THRESHOLD = 0.10f

    // Incompleteness detection
    val TRAILING_PREPOSITIONS = setOf("at", "in", "for", "by", "to", "from", "with", "on")
    val TRAILING_CONJUNCTIONS = setOf("and", "but", "or", "because", "so")
    val TRAILING_ARTICLES = setOf("a", "an", "the")
    val CANCELLATION_PHRASES = setOf("wait", "no", "cancel", "never mind", "actually")

    // Feature flags (enable gradually)
    var useAdaptiveTimeouts = false
    var useSemanticEndpointing = false
    var useUserProfiling = false
}

enum class CommandTier {
    INSTANT,     // 1-3 words, context-dependent, no parameters
    QUICK,       // 2-5 words, simple parameters
    COMPLEX,     // 4+ words, multiple parameters
    CONVERSATIONAL  // 7+ words or extended duration
}

enum class RoutingDecision {
    WAIT,
    EXECUTE,
    ROUTE_TO_LLM,
    ROUTE_TO_NARRATIVE
}

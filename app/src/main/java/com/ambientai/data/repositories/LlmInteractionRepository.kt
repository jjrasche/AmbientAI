package com.ambientai.data.repositories

import android.content.Context
import android.util.Log
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.LlmInteraction
import com.ambientai.data.entities.LlmInteraction_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Repository for CRUD operations on LlmInteraction entities.
 * Exposes Flow for reactive UI updates.
 */
class LlmInteractionRepository(context: Context) {

    private val box: Box<LlmInteraction> = AmbientAIApp.boxStore.boxFor()

    companion object {
        private const val TAG = "LlmInteractionRepository"
    }

    /**
     * Get all LLM interactions as a reactive Flow.
     * Automatically emits new data when database changes.
     */
    fun getAllInteractions(): Flow<List<LlmInteraction>> = callbackFlow {
        val query = box.query()
            .order(LlmInteraction_.timestamp, OrderFlags.DESCENDING)
            .build()

        val subscription = query.subscribe().observer { data ->
            Log.d(TAG, "Flow emitting ${data.size} interactions")
            trySend(data)
        }

        awaitClose {
            subscription.cancel()
            Log.d(TAG, "Flow collection cancelled")
        }
    }

    /**
     * Get recent N interactions as a reactive Flow.
     * Automatically emits new data when database changes.
     */
    fun getRecentInteractions(limit: Int): Flow<List<LlmInteraction>> = callbackFlow {
        val query = box.query()
            .order(LlmInteraction_.timestamp, OrderFlags.DESCENDING)
            .build()

        val subscription = query.subscribe().observer { data ->
            val limited = data.take(limit)
            Log.d(TAG, "Flow emitting ${limited.size} recent interactions (limit: $limit)")
            trySend(limited)
        }

        awaitClose {
            subscription.cancel()
            Log.d(TAG, "Recent interactions flow collection cancelled")
        }
    }

    fun save(interaction: LlmInteraction): LlmInteraction {
        box.put(interaction)
        Log.d(TAG, "Saved interaction ${interaction.id}")
        // No manual refresh needed - Flow auto-emits
        return interaction
    }

    fun getById(id: Long): LlmInteraction? {
        return box.get(id)
    }

    fun getMostRecent(): LlmInteraction? {
        return box.query()
            .order(LlmInteraction_.timestamp, OrderFlags.DESCENDING)
            .build()
            .findFirst()
    }

    fun getRecent(limit: Int): List<LlmInteraction> {
        return box.query()
            .order(LlmInteraction_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find(0, limit.toLong())
    }

    fun getGraded(): List<LlmInteraction> {
        return box.query()
            .notNull(LlmInteraction_.grade)
            .order(LlmInteraction_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun getUngraded(): List<LlmInteraction> {
        return box.query()
            .isNull(LlmInteraction_.grade)
            .order(LlmInteraction_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun updateGrade(id: Long, grade: Int): Boolean {
        val interaction = box.get(id) ?: run {
            Log.w(TAG, "Interaction $id not found for grade update")
            return false
        }

        interaction.grade = grade
        box.put(interaction)
        Log.d(TAG, "Updated interaction $id grade to $grade")
        // Flow handles UI update automatically
        return true
    }

    fun delete(id: Long): Boolean {
        val result = box.remove(id)
        Log.d(TAG, "Deleted interaction $id: $result")
        return result
    }

    fun count(): Long {
        return box.count()
    }
}
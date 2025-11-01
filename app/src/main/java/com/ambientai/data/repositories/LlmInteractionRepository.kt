package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.LlmInteraction
import com.ambientai.data.entities.LlmInteraction_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags

/**
 * Repository for CRUD operations on LlmInteraction entities.
 */
class LlmInteractionRepository(context: Context) {

    private val box: Box<LlmInteraction> = AmbientAIApp.boxStore.boxFor()

    fun save(interaction: LlmInteraction): LlmInteraction {
        box.put(interaction)
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
        val interaction = box.get(id) ?: return false
        interaction.grade = grade
        box.put(interaction)
        return true
    }

    fun delete(id: Long): Boolean {
        return box.remove(id)
    }

    fun count(): Long {
        return box.count()
    }

    fun close() {
        box.store.close()
    }
}
package com.ambientai.data.repositories

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.LlmInteraction
import com.ambientai.data.entities.LlmInteraction_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags

/**
 * Repository for CRUD operations on LlmInteraction entities.
 * Exposes LiveData for reactive UI updates.
 */
class LlmInteractionRepository(context: Context) {

    private val box: Box<LlmInteraction> = AmbientAIApp.boxStore.boxFor()

    // LiveData for reactive upddates
    private val _interactions = MutableLiveData<List<LlmInteraction>>()
    val interactions: LiveData<List<LlmInteraction>> = _interactions

    private val _recentInteractions = MutableLiveData<List<LlmInteraction>>()
    val recentInteractions: LiveData<List<LlmInteraction>> = _recentInteractions

    init {
        // Initialize with current data
        refreshLiveData()
    }

    private fun refreshLiveData() {
        _interactions.postValue(box.all)
        _recentInteractions.postValue(getRecent(20))
    }

    fun save(interaction: LlmInteraction): LlmInteraction {
        box.put(interaction)
        refreshLiveData()
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
        refreshLiveData()
        return true
    }

    fun delete(id: Long): Boolean {
        val result = box.remove(id)
        refreshLiveData()
        return result
    }

    fun count(): Long {
        return box.count()
    }
}
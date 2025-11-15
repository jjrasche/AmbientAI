package com.ambientai.core.ui

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class UiService @Inject constructor(@ApplicationContext private val context: Context) {
    private val _modalState = MutableStateFlow<ModalData?>(null)
    val modalState: StateFlow<ModalData?> = _modalState
    private val _choiceState = MutableStateFlow<ChoiceData?>(null)
    val choiceState: StateFlow<ChoiceData?> = _choiceState
    private var choiceResponse: CompletableDeferred<Int>? = null

    companion object { private const val TAG = "UiService"; private const val DEFAULT_TIMEOUT_MS = 300000L }
    data class ModalData(val title: String, val message: String)
    data class ChoiceData(val title: String, val options: List<String>, val message: String? = null)
    fun execute(actionName: String, input: JSONObject): JSONObject? = when (actionName) { "ui.showModal" -> showModal(input); "ui.dismissModal" -> dismissModal(input); else -> errorResult("Unknown action: $actionName") }
    suspend fun executeAsync(actionName: String, input: JSONObject) = when (actionName) { "ui.awaitChoice" -> awaitChoice(input); else -> errorResult("Unknown action: $actionName") }
    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }
    private fun showModal(input: JSONObject): JSONObject {
        val title = input.optString("title", "Information")
        val message = input.optString("message", "")
        if (message.isBlank()) return errorResult("Message cannot be empty")
        Log.d(TAG, "📋 SHOWING MODAL: $title")
        _modalState.value = ModalData(title, message)
        return successResult(mapOf("message" to "Modal displayed"))
    }
    private fun dismissModal(input: JSONObject): JSONObject {
        Log.d(TAG, "✖ DISMISSING MODAL")
        _modalState.value = null
        return successResult(mapOf("message" to "Modal dismissed"))
    }
    private suspend fun awaitChoice(input: JSONObject): JSONObject {
        val title = input.optString("title", "Select Option")
        val message = input.optString("message", null)
        val optionsArray = input.optJSONArray("options") ?: return errorResult("options array required")
        val options = (0 until optionsArray.length()).map { optionsArray.getString(it) }
        if (options.isEmpty()) return errorResult("At least one option required")
        val timeoutMs = input.optLong("timeout_ms", DEFAULT_TIMEOUT_MS)
        Log.d(TAG, "⏸ AWAITING USER CHOICE: $title (${options.size} options, timeout=${timeoutMs}ms)")
        choiceResponse = CompletableDeferred()
        _choiceState.value = ChoiceData(title, options, message)
        val selectedIndex = withTimeoutOrNull(timeoutMs) { choiceResponse?.await() }
        _choiceState.value = null
        choiceResponse = null
        return if (selectedIndex != null && selectedIndex in options.indices) { Log.d(TAG, "✓ USER SELECTED: ${options[selectedIndex]} (index=$selectedIndex)"); successResult(mapOf("selected_index" to selectedIndex, "selected_option" to options[selectedIndex])) } else { Log.d(TAG, "✖ CHOICE TIMEOUT OR CANCELLED"); errorResult("Choice timeout or cancelled") }
    }
    fun submitChoice(index: Int) {
        Log.d(TAG, "📥 CHOICE SUBMITTED: index=$index")
        choiceResponse?.complete(index)
    }
}

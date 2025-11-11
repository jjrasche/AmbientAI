package com.ambientai.core.ui

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiService @Inject constructor(@ApplicationContext private val context: Context) {
    private val _modalState = MutableStateFlow<ModalData?>(null)
    val modalState: StateFlow<ModalData?> = _modalState

    companion object { private const val TAG = "UiService" }
    data class ModalData(val title: String, val message: String)
    fun execute(actionName: String, input: JSONObject) = when (actionName) { "ui.showModal" -> showModal(input); "ui.dismissModal" -> dismissModal(input); else -> errorResult("Unknown action: $actionName") }
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
}

package com.ambientai.core.media

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder as MPTextEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextEmbedder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TextEmbedder"
        private const val MODEL_NAME = "all_minilm_l6_v2.tflite"
        const val CURRENT_MODEL = "Universal Sentence Encoder"
        const val EMBEDDING_DIMENSIONS = 100
    }
    private var embedder: MPTextEmbedder? = null
    private var isInitialized = false
    suspend fun initialize(): Boolean = runCatching { if (isInitialized) return true; val baseOptions = BaseOptions.builder().setModelAssetPath(MODEL_NAME).build(); val options = MPTextEmbedder.TextEmbedderOptions.builder().setBaseOptions(baseOptions).build(); embedder = MPTextEmbedder.createFromOptions(context, options); isInitialized = true; Log.d(TAG, "TextEmbedder initialized with model: $MODEL_NAME ($CURRENT_MODEL, ${EMBEDDING_DIMENSIONS}D)"); true }.getOrElse { e -> Log.e(TAG, "Failed to initialize TextEmbedder", e); false }
    fun embed(text: String): FloatArray? = runCatching { if (!isInitialized || embedder == null) { Log.w(TAG, "Embedder not initialized"); return null }; val result = embedder!!.embed(text); result.embeddingResult().embeddings().firstOrNull()?.floatEmbedding()?.also { Log.d(TAG, "Generated embedding with ${it.size} dimensions") } }.getOrElse { e -> Log.e(TAG, "Failed to embed text", e); null }
    fun getModelName() = CURRENT_MODEL
    fun getDimensions() = EMBEDDING_DIMENSIONS
    fun cleanup() { embedder?.close(); embedder = null; isInitialized = false }
}

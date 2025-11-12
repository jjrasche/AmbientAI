package com.ambientai.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DebugCommandReceiver : BroadcastReceiver() {
    @Inject lateinit var executor: DebugCommandExecutor

    companion object {
        private const val TAG = "DebugCommand"
        const val ACTION_DEBUG_COMMAND = "com.ambientai.DEBUG_COMMAND"
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG_COMMAND) return
        val cmd = intent.getStringExtra("cmd") ?: return
        Log.d(TAG, "📡 RECEIVED COMMAND: $cmd")
        val result = try {
            executor.execute(cmd)
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
        Log.d(TAG, "📡 RESULT: $result")
        resultData = result
    }
}

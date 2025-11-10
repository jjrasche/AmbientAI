package com.ambientai

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.objectbox.BoxStore
import javax.inject.Inject

@HiltAndroidApp
class AmbientAIApp : Application() {
    @Inject
    lateinit var boxStore: BoxStore

    override fun onTerminate() {
        super.onTerminate()
        if (::boxStore.isInitialized) {
            boxStore.close()
        }
    }
}

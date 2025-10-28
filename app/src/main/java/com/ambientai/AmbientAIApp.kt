package com.ambientai

import android.app.Application
import com.ambientai.data.entities.MyObjectBox
import io.objectbox.BoxStore

class AmbientAIApp : Application() {
    companion object {
        lateinit var boxStore: BoxStore
            private set
    }

    override fun onCreate() {
        super.onCreate()
        boxStore = MyObjectBox.builder()
            .androidContext(applicationContext)
            .build()
    }

    override fun onTerminate() {
        boxStore.close()
        super.onTerminate()
    }
}
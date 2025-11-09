package com.ambientai

import android.app.Application
import com.ambientai.data.WorkflowSeeder
import com.ambientai.data.entities.MyObjectBox
import dagger.hilt.android.HiltAndroidApp
import io.objectbox.BoxStore

@HiltAndroidApp
class AmbientAIApp : Application() {
    companion object {
        lateinit var boxStore: BoxStore
            private set
    }

    override fun onCreate() = super.onCreate().also { boxStore = MyObjectBox.builder().androidContext(applicationContext).build() }
    override fun onTerminate() = boxStore.close().also { super.onTerminate() }
}

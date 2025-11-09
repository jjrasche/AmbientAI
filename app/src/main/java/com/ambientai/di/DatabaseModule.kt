package com.ambientai.di

import android.content.Context
import com.ambientai.data.entities.MyObjectBox
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.objectbox.BoxStore
import javax.inject.Singleton

/**
 * Hilt module providing ObjectBox database instance.
 *
 * @InstallIn(SingletonComponent::class) means this module lives as long as the app.
 * @Singleton ensures only one instance of BoxStore is created.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideBoxStore(@ApplicationContext context: Context): BoxStore =
        MyObjectBox.builder().androidContext(context).build()
}

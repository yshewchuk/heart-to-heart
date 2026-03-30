package com.yurishewchuk.hearttoheart.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single app-wide Preferences DataStore. Must not be duplicated via another
 * [preferencesDataStore] delegate with the same name — that triggers a runtime crash.
 */
internal val Context.heartToHeartPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "heart_to_heart_prefs"
)

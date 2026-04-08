package com.mixts.android.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_PASSWORD = stringPreferencesKey("password")
    }

    fun getPassword(): String {
        return runBlocking {
            context.dataStore.data.first()[KEY_PASSWORD] ?: ""
        }
    }

    fun setPassword(password: String) {
        runBlocking {
            context.dataStore.edit { prefs ->
                prefs[KEY_PASSWORD] = password
            }
        }
    }
}

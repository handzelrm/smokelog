package com.smokelog.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Credentials (Traeger login, Anthropic API key) live in encrypted prefs and never
 * leave the device except to the service they belong to.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "smokelog_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    data class Snapshot(
        val traegerEmail: String,
        val traegerPassword: String,
        val anthropicKey: String,
        val aiModel: String,
        val readingIntervalSec: Int,
        val aiProvider: String,
    ) {
        val hasTraeger get() = traegerEmail.isNotBlank() && traegerPassword.isNotBlank()
        val usesClaudeApp get() = aiProvider == PROVIDER_CLAUDE_APP
        val hasAi get() = usesClaudeApp || anthropicKey.isNotBlank()
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private fun read() = Snapshot(
        traegerEmail = prefs.getString(K_TR_EMAIL, "") ?: "",
        traegerPassword = prefs.getString(K_TR_PASS, "") ?: "",
        anthropicKey = prefs.getString(K_AI_KEY, "") ?: "",
        aiModel = prefs.getString(K_AI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
        readingIntervalSec = prefs.getInt(K_INTERVAL, 60),
        aiProvider = prefs.getString(K_PROVIDER, PROVIDER_CLAUDE_APP) ?: PROVIDER_CLAUDE_APP,
    )

    fun update(s: Snapshot) {
        prefs.edit()
            .putString(K_TR_EMAIL, s.traegerEmail.trim())
            .putString(K_TR_PASS, s.traegerPassword)
            .putString(K_AI_KEY, s.anthropicKey.trim())
            .putString(K_AI_MODEL, s.aiModel.trim().ifBlank { DEFAULT_MODEL })
            .putInt(K_INTERVAL, s.readingIntervalSec.coerceIn(15, 900))
            .putString(K_PROVIDER, s.aiProvider)
            .apply()
        _state.value = read()
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val PROVIDER_CLAUDE_APP = "claude_app"
        const val PROVIDER_API = "api"
        private const val K_PROVIDER = "ai_provider"
        private const val K_TR_EMAIL = "traeger_email"
        private const val K_TR_PASS = "traeger_password"
        private const val K_AI_KEY = "anthropic_key"
        private const val K_AI_MODEL = "ai_model"
        private const val K_INTERVAL = "reading_interval"
    }
}

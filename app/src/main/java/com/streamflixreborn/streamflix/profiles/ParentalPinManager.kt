package com.streamflixreborn.streamflix.profiles

import android.content.Context
import androidx.compose.runtime.Immutable
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

@Immutable
data class ParentalProtectionConfig(
    val masterPinHash: String? = null,
    val protectAddonsAndSettings: Boolean = false,
) {
    val hasMasterPin: Boolean
        get() = !masterPinHash.isNullOrBlank()

    val isSettingsProtectionActive: Boolean
        get() = protectAddonsAndSettings && hasMasterPin
}

/**
 * Manages 4-digit numeric PIN hashing, profile lock verification, and global parental protection
 * for the Add-ons QR/URL installer and advanced profile settings.
 */
object ParentalPinManager {

    private const val PREFS_NAME = "streamflix_tv_parental_pin"
    private const val KEY_MASTER_PIN_HASH = "master_pin_hash"
    private const val KEY_PROTECT_ADDONS_AND_SETTINGS = "protect_addons_and_settings"
    private const val SALT = "streamflix_tv_pin_v1:"

    private val _config = MutableStateFlow(ParentalProtectionConfig())
    val config: StateFlow<ParentalProtectionConfig> = _config.asStateFlow()

    fun ensureLoaded(context: Context = StreamFlixApp.instance): ParentalProtectionConfig {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val loaded = ParentalProtectionConfig(
            masterPinHash = sp.getString(KEY_MASTER_PIN_HASH, null)?.takeIf { it.isNotBlank() },
            protectAddonsAndSettings = sp.getBoolean(KEY_PROTECT_ADDONS_AND_SETTINGS, false),
        )
        _config.value = loaded
        return loaded
    }

    fun isValidRawPin(rawPin: String): Boolean {
        return rawPin.length == 4 && rawPin.all { it.isDigit() }
    }

    fun hashPin(rawPin: String): String {
        require(isValidRawPin(rawPin)) { "El PIN debe contener exactamente 4 dígitos numéricos." }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((SALT + rawPin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPinHash(rawPin: String, expectedHash: String?): Boolean {
        if (expectedHash.isNullOrBlank() || !isValidRawPin(rawPin)) return false
        return hashPin(rawPin) == expectedHash
    }

    fun verifyProfilePin(rawPin: String, profile: TvUserProfileEntity): Boolean {
        if (!profile.hasPinLock) return true
        // Allow unlocking with either the profile's own PIN or the master parental PIN if configured
        val master = _config.value.masterPinHash
        return verifyPinHash(rawPin, profile.pinHash) ||
            (!master.isNullOrBlank() && verifyPinHash(rawPin, master))
    }

    fun verifyMasterOrFallbackPin(
        rawPin: String,
        activeProfile: TvUserProfileEntity? = null,
    ): Boolean {
        val current = ensureLoaded()
        if (current.hasMasterPin) {
            return verifyPinHash(rawPin, current.masterPinHash)
        }
        if (activeProfile?.hasPinLock == true) {
            return verifyPinHash(rawPin, activeProfile.pinHash)
        }
        return false
    }

    fun setMasterPin(
        rawPin: String,
        protectAddonsAndSettings: Boolean = true,
        context: Context = StreamFlixApp.instance,
    ) {
        val hashed = hashPin(rawPin)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MASTER_PIN_HASH, hashed)
            .putBoolean(KEY_PROTECT_ADDONS_AND_SETTINGS, protectAddonsAndSettings)
            .apply()
        _config.value = ParentalProtectionConfig(
            masterPinHash = hashed,
            protectAddonsAndSettings = protectAddonsAndSettings,
        )
    }

    fun setProtectAddonsAndSettings(
        enabled: Boolean,
        context: Context = StreamFlixApp.instance,
    ) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_PROTECT_ADDONS_AND_SETTINGS, enabled).apply()
        _config.value = _config.value.copy(protectAddonsAndSettings = enabled)
    }

    fun clearMasterPin(context: Context = StreamFlixApp.instance) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MASTER_PIN_HASH)
            .putBoolean(KEY_PROTECT_ADDONS_AND_SETTINGS, false)
            .apply()
        _config.value = ParentalProtectionConfig()
    }
}

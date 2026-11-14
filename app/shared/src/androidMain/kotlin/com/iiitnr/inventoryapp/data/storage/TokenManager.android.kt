package com.iiitnr.inventoryapp.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_preferences")

class AndroidTokenManager(
    private val context: Context,
) : TokenManager {
    companion object {
        private val TOKEN_KEY = stringPreferencesKey("auth_token")
        private const val KEY_ALIAS = "inventory_auth_token_key"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
    }

    private val _token = MutableStateFlow<String?>(null)
    private val loadMutex = Mutex()

    @Volatile
    private var loaded = false

    @Volatile
    private var cachedKey: SecretKey? = null

    private val keyLock = Any()

    override val token: Flow<String?> =
        flow {
            ensureLoaded()
            emitAll(_token)
        }.flowOn(Dispatchers.IO)

    override suspend fun saveToken(token: String) {
        val encrypted =
            withContext(Dispatchers.IO) {
                encrypt(token) ?: throw IllegalStateException("Could not encrypt auth token")
            }

        context.dataStore.edit { preferences ->
            preferences[TOKEN_KEY] = encrypted
        }

        _token.value = token
        loaded = true
    }

    override suspend fun clearToken() {
        context.dataStore.edit { preferences ->
            preferences.remove(TOKEN_KEY)
        }

        _token.value = null
        loaded = true
    }

    private suspend fun ensureLoaded() {
        if (loaded) return

        loadMutex.withLock {
            if (loaded) return

            val stored = context.dataStore.data.first()[TOKEN_KEY]
            _token.value = stored?.let(::decrypt)
            loaded = true
        }
    }

    private fun secretKey(): SecretKey =
        cachedKey ?: synchronized(keyLock) {
            cachedKey ?: loadOrGenerateKey().also { cachedKey = it }
        }

    private fun loadOrGenerateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        return existing ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): String? =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        }.getOrNull()

    private fun decrypt(stored: String): String? =
        runCatching {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            require(payload.size > IV_BYTES) { "ciphertext too short" }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, IV_BYTES),
            )
            String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), Charsets.UTF_8)
        }.getOrNull()
}

actual fun createTokenManager(): TokenManager =
    throw NotImplementedError("TokenManager must be created with Context on Android")

fun createTokenManager(context: Context): TokenManager = AndroidTokenManager(context)

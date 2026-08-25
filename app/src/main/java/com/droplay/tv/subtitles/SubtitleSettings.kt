package com.droplay.tv.subtitles

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SubtitleAppearance(
    val preferredLanguage: String = "pt-br",
    val delayMs: Long = 0,
    val textScale: Float = 1f,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: Boolean = true,
    val outline: Boolean = true,
)

enum class SubtitleColor { WHITE, YELLOW, CYAN }

class SubtitleSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("droplay_subtitles", Context.MODE_PRIVATE)

    fun configuration(): OpenSubtitlesConfiguration {
        val json = decrypt(prefs.getString("credentials", null)) ?: return OpenSubtitlesConfiguration()
        val value = runCatching { JSONObject(json) }.getOrElse { return OpenSubtitlesConfiguration() }
        return OpenSubtitlesConfiguration(
            apiKey = value.optString("apiKey"), userAgent = value.optString("userAgent", "DROPLAY v1.2.18"),
            username = value.optString("username"), token = value.optString("token"),
            baseUrl = value.optString("baseUrl", OpenSubtitlesConfiguration.DEFAULT_API_URL),
        )
    }

    fun saveConfiguration(apiKey: String, userAgent: String, username: String) {
        val old = configuration()
        val sameAccount = old.apiKey == apiKey.trim() && old.username == username.trim()
        save(old.copy(apiKey = apiKey.trim(), userAgent = userAgent.trim(), username = username.trim(),
            token = old.token.takeIf { sameAccount }.orEmpty(),
            baseUrl = old.baseUrl.takeIf { sameAccount } ?: OpenSubtitlesConfiguration.DEFAULT_API_URL))
    }

    fun saveSession(token: String, baseUrl: String) = save(configuration().copy(token = token, baseUrl = normalizeOpenSubtitlesBase(baseUrl)))
    fun clearSession() = save(configuration().copy(token = "", baseUrl = OpenSubtitlesConfiguration.DEFAULT_API_URL))

    fun appearance(): SubtitleAppearance = SubtitleAppearance(
        preferredLanguage = prefs.getString("preferred_language", "pt-br").orEmpty(),
        delayMs = prefs.getLong("delay_ms", 0), textScale = prefs.getFloat("text_scale", 1f),
        color = runCatching { SubtitleColor.valueOf(prefs.getString("color", SubtitleColor.WHITE.name).orEmpty()) }.getOrDefault(SubtitleColor.WHITE),
        background = prefs.getBoolean("background", true), outline = prefs.getBoolean("outline", true),
    )

    fun saveAppearance(value: SubtitleAppearance) {
        prefs.edit().putString("preferred_language", SubtitleRanking.normalizeLanguage(value.preferredLanguage))
            .putLong("delay_ms", value.delayMs.coerceIn(-30_000, 30_000)).putFloat("text_scale", value.textScale.coerceIn(.7f, 1.6f))
            .putString("color", value.color.name).putBoolean("background", value.background)
            .putBoolean("outline", value.outline).apply()
    }

    private fun save(value: OpenSubtitlesConfiguration) {
        val json = JSONObject().put("apiKey", value.apiKey).put("userAgent", value.userAgent)
            .put("username", value.username).put("token", value.token).put("baseUrl", value.baseUrl).toString()
        prefs.edit().putString("credentials", encrypt(json)).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun encrypt(text: String): String = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.getOrDefault("")

    private fun decrypt(value: String?): String? = value?.takeIf(String::isNotBlank)?.let { encoded -> runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull() }

    private companion object { const val KEY_ALIAS = "droplay_opensubtitles_v1" }
}

internal fun normalizeOpenSubtitlesBase(value: String): String {
    val raw = value.trim().trimEnd('/')
    if (raw.isBlank()) return OpenSubtitlesConfiguration.DEFAULT_API_URL
    val base = if (raw.startsWith("https://", true) || raw.startsWith("http://", true)) raw else "https://$raw"
    val uri = runCatching { java.net.URI(base) }.getOrNull() ?: return OpenSubtitlesConfiguration.DEFAULT_API_URL
    if (!uri.scheme.equals("https", true) || uri.host !in setOf("api.opensubtitles.com", "vip-api.opensubtitles.com")) {
        return OpenSubtitlesConfiguration.DEFAULT_API_URL
    }
    val origin = "https://${uri.host}"
    return "$origin/api/v1"
}

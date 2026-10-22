package com.jarvis.agent.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.agent.ai.Provider
import com.jarvis.agent.ai.ProviderConfig

/** تخزين مفاتيح الـ API مشفّرة محلياً على الجهاز فقط (لا تُرسل لأي خادم خارجي). */
class KeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jarvis_secure_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveKey(provider: Provider, apiKey: String) {
        prefs.edit().putString("key_${provider.name}", apiKey).apply()
    }

    fun getKey(provider: Provider): String? =
        prefs.getString("key_${provider.name}", null)

    fun saveModel(provider: Provider, model: String) {
        prefs.edit().putString("model_${provider.name}", model).apply()
    }

    fun getModel(provider: Provider): String =
        prefs.getString("model_${provider.name}", null)
            ?: ProviderConfig.defaultModel(provider)

    fun setActiveProvider(provider: Provider) {
        prefs.edit().putString("active_provider", provider.name).apply()
    }

    fun getActiveProvider(): Provider? =
        prefs.getString("active_provider", null)?.let { runCatching { Provider.valueOf(it) }.getOrNull() }

    /** يبني إعداد المزوّد النشط إن توفّر مفتاحه. */
    fun activeConfig(): ProviderConfig? {
        val p = getActiveProvider() ?: return null
        val key = getKey(p)?.takeIf { it.isNotBlank() } ?: return null
        return ProviderConfig(p, key, getModel(p))
    }

    fun configuredProviders(): List<Provider> =
        Provider.entries.filter { !getKey(it).isNullOrBlank() }
}

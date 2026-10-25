package com.zhideer.app.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.zhideer.app.domain.model.AiConfig

/**
 * AI 配置存储契约（T17/#18）：
 * - API Key 保存于本机安全存储，明文只在 [load] 中提供给出例层用于发请求；
 * - 模型标识与 Base URL 为非敏感配置，保存于普通 SharedPreferences。
 */
interface AiConfigStore {
    fun load(): AiConfig?
    fun save(config: AiConfig)
    fun clear()
}

/**
 * 生产实现：
 * - Key 存 EncryptedSharedPreferences（主密钥 AndroidKeystore，AES256_GCM）；
 * - 模型标识与 Base URL 存普通 SharedPreferences。
 * 两部分合并为一个 [AiConfig]；任何一部分缺失视为未完整配置。
 */
class EncryptedAiConfigStore(context: Context) : AiConfigStore {

    private val securePrefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plainPrefs: SharedPreferences =
        context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

    override fun load(): AiConfig? {
        val key = securePrefs.getString(PREF_API_KEY, null) ?: return null
        val model = plainPrefs.getString(PREF_MODEL, null) ?: return null
        val baseUrl = plainPrefs.getString(PREF_BASE_URL, null) ?: return null
        if (key.isBlank() || model.isBlank() || baseUrl.isBlank()) return null
        return AiConfig(apiKey = key, model = model, baseUrl = baseUrl)
    }

    override fun save(config: AiConfig) {
        securePrefs.edit()
            .putString(PREF_API_KEY, config.apiKey)
            .apply()
        plainPrefs.edit()
            .putString(PREF_MODEL, config.model)
            .putString(PREF_BASE_URL, config.baseUrl)
            .apply()
    }

    override fun clear() {
        securePrefs.edit().clear().apply()
        plainPrefs.edit().clear().apply()
    }

    private companion object {
        const val SECURE_FILE = "ai_secure_config"
        const val PLAIN_FILE = "ai_chat_config"
        const val PREF_API_KEY = "api_key"
        const val PREF_MODEL = "model"
        const val PREF_BASE_URL = "base_url"
    }
}

/** 内存实现：供单元测试使用（Robolectric 不支持 AndroidKeyStore，无法实例化加密存储）。 */
class InMemoryAiConfigStore : AiConfigStore {
    private var config: AiConfig? = null

    override fun load(): AiConfig? = config

    override fun save(config: AiConfig) {
        this.config = config
    }

    override fun clear() {
        config = null
    }
}

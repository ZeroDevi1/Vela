package com.vela.data.navidrome

import android.content.Context
import com.vela.data.security.SecureSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.UUID

/** Navidrome 账号和凭据写入现有加密存储；旧版单账号记录可直接读取。 */
class NavidromeStore private constructor(context: Context) {
    private val secure = SecureSessionStore(context)
    private val lock = Mutex()

    @Serializable
    private class Account(
        val accountKey: String,
        val address: String,
        val username: String,
        val secret: String,
        val useApiKey: Boolean,
        val note: String = ""
    ) {
        fun session(): NavidromeSession {
            val url = NavidromeClient.address(address)
            return NavidromeSession(accountKey, address, username, note, NavidromeClient(url, username, secret, useApiKey))
        }
    }

    private var accounts = runCatching {
        secure.getToken(STORAGE_KEY)?.let { saved ->
            if (Json.parseToJsonElement(saved) is JsonObject) listOf(Json.decodeFromString<Account>(saved))
            else Json.decodeFromString<List<Account>>(saved)
        }.orEmpty()
    }.getOrDefault(emptyList())
    private val current = MutableStateFlow(accounts.map(Account::session))
    val sessions = current.asStateFlow()

    fun contains(session: NavidromeSession): Boolean = session in current.value

    suspend fun connect(address: String, username: String, secret: String, useApiKey: Boolean, note: String): NavidromeSession = withContext(Dispatchers.IO) {
        val url = NavidromeClient.address(address)
        val credential = if (useApiKey) secret.trim() else secret
        val user = username.trim()
        if (credential.isEmpty() || (!useApiKey && user.isBlank())) throw IOException("请填写 Navidrome 登录凭据")
        val resolved = NavidromeClient(url, user, credential, useApiKey).login()
        lock.withLock {
            val existing = accounts.firstOrNull { it.address == url.toString() && it.username == resolved }
            val account = Account(existing?.accountKey ?: "navidrome:${UUID.randomUUID()}", url.toString(),
                resolved, credential, useApiKey, note.trim().ifEmpty { existing?.note.orEmpty() })
            val verified = account.session()
            save(accounts.filterNot { it.accountKey == account.accountKey } + account,
                current.value.filterNot { it.accountKey == account.accountKey } + verified)
            verified
        }
    }

    suspend fun disconnect(accountKey: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            save(accounts.filterNot { it.accountKey == accountKey }, current.value.filterNot { it.accountKey == accountKey })
        }
    }

    private fun save(updated: List<Account>, sessions: List<NavidromeSession>) {
        if (updated.isEmpty()) secure.removeToken(STORAGE_KEY)
        else secure.putToken(STORAGE_KEY, Json.encodeToString(updated))
        accounts = updated
        current.value = sessions
    }

    companion object {
        private const val STORAGE_KEY = "music:navidrome"
        @Volatile private var instance: NavidromeStore? = null

        fun getInstance(context: Context): NavidromeStore =
            instance ?: synchronized(this) {
                instance ?: NavidromeStore(context.applicationContext).also { instance = it }
            }
    }
}

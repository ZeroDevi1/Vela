package com.vela.app.ui.components.privacy

import android.app.Activity
import android.app.KeyguardManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vela.app.ui.screens.dashboard.home.CachedData
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.data.repository.MediaRepositoryProvider
import com.vela.shared.R
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 本进程内已通过系统锁屏验证的私密服务器。
 * 只放内存，不落盘；进程退出后自然清空，下次进入应用需要重新验证。
 */
internal object PrivateServerSession {
    /** 已解锁的服务器 id。进程被系统回收或用户划掉应用后失效。 */
    private val unlockedServerIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * 当前进程里该私密服务器是否已经解锁。
     *
     * @param serverId 已保存服务器 id。
     * @return 本进程内验证成功过则为 true。
     */
    fun isUnlocked(serverId: String): Boolean {
        return serverId.isNotBlank() && serverId in unlockedServerIds
    }

    /**
     * 记录本进程内的解锁，直到应用进程结束。
     *
     * @param serverId 刚通过系统锁屏验证的服务器 id。
     */
    fun unlock(serverId: String) {
        if (serverId.isNotBlank()) unlockedServerIds.add(serverId)
    }
}

/** 只接受系统返回的验证成功；取消、未设置锁屏或无法启动验证都保持关闭。 */
@Composable
internal fun rememberDeviceCredentialConfirmation(onConfirmed: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val confirmed = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK) confirmed?.invoke()
    }
    return {
        if (pending == null) {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = if (keyguard?.isDeviceSecure == true) {
                keyguard.createConfirmDeviceCredentialIntent(
                    context.getString(R.string.server_private_title),
                    context.getString(R.string.server_private_verify)
                )
            } else null
            if (intent == null) {
                Toast.makeText(context, R.string.server_private_no_lock, Toast.LENGTH_LONG).show()
            } else {
                try {
                    pending = onConfirmed
                    launcher.launch(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    pending = null
                    Toast.makeText(context, R.string.server_private_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/**
 * 私密会话解锁前不创建导航内容，避免冷启动恢复页面或展示缓存。
 * 解锁只记在进程内存：应用未退出时再次进入同一服务器不再验证，退出重进后需要重新验证。
 */
@Composable
internal fun ServerPrivacyGate(activity: Activity, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val repository = remember { AuthRepositoryProvider.getInstance(context) }
    val sessions = remember(repository) { repository.observeActiveSession() }
    val snapshot by sessions.collectAsState(initial = null)
    val active = snapshot?.let { session -> session.savedServers.firstOrNull { it.id == session.activeServerId } }
    var unlocked by remember(active?.id, active?.isPrivate) {
        mutableStateOf(
            active?.let { server -> server.isPrivate && PrivateServerSession.isUnlocked(server.id) } == true
        )
    }
    var unlocking by remember(active?.id) { mutableStateOf(false) }
    var error by remember(active?.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mediaRepository = remember { MediaRepositoryProvider.getInstance(context) }
    val confirm = rememberDeviceCredentialConfirmation {
        val serverId = active?.id.orEmpty()
        scope.launch {
            unlocking = true
            try {
                mediaRepository.clearPersistedHomeSnapshot()
                CachedData.clearAllCache()
                PrivateServerSession.unlock(serverId)
                unlocked = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: context.getString(R.string.server_private_unavailable)
            } finally {
                unlocking = false
            }
        }
    }
    DisposableEffect(active?.isPrivate) {
        if (active?.isPrivate == true) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    if (snapshot == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (active?.isPrivate == true && !unlocked) {
        BackHandler { activity.finish() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.server_private_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.server_private_locked))
            Spacer(Modifier.height(20.dp))
            Button(onClick = confirm, enabled = !unlocking) { Text(stringResource(R.string.server_private_unlock)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            snapshot?.savedServers.orEmpty().filterNot { it.isPrivate }.forEach { server ->
                TextButton(enabled = !unlocking, onClick = {
                    scope.launch {
                        unlocking = true
                        try {
                            mediaRepository.clearPersistedHomeSnapshot()
                            CachedData.clearAllCache()
                            repository.switchServer(server.id).onFailure { error = it.message }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: context.getString(R.string.server_private_unavailable)
                        } finally {
                            unlocking = false
                        }
                    }
                }) { Text(stringResource(R.string.server_private_use_public, server.displayName())) }
            }
            TextButton(onClick = { activity.finish() }) { Text(stringResource(R.string.cancel)) }
        }
    } else {
        content()
    }
}

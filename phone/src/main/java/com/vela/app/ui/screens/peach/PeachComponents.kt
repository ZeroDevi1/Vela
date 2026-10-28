package com.vela.app.ui.screens.peach

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vela.data.peach.PeachApiException
import com.vela.data.peach.PeachRepository
import com.vela.shared.R
import com.vela.shared.ui.components.common.bottomContentPadding
import com.vela.shared.ui.components.common.excludeBottom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 一次加载的结果：加载中、成功或失败。失败保留原始异常，界面据此区分「仅本机」「账本只读」。 */
internal sealed interface PeachLoad<out T> {
    data object Loading : PeachLoad<Nothing>
    data class Ready<T>(val value: T) : PeachLoad<T>
    data class Failed(val error: Throwable) : PeachLoad<Nothing>
}

internal class PeachLoader<T>(private val state: MutableState<PeachLoad<T>>, private val reloadKey: MutableState<Int>) {
    val value: PeachLoad<T> get() = state.value
    fun reload() {
        reloadKey.value += 1
    }
}

/** 进入页面加载一次，[PeachLoader.reload] 后重新加载。 */
@Composable
internal fun <T> rememberPeachLoad(block: suspend PeachRepository.() -> T): PeachLoader<T> {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    val state = remember { mutableStateOf<PeachLoad<T>>(PeachLoad.Loading) }
    val reloadKey = remember { mutableStateOf(0) }
    LaunchedEffect(reloadKey.value) {
        if (state.value !is PeachLoad.Ready) state.value = PeachLoad.Loading
        state.value = runCatching { repository.block() }.fold(
            onSuccess = { PeachLoad.Ready(it) },
            onFailure = { PeachLoad.Failed(it) }
        )
    }
    return remember { PeachLoader(state, reloadKey) }
}

/** 参数变化时重新加载的版本：[key] 变了就清空并重新请求。 */
@Composable
internal fun <T> rememberPeachLoadKeyed(key: Any?, block: suspend PeachRepository.() -> T): PeachLoader<T> {
    val context = LocalContext.current
    val repository = remember { PeachRepository.getInstance(context) }
    val state = remember(key) { mutableStateOf<PeachLoad<T>>(PeachLoad.Loading) }
    val reloadKey = remember(key) { mutableStateOf(0) }
    LaunchedEffect(key, reloadKey.value) {
        if (state.value !is PeachLoad.Ready) state.value = PeachLoad.Loading
        state.value = runCatching { repository.block() }.fold(
            onSuccess = { PeachLoad.Ready(it) },
            onFailure = { PeachLoad.Failed(it) }
        )
    }
    return remember(key) { PeachLoader(state, reloadKey) }
}

/** 写操作：进行中禁用同一按钮，失败弹出服务端的说明，成功后执行 [onDone]。 */
internal class PeachActions(
    private val scope: CoroutineScope,
    private val repository: PeachRepository,
    private val showMessage: (String) -> Unit,
    private val busy: MutableState<Boolean>
) {
    val isBusy: Boolean get() = busy.value

    fun run(successMessage: String? = null, onDone: () -> Unit = {}, block: suspend PeachRepository.() -> Unit) {
        if (busy.value) return
        busy.value = true
        scope.launch {
            runCatching { repository.block() }
                .onSuccess {
                    successMessage?.let(showMessage)
                    onDone()
                }
                .onFailure { error -> showMessage(error.message ?: error.toString()) }
            busy.value = false
        }
    }
}

@Composable
internal fun rememberPeachActions(): PeachActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateOf(false) }
    return remember {
        PeachActions(
            scope = scope,
            repository = PeachRepository.getInstance(context),
            showMessage = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() },
            busy = busy
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeachPageScaffold(
    title: String,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_button),
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.peach_refresh), tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding.excludeBottom())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = padding.bottomContentPadding(),
            content = content
        )
    }
}

/** 统一渲染加载状态；成功时把值交给 [content]。 */
internal fun <T> LazyListScope.peachLoadItems(
    load: PeachLoader<T>,
    content: LazyListScope.(T) -> Unit
) {
    when (val value = load.value) {
        PeachLoad.Loading -> item {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is PeachLoad.Failed -> item { PeachErrorCard(value.error, onRetry = load::reload) }
        is PeachLoad.Ready -> content(value.value)
    }
}

@Composable
internal fun PeachErrorCard(error: Throwable, onRetry: (() -> Unit)?) {
    val peachError = error as? PeachApiException
    val title = when (peachError?.kind) {
        PeachApiException.Kind.LOCAL_ONLY -> stringResource(R.string.peach_local_only_title)
        PeachApiException.Kind.READ_ONLY -> stringResource(R.string.peach_read_only_title)
        PeachApiException.Kind.UNAUTHORIZED -> stringResource(R.string.peach_unauthorized_title)
        else -> stringResource(R.string.peach_load_failed)
    }
    val detail = when (peachError?.kind) {
        PeachApiException.Kind.LOCAL_ONLY -> stringResource(R.string.peach_local_only_detail)
        else -> error.message ?: error.toString()
    }
    PeachSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null && peachError?.kind != PeachApiException.Kind.LOCAL_ONLY) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.peach_retry)) }
            }
        }
    }
}

@Composable
internal fun PeachSection(title: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))
        ) {
            Column { content() }
        }
    }
}

@Composable
internal fun PeachRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Text(value, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }
        when {
            trailing != null -> Box(Modifier.padding(start = 8.dp)) { trailing() }
            onClick != null -> Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun PeachSwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    PeachRow(title = title, subtitle = subtitle, trailing = {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    })
}

@Composable
internal fun PeachDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(start = 16.dp))
}

@Composable
internal fun PeachHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

/** 列表分段：行之间加分隔线。 */
@Composable
internal fun <T> PeachRows(items: List<T>, emptyText: String, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) {
        PeachRow(title = emptyText)
        return
    }
    items.forEachIndexed { index, item ->
        if (index > 0) PeachDivider()
        row(item)
    }
}

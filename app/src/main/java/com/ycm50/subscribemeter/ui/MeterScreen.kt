@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ycm50.subscribemeter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ycm50.subscribemeter.core.ProviderSnapshot
import com.ycm50.subscribemeter.core.QuotaWindow
import com.ycm50.subscribemeter.core.sortedByWindowOrder
import com.ycm50.subscribemeter.data.ProviderConfig
import com.ycm50.subscribemeter.data.ProviderType
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterScreen(
    state: UiState,
    login: LoginState,
    onStartOpenDesignLogin: (String) -> Unit,
    onCancelOpenDesignLogin: () -> Unit,
    onRefresh: () -> Unit,
    onSaveProvider: (ProviderConfig, String?) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSecretOf: (String) -> String,
    onExport: () -> String,
    onImport: (String) -> String,
    onSetRefreshMinutes: (Int) -> Unit,
    onSetNotify: (Boolean, Int) -> Unit,
    onSetProxy: (String) -> Unit,
    onClearMessage: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        if (state.message.isNotEmpty()) {
            snackbar.showSnackbar(state.message)
            onClearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("额度看板", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = subtitleOf(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "刷新") }
                    }
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, contentDescription = "设置") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = "新增额度源")
            }
        },
    ) { padding ->
        val providers = state.config.providers
        if (providers.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(providers, key = { it.id }) { provider ->
                    ProviderCard(
                        provider = provider,
                        snapshot = state.snapshots[provider.id],
                        onClick = { editing = provider },
                    )
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    if (creating) {
        NewProviderDialog(
            onDismiss = { creating = false },
            onCreate = { type ->
                creating = false
                editing = defaultProvider(type)
            },
        )
    }

    editing?.let { provider ->
        ProviderEditorSheet(
            initial = provider,
            existingSecret = onSecretOf(provider.id),
            snapshot = state.snapshots[provider.id],
            login = login,
            onStartLogin = onStartOpenDesignLogin,
            onCancelLogin = onCancelOpenDesignLogin,
            onDismiss = { editing = null },
            onDelete = {
                onDeleteProvider(provider.id)
                editing = null
            },
            onSave = { updated, secret ->
                onSaveProvider(updated, secret)
                editing = null
            },
        )
    }

    if (settingsOpen) {
        SettingsSheet(
            state = state,
            onDismiss = { settingsOpen = false },
            onExport = onExport,
            onImport = onImport,
            onSetRefreshMinutes = onSetRefreshMinutes,
            onSetNotify = onSetNotify,
            onSetProxy = onSetProxy,
        )
    }
}

private fun subtitleOf(state: UiState): String {
    if (state.lastRefreshAt == 0L) return "尚未刷新"
    val ago = Duration.between(Instant.ofEpochMilli(state.lastRefreshAt), Instant.now())
    val minutes = ago.toMinutes()
    val text = when {
        minutes < 1 -> "刚刚刷新"
        minutes < 60 -> minutes.toString() + " 分钟前刷新"
        else -> (minutes / 60).toString() + " 小时前刷新"
    }
    return text + " · 每 " + state.config.refreshMinutes + " 分钟自动刷新"
}

private fun defaultProvider(type: ProviderType): ProviderConfig = when (type) {
    ProviderType.OPENCODE -> ProviderConfig(id = newId(), type = type.id, name = "OpenCode Go", openCodeMode = "go")
    ProviderType.COMMANDCODE -> ProviderConfig(id = newId(), type = type.id, name = "Command Code GOAT", plan = "goat")
    ProviderType.OPENDESIGN -> ProviderConfig(id = newId(), type = type.id, name = "OpenDesign Cloud")
    ProviderType.NEWAPI -> ProviderConfig(id = newId(), type = type.id, name = "new-api")
    ProviderType.CUSTOM -> ProviderConfig(id = newId(), type = type.id, name = "自定义额度")
}

private fun newId(): String = "p_" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有额度源", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "点右下角 + 添加 OpenCode / Command Code / OpenDesign / new-api",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(provider: ProviderConfig, snapshot: ProviderSnapshot?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val subtitle = snapshot?.subtitle?.takeIf { it.isNotEmpty() } ?: ProviderType.of(provider.type)?.title.orEmpty()
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (provider.enabled.not()) {
                    Text("已停用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                snapshot == null -> Text("等待首次刷新…", style = MaterialTheme.typography.bodySmall)
                snapshot.error.isNotEmpty() -> Text(snapshot.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                snapshot.softMessage.isNotEmpty() -> Text(snapshot.softMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    for (window in snapshot.windows.sortedByWindowOrder()) {
                        WindowRow(window)
                        Spacer(Modifier.height(8.dp))
                    }
                    if (snapshot.meta.isNotEmpty()) {
                        Text(
                            snapshot.meta.entries.joinToString(" · ") { it.key + ": " + it.value },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowRow(window: QuotaWindow) {
    val percent = window.displayPercent
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(window.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                String.format("%.1f%%", percent),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = meterColor(percent),
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = meterColor(percent),
            trackColor = MaterialTheme.colorScheme.surface,
        )
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                window.amountText(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            val resetText = resetText(window.resetsAt)
            if (resetText.isNotEmpty()) {
                Text(resetText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (window.note.isNotEmpty()) {
            Text(window.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** 把 ISO 重置时刻转成「3 小时 12 分后重置」。 */
fun resetText(resetsAt: String): String {
    if (resetsAt.isEmpty()) return ""
    val target = runCatching { Instant.parse(resetsAt) }.getOrNull() ?: return ""
    val remain = Duration.between(Instant.now(), target)
    if (remain.isNegative) return "即将重置"
    val days = remain.toDays()
    val hours = remain.toHours() % 24
    val minutes = remain.toMinutes() % 60
    return when {
        days > 0 -> days.toString() + " 天 " + hours + " 小时后重置"
        hours > 0 -> hours.toString() + " 小时 " + minutes + " 分后重置"
        else -> minutes.toString() + " 分钟后重置"
    }
}
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ycm50.subscribemeter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.unit.dp

/** 全局设置:刷新间隔、通知阈值、代理、配置导入导出。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onExport: () -> String,
    onImport: (String) -> String,
    onSetRefreshMinutes: (Int) -> Unit,
    onSetNotify: (Boolean, Int) -> Unit,
    onSetProxy: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var refreshText by remember { mutableStateOf(state.config.refreshMinutes.toString()) }
    var threshold by remember { mutableFloatStateOf(state.config.notifyThreshold.toFloat()) }
    var proxy by remember { mutableStateOf(state.config.proxyUrl) }
    var importText by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("设置", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = refreshText,
                onValueChange = {
                    refreshText = it.filter { ch -> ch.isDigit() }.take(4)
                    refreshText.toIntOrNull()?.let(onSetRefreshMinutes)
                },
                label = { Text("自动刷新间隔(分钟)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row {
                Text("额度吃紧提醒", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.config.notifyEnabled,
                    onCheckedChange = { onSetNotify(it, threshold.toInt()) },
                )
            }
            Text("超过 " + threshold.toInt() + "% 时通知", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { onSetNotify(state.config.notifyEnabled, threshold.toInt()) },
                valueRange = 50f..100f,
                steps = 9,
            )

            OutlinedTextField(
                value = proxy,
                onValueChange = { proxy = it },
                label = { Text("HTTP 代理(可选,如 127.0.0.1:10808)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = { onSetProxy(proxy); hint = "代理已保存" }, modifier = Modifier.fillMaxWidth()) {
                Text("保存代理")
            }

            Text("配置导入导出(不含密钥)", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val text = onExport()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("subscribe-meter-config", text))
                        hint = "配置已复制到剪贴板(不含密钥)"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("复制配置") }
            }
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                label = { Text("粘贴配置 JSON 以导入") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Button(
                onClick = { hint = onImport(importText) },
                modifier = Modifier.fillMaxWidth(),
                enabled = importText.isNotBlank(),
            ) { Text("导入") }

            if (hint.isNotEmpty()) {
                Text(hint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
            Text(
                "密钥只保存在本机 Android Keystore 里,导出配置时会被剔除;换机后请重新填写。",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
    }
}
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ycm50.subscribemeter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ycm50.subscribemeter.data.OpenCodeMode
import com.ycm50.subscribemeter.data.ProviderConfig
import com.ycm50.subscribemeter.data.ProviderType
import com.ycm50.subscribemeter.providers.Plans

/** 新增额度源:先选类型。 */
@Composable
fun NewProviderDialog(onDismiss: () -> Unit, onCreate: (ProviderType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加额度源") },
        text = {
            Column {
                for (type in ProviderType.entries) {
                    TextButton(onClick = { onCreate(type) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(type.title)
                            Text(type.hint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 编辑额度源:字段随类型变化,密钥单独输入(只写不回显)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditorSheet(
    initial: ProviderConfig,
    existingSecret: String,
    snapshot: com.ycm50.subscribemeter.core.ProviderSnapshot? = null,
    login: LoginState,
    onStartLogin: (String) -> Unit,
    onCancelLogin: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (ProviderConfig, String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(initial) }
    // 回显只给掩码:既不泄露密钥,也能让用户确认「已经存过」。
    var secret by remember { mutableStateOf(if (existingSecret.isEmpty()) "" else com.ycm50.subscribemeter.data.maskSecret(existingSecret)) }
    var secretEdited by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val type = ProviderType.of(draft.type)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(type?.title ?: "额度源", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", modifier = Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
            }

            when (type) {
                ProviderType.OPENCODE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mode in OpenCodeMode.entries) {
                            FilterChip(
                                selected = draft.openCodeMode == mode.id,
                                onClick = { draft = draft.copy(openCodeMode = mode.id) },
                                label = { Text(mode.title) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draft.openCodeBaseUrl,
                        onValueChange = { draft = draft.copy(openCodeBaseUrl = it) },
                        label = { Text("站点地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (draft.openCodeMode == OpenCodeMode.ZEN.id) {
                        OutlinedTextField(
                            value = draft.orgId,
                            onValueChange = { draft = draft.copy(orgId = it) },
                            label = { Text("工作区 id(留空自动获取)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }

                ProviderType.COMMANDCODE -> {
                    Text("套餐", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (plan in Plans.entries) {
                            FilterChip(
                                selected = draft.plan == plan.id,
                                onClick = { draft = draft.copy(plan = plan.id) },
                                label = { Text(plan.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = if (draft.monthlyPool > 0) draft.monthlyPool.toString() else "",
                        onValueChange = { draft = draft.copy(monthlyPool = it.toDoubleOrNull() ?: 0.0) },
                        label = { Text("月度额度池(留空＝套餐默认)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        "接口给的 monthlyCredits 是「当前计费模型那一档」的剩余额;若主力模型档位低于所选套餐,请把池改成该档的额度(例如 DeepSeek V4 Flash 档填 60)。",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }

                ProviderType.OPENDESIGN -> {
                    OutlinedTextField(
                        value = draft.workspaceId,
                        onValueChange = { draft = draft.copy(workspaceId = it) },
                        label = { Text("workspaceId(留空自动获取)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = if (draft.openDesignUsdPool > 0) formatPool(draft.openDesignUsdPool) else "",
                        onValueChange = { draft = draft.copy(openDesignUsdPool = it.toDoubleOrNull() ?: 0.0) },
                        label = { Text("总额(美元),留空则显示 credits") },
                        supportingText = { Text("例如自己的 7 天额度是 15000 credits = \$30,就填 30") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        ),
                    )
                    Text("这个总额对应哪个窗口", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((id, label) in listOf("7d" to "7 天额度", "5h" to "5 小时额度")) {
                            FilterChip(
                                selected = draft.openDesignUsdPoolWindow == id,
                                onClick = { draft = draft.copy(openDesignUsdPoolWindow = id) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "额度卡会按同一汇率把各窗口折算成美元;余额卡照旧显示钱包余额。",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }

                ProviderType.NEWAPI -> {
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft = draft.copy(baseUrl = it) },
                        label = { Text("站点地址(如 https://api.example.com)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text("类型", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, label) in listOf("auto" to "自动", "oneapi" to "one-api/new-api", "sub2api" to "sub2api")) {
                            FilterChip(
                                selected = draft.gatewayKind == id,
                                onClick = { draft = draft.copy(gatewayKind = id) },
                                label = { Text(label) },
                            )
                        }
                    }
                }

                ProviderType.CUSTOM -> {
                    OutlinedTextField(
                        value = draft.customUrl,
                        onValueChange = { draft = draft.copy(customUrl = it) },
                        label = { Text("额度接口 URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.customMethod,
                            onValueChange = { draft = draft.copy(customMethod = it) },
                            label = { Text("方法") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = draft.customHeader,
                            onValueChange = { draft = draft.copy(customHeader = it) },
                            label = { Text("认证头") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = draft.customPrefix,
                        onValueChange = { draft = draft.copy(customPrefix = it) },
                        label = { Text("前缀(如 Bearer )") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.customJsonPath,
                        onValueChange = { draft = draft.copy(customJsonPath = it) },
                        label = { Text("百分比路径(如 data.used_percent)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.customUsedPath,
                        onValueChange = { draft = draft.copy(customUsedPath = it) },
                        label = { Text("已用数值路径") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.customCapPath,
                        onValueChange = { draft = draft.copy(customCapPath = it) },
                        label = { Text("总额路径") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.customResetPath,
                        onValueChange = { draft = draft.copy(customResetPath = it) },
                        label = { Text("重置时刻路径") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.customUnit,
                        onValueChange = { draft = draft.copy(customUnit = it) },
                        label = { Text("单位标签(USD / credits / 留空)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                null -> Unit
            }

            WindowUsdCapsBlock(
                type = type,
                draft = draft,
                snapshot = snapshot,
                onChange = { key, value ->
                    val next = draft.windowUsdCaps.toMutableMap()
                    if (value > 0) next[key] = value else next.remove(key)
                    draft = draft.copy(windowUsdCaps = next)
                },
            )

            OutlinedTextField(
                value = secret,
                onValueChange = {
                    // 一旦改动就当作新值;用户原样留下掩码则视为「不修改」。
                    secret = it
                    secretEdited = it != com.ycm50.subscribemeter.data.maskSecret(existingSecret)
                },
                label = { Text(if (type == ProviderType.OPENCODE && draft.openCodeMode == OpenCodeMode.ZEN.id) "控制台 Cookie" else "密钥 / 令牌") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                if (secretEdited || existingSecret.isEmpty()) "密钥用 Android Keystore 加密保存,不随导出的配置一起走"
                else "已保存密钥(留空表示不修改)",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )

            if (type == ProviderType.OPENDESIGN) {
                OpenDesignLoginBlock(login = login, onStart = { onStartLogin(draft.id) }, onCancel = onCancelLogin)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(draft, if (secretEdited) secret else null) },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
                TextButton(onClick = { confirmDelete = true }) { Text("删除") }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个额度源?") },
            text = { Text("配置与已保存的密钥会一起删除。") },
            confirmButton = { TextButton(onClick = onDelete) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
/**
 * OpenDesign 浏览器授权登录块(设备码流程,和官方 vela login 一致):
 * 点「登录 OpenDesign」→ 拿到验证码 + 激活链接 → 浏览器确认 → App 轮询拿到 controlKey 并验证。
 * 注意:未授权时接口返回的 controlKey 是 JSON null,已在解析层显式判掉,不会再误报已登录。
 */
@Composable
private fun OpenDesignLoginBlock(login: LoginState, onStart: () -> Unit, onCancel: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("浏览器授权登录", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        when (login.status) {
            LoginStatus.IDLE, LoginStatus.SUCCEEDED -> {
                if (login.status == LoginStatus.SUCCEEDED) {
                    Text(login.message, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text(if (login.status == LoginStatus.SUCCEEDED) "重新登录" else "登录 OpenDesign")
                }
                Text(
                    "登录态由浏览器授权获得,不需要手填。",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }

            LoginStatus.STARTING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在申请授权码…", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                }
            }

            LoginStatus.WAITING -> {
                Text("验证码:" + login.userCode, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(
                    "用浏览器打开激活页并确认该验证码,成功后会自动写入登录态。",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(login.activationUrl),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("打开激活页") }
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }
            }

            LoginStatus.FAILED -> {
                Text(
                    login.message,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("重试") }
                    OutlinedButton(onClick = onCancel) { Text("关闭") }
                }
            }
        }
    }
}
/** 总额输入框的回显:整数不带小数。 */
private fun formatPool(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.2f", value)
/** 各额度源可配置的窗口(键 → 展示名);填了总额就按美元展示。 */
private fun windowSlotsOf(type: ProviderType?): List<Pair<String, String>> = when (type) {
    ProviderType.OPENCODE -> listOf("rolling" to "5 小时", "weekly" to "本周", "monthly" to "本月")
    ProviderType.COMMANDCODE -> listOf("5h" to "5 小时", "weekly" to "本周", "monthly" to "月度池")
    ProviderType.OPENDESIGN -> listOf("5h" to "5 小时", "7d" to "7 天")
    ProviderType.NEWAPI -> listOf("rolling" to "5 小时", "weekly" to "本周", "monthly" to "额度")
    ProviderType.CUSTOM -> listOf("monthly" to "额度")
    null -> emptyList()
}

/**
 * 「各窗口总额(美元)」设置块。
 *
 * 用途:
 *  - 接口只给百分比的(OpenCode Go)→ 填了总额就能显示 $ 已用 / $ 总额;
 *  - 接口用 credits / tokens 计的(OpenDesign / 网关)→ 填了一个窗口的总额,其余同单位窗口按同一汇率折算。
 * 留空则该窗口保持接口原始单位。
 */
@Composable
private fun WindowUsdCapsBlock(
    type: ProviderType?,
    draft: ProviderConfig,
    snapshot: com.ycm50.subscribemeter.core.ProviderSnapshot?,
    onChange: (String, Double) -> Unit,
) {
    val slots = windowSlotsOf(type)
    if (slots.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tiered = type == ProviderType.OPENCODE || type == ProviderType.COMMANDCODE || type == ProviderType.OPENDESIGN
        Text("各窗口总额(美元)", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        Text(
            if (tiered) {
                "留空 = 直接用接口返回的额度(推荐)。**三档填任意一个即可**,其余档位会按接口给出的数值比例自动推导。"
            } else {
                "留空 = 直接用接口返回的额度(推荐)。只有接口不给额度、或给的单位不是美元时,才需要在这里填总额。"
            },
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
        for ((key, label) in slots) {
            val current = draft.windowUsdCaps[key] ?: 0.0
            val actual = snapshot?.windows?.firstOrNull { it.id == key }
            val hint = when {
                actual == null -> ""
                actual.unit == "USD" && actual.cap != null ->
                    "接口已返回 " + formatPool(actual.cap) + " USD,留空即用它"

                actual.cap != null ->
                    "接口已返回 " + formatPool(actual.cap) + " " + actual.unit + ",填总额可折算成美元"

                else -> "接口只给百分比,填总额才知道金额"
            }
            OutlinedTextField(
                value = if (current > 0) formatPool(current) else "",
                onValueChange = { text -> onChange(key, text.toDoubleOrNull() ?: 0.0) },
                label = { Text(label + " 总额(美元)") },
                placeholder = { Text(hint) },
                supportingText = if (hint.isEmpty()) null else ({ Text(hint) }),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                ),
            )
        }
    }
}
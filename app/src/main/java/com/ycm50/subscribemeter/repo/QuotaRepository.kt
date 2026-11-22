package com.ycm50.subscribemeter.repo

import com.ycm50.subscribemeter.core.ProviderSnapshot
import com.ycm50.subscribemeter.core.QuotaWindow
import com.ycm50.subscribemeter.core.sortedByWindowOrder
import com.ycm50.subscribemeter.data.ConfigRepository
import com.ycm50.subscribemeter.data.ProviderConfig
import com.ycm50.subscribemeter.data.SnapshotFile
import com.ycm50.subscribemeter.data.SnapshotRecord
import com.ycm50.subscribemeter.data.WindowRecord
import com.ycm50.subscribemeter.providers.ProviderContext
import com.ycm50.subscribemeter.providers.ProviderRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一次全量刷新的结果。 */
data class RefreshOutcome(
    val snapshots: Map<String, ProviderSnapshot>,
    val finishedAt: Long,
) {
    /** 所有可展示窗口里最紧张的一项(通知摘要用)。 */
    val tightest: Pair<ProviderSnapshot, QuotaWindow>?
        get() = snapshots.values
            .filter { it.windows.isNotEmpty() }
            .mapNotNull { s -> s.tightest?.let { w -> s to w } }
            .maxByOrNull { it.second.percent ?: 0.0 }
}

/**
 * 额度仓库:负责并发刷新所有启用的额度源、把结果缓存成 StateFlow、并持久化快照。
 *
 * 设计:
 *  - 冷启动先从 snapshot.json 恢复上次读数(instant 展示),再静默刷新;
 *  - 每个 provider 独立协程,单个失败不影响其它卡片;
 *  - 失败/软失败都写进快照,UI 据此显示中性提示而不是空白。
 */
class QuotaRepository(
    private val config: ConfigRepository,
    private val registry: ProviderRegistry,
) {
    private val _snapshots = MutableStateFlow<Map<String, ProviderSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, ProviderSnapshot>> = _snapshots.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _lastRefreshAt = MutableStateFlow(0L)
    val lastRefreshAt: StateFlow<Long> = _lastRefreshAt.asStateFlow()

    /** 从磁盘恢复上次结果(冷启动首屏)。 */
    fun restoreFromDisk() {
        val file = config.snapshot()
        if (file.snapshots.isEmpty()) return
        val restored = file.snapshots.associate { record ->
            record.providerId to ProviderSnapshot(
                providerId = record.providerId,
                windows = record.windows.map { w ->
                    QuotaWindow(
                        id = w.id,
                        label = w.label,
                        percent = w.percent,
                        used = w.used,
                        cap = w.cap,
                        remaining = w.remaining,
                        unit = w.unit,
                        resetsAt = w.resetsAt,
                        note = w.note,
                    )
                }.sortedByWindowOrder(),
                subtitle = record.subtitle,
                meta = record.meta,
                fetchedAt = record.fetchedAt,
                softMessage = record.softMessage,
                error = record.error,
                endpoint = record.endpoint,
            )
        }
        _snapshots.value = restored
        _lastRefreshAt.value = file.savedAt
    }

    /** 全量刷新所有启用的额度源;单卡失败不影响其它卡片。 */
    suspend fun refreshAll(): RefreshOutcome = coroutineScope {
        _refreshing.value = true
        try {
            val enabled = config.config().providers.filter { it.enabled }
            val results = enabled.map { provider ->
                async { provider.id to fetchOne(provider) }
            }.awaitAll()
            val merged = _snapshots.value.toMutableMap()
            for ((id, snapshot) in results) merged[id] = snapshot
            _snapshots.value = merged
            val finishedAt = System.currentTimeMillis()
            _lastRefreshAt.value = finishedAt
            persist(merged, finishedAt)
            RefreshOutcome(merged, finishedAt)
        } finally {
            _refreshing.value = false
        }
    }

    /** 单个额度源查询(异常收敛成快照里的 error,不向外抛)。 */
    private suspend fun fetchOne(provider: ProviderConfig): ProviderSnapshot {
        val secret = config.secret(provider.id)
        return runCatching {
            registry.providerFor(provider).fetch(ProviderContext(provider, secret))
        }.getOrElse { error ->
            ProviderSnapshot(
                providerId = provider.id,
                fetchedAt = System.currentTimeMillis(),
                error = "查询失败:" + (error.message ?: error::class.java.simpleName),
            )
        }
    }

    private fun persist(snapshots: Map<String, ProviderSnapshot>, savedAt: Long) {
        val records = snapshots.values.map { s ->
            SnapshotRecord(
                providerId = s.providerId,
                windows = s.windows.map { w ->
                    WindowRecord(
                        id = w.id,
                        label = w.label,
                        percent = w.percent,
                        used = w.used,
                        cap = w.cap,
                        remaining = w.remaining,
                        unit = w.unit,
                        resetsAt = w.resetsAt,
                        note = w.note,
                    )
                },
                subtitle = s.subtitle,
                meta = s.meta,
                fetchedAt = s.fetchedAt,
                softMessage = s.softMessage,
                error = s.error,
                endpoint = s.endpoint,
            )
        }
        config.saveSnapshot(SnapshotFile(savedAt = savedAt, snapshots = records))
    }

    /** 删除额度源后同步清掉快照,避免幽灵卡片。 */
    fun forget(providerId: String) {
        _snapshots.value = _snapshots.value - providerId
    }
}

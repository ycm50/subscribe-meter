package com.ycm50.subscribemeter

import android.app.Application
import com.ycm50.subscribemeter.data.ConfigRepository
import com.ycm50.subscribemeter.net.NetworkClient
import com.ycm50.subscribemeter.providers.ProviderRegistry
import com.ycm50.subscribemeter.repo.QuotaRepository

/** 应用入口:构建一个进程级单例容器(仓库/网络/适配器),避免 Activity 重建时重复初始化。 */
class MeterApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

class AppGraph(app: Application) {
    val config: ConfigRepository = ConfigRepository(app)
    val network: NetworkClient = NetworkClient { config.config().proxyUrl }
    val registry: ProviderRegistry = ProviderRegistry(network)
    val repository: QuotaRepository = QuotaRepository(config, registry)
}

/** 便捷取容器。 */
val android.content.Context.appGraph: AppGraph
    get() = (applicationContext as MeterApp).graph

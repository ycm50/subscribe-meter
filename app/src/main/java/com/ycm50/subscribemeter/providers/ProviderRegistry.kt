package com.ycm50.subscribemeter.providers

import com.ycm50.subscribemeter.data.ProviderConfig
import com.ycm50.subscribemeter.data.ProviderType
import com.ycm50.subscribemeter.net.NetworkClient

/** 类型 → adapter 的注册表;新增额度源只需在这里加一行。 */
class ProviderRegistry(private val http: NetworkClient) {

    private val openCode = OpenCodeProvider(http)
    private val commandCode = CommandCodeProvider(http)
    private val openDesign = OpenDesignProvider(http)
    private val newApi = NewApiProvider(http)
    private val custom = CustomJsonProvider(http)

    fun providerFor(config: ProviderConfig): QuotaProvider = when (ProviderType.of(config.type)) {
        ProviderType.OPENCODE -> openCode
        ProviderType.COMMANDCODE -> commandCode
        ProviderType.OPENDESIGN -> openDesign
        ProviderType.NEWAPI -> newApi
        ProviderType.CUSTOM -> custom
        null -> custom
    }
}

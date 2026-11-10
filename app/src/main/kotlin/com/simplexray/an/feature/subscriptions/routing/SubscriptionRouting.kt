package com.simplexray.an.feature.subscriptions.routing

import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingRule
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SubscriptionRoutingBlock(
    val target: RouteTarget,
    val text: String,
    val isServer: Boolean = false,
    val server: String? = null,
)

data class SubscriptionRouting(
    val blocks: List<SubscriptionRoutingBlock>,
    val geoipUrl: String? = null,
    val geositeUrl: String? = null,
    val updatedAt: Long = 0L,
) {
    fun validate() {
        require(blocks.size in 1..32) { "В роутинге подписки нужно от 1 до 32 блоков" }
        RouteTarget.entries.forEach { target ->
            require(blocks.count { !it.isServer && it.target == target } <= 1) {
                "В роутинге подписки повторяется блок ${target.name}"
            }
        }
        blocks.forEach { block ->
            require(!block.isServer || block.target == RouteTarget.PROXY) {
                "Серверный блок должен использовать прокси"
            }
            require(block.isServer || block.server == null) {
                "Встроенный блок не может содержать сервер"
            }
            block.server?.let {
                require(it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl)) {
                    "Некорректное имя сервера в роутинге подписки"
                }
            }
        }
        listOfNotNull(geoipUrl, geositeUrl).forEach { url ->
            require(
                url.length <= 8192 &&
                    url == url.trim() &&
                    url.none { it.code < 32 || it.code == 127 } &&
                    url.toHttpUrlOrNull() != null
            ) {
                "Для геоданных подписки нужен корректный HTTP или HTTPS URL"
            }
        }
        val rules = rules { "sub_$it" }
        require(rules.size <= 64) { "Не больше 64 правил в роутинге подписки" }
    }

    fun rules(serverId: (Int) -> String): List<RoutingRule> =
        blocks.flatMapIndexed { index, block -> rules(index, block, serverId) }

    fun rules(index: Int, block: SubscriptionRoutingBlock, serverId: (Int) -> String) =
        RoutingBlocks.parse(
            RoutingBlock(
                block.target,
                block.text,
                if (block.isServer) serverId(index) else block.target.name,
            )
        )
}

data class SubscriptionRoutingView(
    val routing: SubscriptionRouting,
    val unresolved: List<String>,
)

package com.simplexray.an.feature.routing.state

import com.simplexray.an.feature.routing.model.RouteTarget
import com.simplexray.an.feature.routing.model.RoutingBlock
import com.simplexray.an.feature.routing.model.RoutingBlocks
import com.simplexray.an.feature.routing.model.RoutingServerRef
import com.simplexray.an.feature.routing.model.RoutingSettings
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RoutingEditorState(
    val draft: RoutingSettings = RoutingSettings(),
    val corrupt: Boolean = false,
    val error: String? = null,
    val revision: Int = 0,
)

class RoutingEditor(private val read: () -> String?, private val write: (String) -> Unit) {
    private var saved = RoutingSettings()
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()

    private fun load(): RoutingEditorState =
        try {
            saved = RoutingSettings.decode(read())
            RoutingEditorState(draft = saved)
        } catch (_: Exception) {
            RoutingEditorState(
                corrupt = true,
                error = "Настройки роутинга повреждены. Сбрось их, чтобы продолжить.",
            )
        }

    fun edit(change: (RoutingSettings) -> RoutingSettings): Boolean {
        val current = mutableState.value
        if (current.corrupt) return false
        try {
            return persist(change(current.draft).copy(enabled = true).also { it.validate() })
        } catch (error: IllegalArgumentException) {
            mutableState.value = current.copy(error = error.message)
            return false
        }
    }

    fun reset(): Boolean = persist(RoutingSettings(), replace = true)

    fun reload() {
        val revision = mutableState.value.revision + 1
        mutableState.value = load().copy(revision = revision)
    }

    fun saveBlock(target: RouteTarget, text: String): Boolean = saveBlock(target.name, text)

    fun saveBlock(id: String, text: String): Boolean = edit { settings ->
        val blocks = settings.editableBlocks()
        require(blocks.any { it.id == id }) { "Блок не найден" }
        settings.withBlocks(blocks.map { if (it.id == id) it.copy(text = text) else it })
    }

    fun moveBlock(target: RouteTarget, destination: Int): Boolean =
        moveBlock(target.name, destination)

    fun moveBlock(id: String, destination: Int): Boolean = edit { settings ->
        val blocks = settings.editableBlocks().toMutableList()
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0 || index == destination || destination !in blocks.indices) settings
        else {
            blocks.add(destination, blocks.removeAt(index))
            settings.withBlocks(blocks)
        }
    }

    fun addServerBlock(server: RoutingServerRef): Boolean = edit { settings ->
        server.validate()
        val blocks = settings.editableBlocks().toMutableList()
        require(blocks.size < 32) { "Не больше 32 блоков" }
        val index = blocks.indexOfFirst { it.target == RouteTarget.PROXY }
        blocks.add(
            index,
            RoutingBlock(RouteTarget.PROXY, id = "route_${UUID.randomUUID()}", server = server),
        )
        settings.withBlocks(blocks)
    }

    fun selectBlockServer(id: String, server: RoutingServerRef): Boolean = edit { settings ->
        server.validate()
        val blocks = settings.editableBlocks()
        require(blocks.any { it.id == id && it.isServer }) { "Серверный блок не найден" }
        settings.withBlocks(blocks.map { if (it.id == id) it.copy(server = server) else it })
    }

    fun removeBlock(id: String): Boolean = edit { settings ->
        val blocks = settings.editableBlocks()
        require(blocks.any { it.id == id && it.isServer }) { "Можно удалить только серверный блок" }
        settings.withBlocks(blocks.filterNot { it.id == id })
    }

    private fun RoutingSettings.editableBlocks(): List<RoutingBlock> =
        blocks ?: RoutingBlocks.fromRules(rules)

    private fun RoutingSettings.withBlocks(blocks: List<RoutingBlock>): RoutingSettings =
        copy(
            version = if (blocks.any { it.isServer }) 2 else 1,
            blocks = blocks,
            rules = blocks.flatMap(RoutingBlocks::parse),
        )

    private fun persist(settings: RoutingSettings, replace: Boolean = false): Boolean {
        val current = mutableState.value
        return try {
            val encoded = settings.encode()
            write(encoded)
            check(read() == encoded)
            saved = settings
            mutableState.value =
                RoutingEditorState(
                    draft = saved,
                    revision = current.revision + if (replace) 1 else 0,
                )
            true
        } catch (error: Exception) {
            mutableState.value =
                current.copy(
                    error =
                        if (error is IllegalArgumentException) error.message
                        else "Не удалось сохранить настройки роутинга"
                )
            false
        }
    }
}

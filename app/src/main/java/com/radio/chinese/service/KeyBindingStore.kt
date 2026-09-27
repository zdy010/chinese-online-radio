package com.radio.chinese.service

import com.radio.chinese.data.local.RadioPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 车机按键映射表：读配置、供按键分发链路同步查表、写回 DataStore。
 *
 * 绑定只做「追加触发源」——默认的标准媒体键切台不受影响，
 * 所以用户不可能把自己配成所有键都没反应。
 */
@Singleton
class KeyBindingStore @Inject constructor(
    private val preferences: RadioPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _bindings = MutableStateFlow<Map<CarKeyAction, KeyBinding>>(emptyMap())
    val bindings: StateFlow<Map<CarKeyAction, KeyBinding>> = _bindings

    /** 按键分发发生在主线程且不能挂起，所以另存一份码→功能的快照供同步查表 */
    @Volatile
    private var byKeyCode: Map<Int, CarKeyAction> = emptyMap()

    init {
        scope.launch {
            preferences.keyBindingsJson.collect { raw ->
                val map = decode(raw)
                _bindings.value = map
                byKeyCode = map.mapNotNull { (action, binding) ->
                    (binding as? KeyBinding.KeyCode)?.code?.let { it to action }
                }.toMap()
            }
        }
    }

    fun actionFor(keyCode: Int): CarKeyAction? = byKeyCode[keyCode]

    /**
     * 把某个键绑到某功能。一个键只服务一个功能：先按码清掉旧绑定再写新，
     * 否则同一个键挂两个功能时用户会看到"按一次做两件事"。
     */
    suspend fun bind(action: CarKeyAction, binding: KeyBinding) {
        val current = _bindings.value.toMutableMap()
        val dupe = current.filterValues { it.token == binding.token }.keys.toList()
        dupe.forEach { current.remove(it) }
        current[action] = binding
        save(current)
    }

    suspend fun unbind(action: CarKeyAction) {
        val current = _bindings.value.toMutableMap()
        current.remove(action)
        save(current)
    }

    suspend fun clearAll() {
        preferences.setKeyBindingsJson(null)
    }

    private suspend fun save(map: Map<CarKeyAction, KeyBinding>) {
        preferences.setKeyBindingsJson(encode(map))
    }

    private fun encode(map: Map<CarKeyAction, KeyBinding>): String? {
        if (map.isEmpty()) return null
        val entries = map.entries.associate { (action, binding) -> action.token to JsonPrimitive(binding.token) }
        return JsonObject(entries).toString()
    }

    private fun decode(raw: String?): Map<CarKeyAction, KeyBinding> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            Json.parseToJsonElement(raw).jsonObject.mapNotNull { (token, value) ->
                val action = CarKeyAction.entries.firstOrNull { it.token == token } ?: return@mapNotNull null
                val binding = KeyBinding.parse(value.jsonPrimitive.content) ?: return@mapNotNull null
                action to binding
            }.toMap()
        } catch (_: Exception) {
            // 手改坏配置文件时当作没绑过，不能让整个按键链路瘫痪
            emptyMap()
        }
    }
}

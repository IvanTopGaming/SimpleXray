# Subscriptions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать пользователю добавлять подписки (URL с base64-списком ссылок), которые превращаются в набор обычных конфигов, с ручным обновлением и группировкой в экране Config.

**Architecture:** Каждый сервер подписки сохраняется как отдельный полный `.json` конфиг (минимальный, как существующий импорт vless-ссылок — socks inbound + outbound, без routing). Принадлежность файла подписке хранится в реестре подписок в `Preferences` (`files: List<String>`). Отдельный `SubscriptionManager` качает URL (proxy-aware OkHttp, как `downloadRuleFile`), парсит через существующий `ConfigFormatConverter`, атомарно пересобирает файлы группы. UI — секция в `ConfigScreen`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), OkHttp 4.12, Gson, org.json, coroutines/StateFlow.

## Global Constraints

- Код **без комментариев** (стиль проекта).
- Коммиты: author = committer = `IvanTopGaming <zjarc0@mail.ru>`. Перед `git commit` экспортировать `GIT_AUTHOR_NAME/GIT_AUTHOR_EMAIL/GIT_COMMITTER_NAME/GIT_COMMITTER_EMAIL`. Никаких `Co-Authored-By` и упоминаний ассистента.
- Никаких автотестов (решение пользователя). Проверка каждой задачи: `./gradlew :app:compileDebugKotlin` (или `assembleDebug`) + ручная проверка на устройстве.
- Парсеры повторяют стиль `VlessLinkConverter`: минимальный конфиг (socks inbound из `Preferences(context).socksPort` + outbound), без блока routing.
- Строковые ресурсы — только через `R.string.*` (проект локализован), новые строки добавлять в `app/src/main/res/values/strings.xml`.
- Удаление отдельного сервера подписки заблокировано в UI — удаляется только вся подписка.
- Package для новых data-классов: `com.simplexray.an.data.model`.

---

### Task 1: Модель Subscription + хранение в Preferences

**Files:**
- Create: `app/src/main/kotlin/com/simplexray/an/data/model/Subscription.kt`
- Modify: `app/src/main/kotlin/com/simplexray/an/prefs/Preferences.kt` (companion + новое свойство)

**Interfaces:**
- Produces:
  - `data class Subscription(val id: String, val name: String, val url: String, val lastUpdated: Long, val files: List<String>)`
  - `var Preferences.subscriptions: List<Subscription>`
  - `const val Preferences.SUBSCRIPTIONS: String`

- [ ] **Step 1: Создать модель**

`app/src/main/kotlin/com/simplexray/an/data/model/Subscription.kt`:

```kotlin
package com.simplexray.an.data.model

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long,
    val files: List<String>
)
```

- [ ] **Step 2: Добавить константу ключа в companion `Preferences`**

В `Preferences.kt`, companion object, рядом с `const val CONFIG_FILES_ORDER`:

```kotlin
        const val SUBSCRIPTIONS: String = "Subscriptions"
```

- [ ] **Step 3: Добавить импорт модели в `Preferences.kt`**

Рядом с существующими импортами:

```kotlin
import com.simplexray.an.data.model.Subscription
```

- [ ] **Step 4: Добавить свойство `subscriptions` (по образцу `configFilesOrder`)**

В классе `Preferences`, после свойства `configFilesOrder`:

```kotlin
    var subscriptions: List<Subscription>
        get() {
            val jsonList = getPrefData(SUBSCRIPTIONS).first
            return jsonList?.let {
                try {
                    val type = object : TypeToken<List<Subscription>>() {}.type
                    gson.fromJson(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing SUBSCRIPTIONS List<Subscription>", e)
                    emptyList()
                }
            } ?: emptyList()
        }
        set(value) {
            setValueInProvider(SUBSCRIPTIONS, gson.toJson(value))
        }
```

- [ ] **Step 5: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
export GIT_AUTHOR_NAME="IvanTopGaming" GIT_AUTHOR_EMAIL="zjarc0@mail.ru" \
       GIT_COMMITTER_NAME="IvanTopGaming" GIT_COMMITTER_EMAIL="zjarc0@mail.ru"
git add app/src/main/kotlin/com/simplexray/an/data/model/Subscription.kt \
        app/src/main/kotlin/com/simplexray/an/prefs/Preferences.kt
git commit -m "feat: add Subscription model and preferences storage"
```

---

### Task 2: Парсер vmess-ссылок

**Files:**
- Create: `app/src/main/kotlin/com/simplexray/an/common/configFormat/VmessLinkConverter.kt`

**Interfaces:**
- Consumes: `ConfigFormatConverter` interface, `DetectedConfig` (= `Pair<String,String>`), `Preferences(context).socksPort`.
- Produces: `class VmessLinkConverter : ConfigFormatConverter`.

Формат: `vmess://` + Base64(JSON). JSON-поля: `ps`(имя), `add`(адрес), `port`, `id`, `aid`(alterId), `scy`(cipher, дефолт `auto`), `net`(tcp/ws/grpc/h2), `host`, `path`, `tls`(`""`/`tls`), `sni`.

- [ ] **Step 1: Реализация**

```kotlin
package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import org.json.JSONObject
import java.util.Base64

class VmessLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith("vmess://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val payload = content.substring("vmess://".length).trim()
            val decoded = String(Base64.getDecoder().decode(padBase64(payload)))
            val v = JSONObject(decoded)

            val name = v.optString("ps").ifEmpty { "imported_vmess_" + System.currentTimeMillis() }
            val address = v.optString("add").ifEmpty { return Result.failure(RuntimeException("Missing add")) }
            val port = v.optString("port").toIntOrNull() ?: 443
            val id = v.optString("id").ifEmpty { return Result.failure(RuntimeException("Missing id")) }
            val alterId = v.optString("aid").toIntOrNull() ?: 0
            val security = v.optString("scy").ifEmpty { "auto" }
            val network = v.optString("net").ifEmpty { "tcp" }.let { if (it == "h2") "http" else it }
            val host = v.optString("host")
            val path = v.optString("path").ifEmpty { "/" }
            val tls = v.optString("tls")
            val sni = v.optString("sni").ifEmpty { host.ifEmpty { address } }

            val socksPort = Preferences(context).socksPort

            val streamSettings = JSONObject().put("network", network)
            if (tls == "tls") {
                streamSettings.put("security", "tls")
                streamSettings.put("tlsSettings", JSONObject().put("serverName", sni))
            }
            when (network) {
                "ws" -> streamSettings.put(
                    "wsSettings",
                    JSONObject().put("path", path).put(
                        "headers",
                        JSONObject().apply { if (host.isNotEmpty()) put("Host", host) }
                    )
                )
                "grpc" -> streamSettings.put(
                    "grpcSettings",
                    JSONObject().put("serviceName", path.trimStart('/'))
                )
                "http" -> streamSettings.put(
                    "httpSettings",
                    JSONObject().put("path", path).apply {
                        if (host.isNotEmpty()) put("host", listOf(host))
                    }
                )
            }

            val config = JSONObject(
                mapOf(
                    "log" to mapOf("loglevel" to "warning"),
                    "inbounds" to listOf(
                        mapOf(
                            "port" to socksPort,
                            "listen" to "127.0.0.1",
                            "protocol" to "socks",
                            "settings" to mapOf("udp" to true)
                        )
                    ),
                    "outbounds" to listOf(
                        mapOf(
                            "protocol" to "vmess",
                            "settings" to mapOf(
                                "vnext" to listOf(
                                    mapOf(
                                        "address" to address,
                                        "port" to port,
                                        "users" to listOf(
                                            mapOf(
                                                "id" to id,
                                                "alterId" to alterId,
                                                "security" to security
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
            config.getJSONArray("outbounds").getJSONObject(0).put("streamSettings", streamSettings)

            Result.success(DetectedConfig(name, config.toString(2)))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun padBase64(s: String): String {
        val clean = s.replace("-", "+").replace("_", "/")
        val pad = (4 - clean.length % 4) % 4
        return clean + "=".repeat(pad)
    }
}
```

- [ ] **Step 2: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
export GIT_AUTHOR_NAME="IvanTopGaming" GIT_AUTHOR_EMAIL="zjarc0@mail.ru" \
       GIT_COMMITTER_NAME="IvanTopGaming" GIT_COMMITTER_EMAIL="zjarc0@mail.ru"
git add app/src/main/kotlin/com/simplexray/an/common/configFormat/VmessLinkConverter.kt
git commit -m "feat: add vmess link converter"
```

---

### Task 3: Парсер shadowsocks-ссылок

**Files:**
- Create: `app/src/main/kotlin/com/simplexray/an/common/configFormat/ShadowsocksLinkConverter.kt`

**Interfaces:**
- Consumes: `ConfigFormatConverter`, `DetectedConfig`, `Preferences(context).socksPort`.
- Produces: `class ShadowsocksLinkConverter : ConfigFormatConverter`.

Формы: `ss://base64(method:password)@host:port#name` (SIP002) и legacy `ss://base64(method:password@host:port)#name`. Plugin-параметры игнорируем в v1.

- [ ] **Step 1: Реализация**

```kotlin
package com.simplexray.an.common.configFormat

import android.content.Context
import androidx.core.net.toUri
import com.simplexray.an.prefs.Preferences
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64

class ShadowsocksLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith("ss://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val hashIndex = content.indexOf('#')
            val name = if (hashIndex != -1) {
                URLDecoder.decode(content.substring(hashIndex + 1), "UTF-8")
            } else {
                "imported_ss_" + System.currentTimeMillis()
            }
            val body = (if (hashIndex != -1) content.substring(0, hashIndex) else content)
                .substring("ss://".length)
                .substringBefore("?")

            val method: String
            val password: String
            val host: String
            val port: Int

            val atIndex = body.lastIndexOf('@')
            if (atIndex != -1) {
                val userInfo = String(Base64.getDecoder().decode(padBase64(body.substring(0, atIndex))))
                method = userInfo.substringBefore(":")
                password = userInfo.substringAfter(":")
                val hostPort = body.substring(atIndex + 1)
                host = hostPort.substringBeforeLast(":")
                port = hostPort.substringAfterLast(":").toIntOrNull()
                    ?: return Result.failure(RuntimeException("Missing port"))
            } else {
                val decoded = String(Base64.getDecoder().decode(padBase64(body)))
                val methodPass = decoded.substringBefore("@")
                val hostPort = decoded.substringAfter("@")
                method = methodPass.substringBefore(":")
                password = methodPass.substringAfter(":")
                host = hostPort.substringBeforeLast(":")
                port = hostPort.substringAfterLast(":").toIntOrNull()
                    ?: return Result.failure(RuntimeException("Missing port"))
            }

            val socksPort = Preferences(context).socksPort

            val config = JSONObject(
                mapOf(
                    "log" to mapOf("loglevel" to "warning"),
                    "inbounds" to listOf(
                        mapOf(
                            "port" to socksPort,
                            "listen" to "127.0.0.1",
                            "protocol" to "socks",
                            "settings" to mapOf("udp" to true)
                        )
                    ),
                    "outbounds" to listOf(
                        mapOf(
                            "protocol" to "shadowsocks",
                            "settings" to mapOf(
                                "servers" to listOf(
                                    mapOf(
                                        "address" to host,
                                        "port" to port,
                                        "method" to method,
                                        "password" to password
                                    )
                                )
                            )
                        )
                    )
                )
            )

            Result.success(DetectedConfig(name, config.toString(2)))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun padBase64(s: String): String {
        val clean = s.replace("-", "+").replace("_", "/")
        val pad = (4 - clean.length % 4) % 4
        return clean + "=".repeat(pad)
    }
}
```

- [ ] **Step 2: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
export GIT_AUTHOR_NAME="IvanTopGaming" GIT_AUTHOR_EMAIL="zjarc0@mail.ru" \
       GIT_COMMITTER_NAME="IvanTopGaming" GIT_COMMITTER_EMAIL="zjarc0@mail.ru"
git add app/src/main/kotlin/com/simplexray/an/common/configFormat/ShadowsocksLinkConverter.kt
git commit -m "feat: add shadowsocks link converter"
```

---

### Task 4: Парсер trojan-ссылок

**Files:**
- Create: `app/src/main/kotlin/com/simplexray/an/common/configFormat/TrojanLinkConverter.kt`

**Interfaces:**
- Consumes: `ConfigFormatConverter`, `DetectedConfig`, `Preferences(context).socksPort`.
- Produces: `class TrojanLinkConverter : ConfigFormatConverter`.

Формат: `trojan://password@host:port?security=tls&sni=...&type=tcp&host=..&path=..#name`.

- [ ] **Step 1: Реализация**

```kotlin
package com.simplexray.an.common.configFormat

import android.content.Context
import androidx.core.net.toUri
import com.simplexray.an.prefs.Preferences
import org.json.JSONObject

class TrojanLinkConverter : ConfigFormatConverter {
    override fun detect(content: String): Boolean {
        return content.startsWith("trojan://")
    }

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val url = content.toUri()
            val name = url.fragment ?: ("imported_trojan_" + System.currentTimeMillis())
            val address = url.host ?: return Result.failure(RuntimeException("Missing host"))
            val port = url.port.takeIf { it != -1 } ?: 443
            val password = url.userInfo ?: return Result.failure(RuntimeException("Missing password"))

            val network = url.getQueryParameter("type") ?: "tcp"
            val sni = url.getQueryParameter("sni") ?: url.getQueryParameter("peer") ?: address
            val host = url.getQueryParameter("host") ?: ""
            val path = url.getQueryParameter("path") ?: "/"

            val socksPort = Preferences(context).socksPort

            val streamSettings = JSONObject()
                .put("network", network)
                .put("security", "tls")
                .put("tlsSettings", JSONObject().put("serverName", sni))
            when (network) {
                "ws" -> streamSettings.put(
                    "wsSettings",
                    JSONObject().put("path", path).put(
                        "headers",
                        JSONObject().apply { if (host.isNotEmpty()) put("Host", host) }
                    )
                )
                "grpc" -> streamSettings.put(
                    "grpcSettings",
                    JSONObject().put("serviceName", path.trimStart('/'))
                )
            }

            val config = JSONObject(
                mapOf(
                    "log" to mapOf("loglevel" to "warning"),
                    "inbounds" to listOf(
                        mapOf(
                            "port" to socksPort,
                            "listen" to "127.0.0.1",
                            "protocol" to "socks",
                            "settings" to mapOf("udp" to true)
                        )
                    ),
                    "outbounds" to listOf(
                        mapOf(
                            "protocol" to "trojan",
                            "settings" to mapOf(
                                "servers" to listOf(
                                    mapOf(
                                        "address" to address,
                                        "port" to port,
                                        "password" to password
                                    )
                                )
                            )
                        )
                    )
                )
            )
            config.getJSONArray("outbounds").getJSONObject(0).put("streamSettings", streamSettings)

            Result.success(DetectedConfig(name, config.toString(2)))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
```

- [ ] **Step 2: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
export GIT_AUTHOR_NAME="IvanTopGaming" GIT_AUTHOR_EMAIL="zjarc0@mail.ru" \
       GIT_COMMITTER_NAME="IvanTopGaming" GIT_COMMITTER_EMAIL="zjarc0@mail.ru"
git add app/src/main/kotlin/com/simplexray/an/common/configFormat/TrojanLinkConverter.kt
git commit -m "feat: add trojan link converter"
```

---

### Task 5: Регистрация парсеров + SubscriptionParser

**Files:**
- Modify: `app/src/main/kotlin/com/simplexray/an/common/configFormat/ConfigFormatConverter.kt:10-13`
- Create: `app/src/main/kotlin/com/simplexray/an/common/configFormat/SubscriptionParser.kt`

**Interfaces:**
- Consumes: `ConfigFormatConverter.convertOrNull(context, line)`, `DetectedConfig`.
- Produces: `object SubscriptionParser { fun parse(context: Context, rawBody: String): List<DetectedConfig> }`.

- [ ] **Step 1: Зарегистрировать новые парсеры**

В `ConfigFormatConverter.kt` заменить список `knownImplementations`:

```kotlin
        val knownImplementations = listOf(
            SimpleXrayFormatConverter(),
            VlessLinkConverter(),
            VmessLinkConverter(),
            ShadowsocksLinkConverter(),
            TrojanLinkConverter(),
        )
```

- [ ] **Step 2: Реализовать SubscriptionParser**

`app/src/main/kotlin/com/simplexray/an/common/configFormat/SubscriptionParser.kt`:

```kotlin
package com.simplexray.an.common.configFormat

import android.content.Context
import java.util.Base64

object SubscriptionParser {
    fun parse(context: Context, rawBody: String): List<DetectedConfig> {
        val text = decodeIfBase64(rawBody.trim())
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                ConfigFormatConverter.convertOrNull(context, line)?.getOrNull()
            }
            .toList()
    }

    private fun decodeIfBase64(body: String): String {
        if (body.contains("://")) return body
        return try {
            val clean = body.replace("-", "+").replace("_", "/").replace("\n", "").replace("\r", "")
            val pad = (4 - clean.length % 4) % 4
            val decoded = String(Base64.getDecoder().decode(clean + "=".repeat(pad)))
            if (decoded.contains("://")) decoded else body
        } catch (e: Exception) {
            body
        }
    }
}
```

- [ ] **Step 3: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
export GIT_AUTHOR_NAME="IvanTopGaming" GIT_AUTHOR_EMAIL="zjarc0@mail.ru" \
       GIT_COMMITTER_NAME="IvanTopGaming" GIT_COMMITTER_EMAIL="zjarc0@mail.ru"
git add app/src/main/kotlin/com/simplexray/an/common/configFormat/ConfigFormatConverter.kt \
        app/src/main/kotlin/com/simplexray/an/common/configFormat/SubscriptionParser.kt
git commit -m "feat: register link converters and add subscription parser"
```

---

### Task 6: SubscriptionManager (fetch + sync)

**Files:**
- Create: `app/src/main/kotlin/com/simplexray/an/data/source/SubscriptionManager.kt`
- Modify: `app/src/main/res/values/strings.xml` (строки ошибок)

**Interfaces:**
- Consumes: `Preferences.subscriptions`, `Preferences.configFilesOrder`, `Preferences.selectedConfigPath`, `SubscriptionParser.parse`, `ConfigUtils.formatConfigContent`, `FilenameValidator`, proxy-aware OkHttp.
- Produces:
  - `class SubscriptionManager(application: Application, prefs: Preferences, isServiceEnabled: () -> Boolean)`
  - `suspend fun add(name: String, url: String): Result<Subscription>`
  - `suspend fun refresh(id: String): Result<Subscription>`
  - `suspend fun delete(id: String): Boolean`

- [ ] **Step 1: Добавить строки ошибок в `strings.xml`**

В `app/src/main/res/values/strings.xml` перед `</resources>`:

```xml
    <string name="subscription_error_network">Failed to fetch subscription</string>
    <string name="subscription_error_empty">No valid servers in subscription</string>
    <string name="subscription_error_not_found">Subscription not found</string>
```

- [ ] **Step 2: Реализовать SubscriptionManager**

`app/src/main/kotlin/com/simplexray/an/data/source/SubscriptionManager.kt`:

```kotlin
package com.simplexray.an.data.source

import android.app.Application
import android.util.Log
import com.simplexray.an.R
import com.simplexray.an.common.ConfigUtils
import com.simplexray.an.common.FilenameValidator
import com.simplexray.an.common.configFormat.SubscriptionParser
import com.simplexray.an.data.model.Subscription
import com.simplexray.an.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

class SubscriptionManager(
    private val application: Application,
    private val prefs: Preferences,
    private val isServiceEnabled: () -> Boolean
) {
    suspend fun add(name: String, url: String): Result<Subscription> {
        val sub = Subscription(
            id = System.currentTimeMillis().toString(),
            name = name.trim(),
            url = url.trim(),
            lastUpdated = 0L,
            files = emptyList()
        )
        prefs.subscriptions = prefs.subscriptions + sub
        return refresh(sub.id)
    }

    suspend fun refresh(id: String): Result<Subscription> = withContext(Dispatchers.IO) {
        val sub = prefs.subscriptions.find { it.id == id }
            ?: return@withContext Result.failure(
                IOException(application.getString(R.string.subscription_error_not_found))
            )

        val body = try {
            fetch(sub.url)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed for ${sub.url}", e)
            return@withContext Result.failure(
                IOException(application.getString(R.string.subscription_error_network))
            )
        }

        val servers = SubscriptionParser.parse(application, body)
        if (servers.isEmpty()) {
            return@withContext Result.failure(
                IOException(application.getString(R.string.subscription_error_empty))
            )
        }

        val filesDir = application.filesDir
        val newFileNames = mutableListOf<String>()
        val usedNames = mutableSetOf<String>()

        for ((serverName, configJson) in servers) {
            val fileName = uniqueFileName(sub.name, serverName, usedNames)
            usedNames.add(fileName)
            val formatted = try {
                ConfigUtils.formatConfigContent(configJson)
            } catch (e: Exception) {
                Log.e(TAG, "Skipping malformed server config: $serverName", e)
                continue
            }
            try {
                File(filesDir, fileName).writeText(formatted)
                newFileNames.add(fileName)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write $fileName", e)
            }
        }

        val removedPaths = sub.files
            .filter { it !in newFileNames }
            .map { File(filesDir, it) }
        removedPaths.forEach { if (it.exists()) it.delete() }

        val updated = sub.copy(files = newFileNames, lastUpdated = System.currentTimeMillis())
        prefs.subscriptions = prefs.subscriptions.map { if (it.id == id) updated else it }

        reconcileOrderAndSelection(newFileNames, sub.files)

        Result.success(updated)
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val sub = prefs.subscriptions.find { it.id == id } ?: return@withContext false
        val filesDir = application.filesDir
        sub.files.forEach { name ->
            val f = File(filesDir, name)
            if (f.exists()) f.delete()
        }
        prefs.subscriptions = prefs.subscriptions.filter { it.id != id }
        reconcileOrderAndSelection(emptyList(), sub.files)
        true
    }

    private fun fetch(url: String): String {
        val client = OkHttpClient.Builder().apply {
            if (isServiceEnabled()) {
                proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort)))
            }
        }.build()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Empty body")
        }
    }

    private fun uniqueFileName(subName: String, serverName: String, used: Set<String>): String {
        val safeSub = sanitize(subName)
        val safeServer = sanitize(serverName)
        val base = "$safeSub - $safeServer"
        var candidate = "$base.json"
        var i = 2
        while (candidate in used) {
            candidate = "$base ($i).json"
            i++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifEmpty { "server" }
    }

    private fun reconcileOrderAndSelection(newFiles: List<String>, removedGroupFiles: List<String>) {
        val filesDir = application.filesDir
        val actual = filesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.name }?.toMutableSet() ?: mutableSetOf()

        val order = prefs.configFilesOrder.toMutableList()
        order.removeAll { it !in actual }
        newFiles.forEach { if (it !in order && it in actual) order.add(it) }
        prefs.configFilesOrder = order

        val selected = prefs.selectedConfigPath
        if (selected != null) {
            val selectedName = File(selected).name
            if (selectedName !in actual) {
                prefs.selectedConfigPath = null
            }
        }
    }

    companion object {
        const val TAG = "SubscriptionManager"
    }
}
```

- [ ] **Step 3: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
export GIT_AUTHOR_NAME="IvanTopGaming" GIT_AUTHOR_EMAIL="zjarc0@mail.ru" \
       GIT_COMMITTER_NAME="IvanTopGaming" GIT_COMMITTER_EMAIL="zjarc0@mail.ru"
git add app/src/main/kotlin/com/simplexray/an/data/source/SubscriptionManager.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat: add subscription manager with fetch and sync"
```

---

### Task 7: Проводка в MainViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/simplexray/an/viewmodel/MainViewModel.kt` (поля, флоу, методы)
- Create: `app/src/main/kotlin/com/simplexray/an/viewmodel/SubscriptionSyncState.kt`

**Interfaces:**
- Consumes: `SubscriptionManager`, `Preferences.subscriptions`, существующие `refreshConfigFileList()`, `_configFiles`.
- Produces (в `MainViewModel`):
  - `val subscriptions: StateFlow<List<Subscription>>`
  - `val subscriptionSync: StateFlow<Map<String, SubscriptionSyncState>>`
  - `fun refreshSubscriptions()` — перечитать реестр из prefs в флоу
  - `fun addSubscription(name: String, url: String)`
  - `fun syncSubscription(id: String)`
  - `fun deleteSubscription(id: String)`
  - `val subscriptionByFile: StateFlow<Map<String, String>>` — имя файла → id подписки

- [ ] **Step 1: Создать модель статуса синхронизации**

`app/src/main/kotlin/com/simplexray/an/viewmodel/SubscriptionSyncState.kt`:

```kotlin
package com.simplexray.an.viewmodel

data class SubscriptionSyncState(
    val syncing: Boolean = false,
    val error: String? = null
)
```

- [ ] **Step 2: Добавить импорты в `MainViewModel.kt`**

Рядом с существующими импортами:

```kotlin
import com.simplexray.an.data.model.Subscription
import com.simplexray.an.data.source.SubscriptionManager
```

- [ ] **Step 3: Инициализировать менеджер и флоу**

После строки `private val fileManager: FileManager = FileManager(application, prefs)` (около строки 77) добавить:

```kotlin
    private val subscriptionManager: SubscriptionManager =
        SubscriptionManager(application, prefs) { _isServiceEnabled.value }

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _subscriptionSync = MutableStateFlow<Map<String, SubscriptionSyncState>>(emptyMap())
    val subscriptionSync: StateFlow<Map<String, SubscriptionSyncState>> =
        _subscriptionSync.asStateFlow()

    private val _subscriptionByFile = MutableStateFlow<Map<String, String>>(emptyMap())
    val subscriptionByFile: StateFlow<Map<String, String>> = _subscriptionByFile.asStateFlow()
```

Примечание: `_isServiceEnabled` — существующий backing StateFlow в этом ViewModel (используется в `downloadRuleFile`, строки ~963). Если его имя иное — использовать то же, что и там.

- [ ] **Step 4: Добавить методы (после `updateSelectedConfigFile`, около строки 752)**

```kotlin
    fun refreshSubscriptions() {
        val subs = prefs.subscriptions
        _subscriptions.value = subs
        _subscriptionByFile.value = buildMap {
            subs.forEach { sub -> sub.files.forEach { put(it, sub.id) } }
        }
    }

    fun addSubscription(name: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tempId = "pending"
            setSync(tempId, SubscriptionSyncState(syncing = true))
            val result = subscriptionManager.add(name, url)
            clearSync(tempId)
            refreshSubscriptions()
            refreshConfigFileList()
            result.exceptionOrNull()?.let { e ->
                _subscriptions.value.lastOrNull()?.let {
                    setSync(it.id, SubscriptionSyncState(error = e.message))
                }
            }
        }
    }

    fun syncSubscription(id: String) {
        if (_subscriptionSync.value[id]?.syncing == true) return
        viewModelScope.launch(Dispatchers.IO) {
            setSync(id, SubscriptionSyncState(syncing = true))
            val result = subscriptionManager.refresh(id)
            setSync(id, SubscriptionSyncState(error = result.exceptionOrNull()?.message))
            refreshSubscriptions()
            refreshConfigFileList()
        }
    }

    fun deleteSubscription(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            subscriptionManager.delete(id)
            clearSync(id)
            refreshSubscriptions()
            refreshConfigFileList()
        }
    }

    private fun setSync(id: String, state: SubscriptionSyncState) {
        _subscriptionSync.value = _subscriptionSync.value.toMutableMap().apply { put(id, state) }
    }

    private fun clearSync(id: String) {
        _subscriptionSync.value = _subscriptionSync.value.toMutableMap().apply { remove(id) }
    }
```

- [ ] **Step 5: Вызвать `refreshSubscriptions()` в существующем `init`**

Найти блок `init { ... refreshConfigFileList() ... }` (около строки 179) и добавить в него строку:

```kotlin
            refreshSubscriptions()
```

- [ ] **Step 6: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Если компилятор ругается на имя `_isServiceEnabled` — заменить на фактическое имя backing-поля сервиса из этого файла (проверить `grep -n "_isServiceEnabled\|isServiceEnabled" MainViewModel.kt`).

- [ ] **Step 7: Commit**

```bash
export GIT_AUTHOR_NAME="IvanTopGaming" GIT_AUTHOR_EMAIL="zjarc0@mail.ru" \
       GIT_COMMITTER_NAME="IvanTopGaming" GIT_COMMITTER_EMAIL="zjarc0@mail.ru"
git add app/src/main/kotlin/com/simplexray/an/viewmodel/MainViewModel.kt \
        app/src/main/kotlin/com/simplexray/an/viewmodel/SubscriptionSyncState.kt
git commit -m "feat: wire subscriptions into main view model"
```

---

### Task 8: UI — секция подписок + диалог добавления

**Files:**
- Create: `app/src/main/kotlin/com/simplexray/an/ui/screens/SubscriptionSection.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MainViewModel.subscriptions`, `MainViewModel.subscriptionSync`, `addSubscription`, `syncSubscription`, `deleteSubscription`, `Subscription`.
- Produces:
  - `@Composable fun SubscriptionCard(sub, syncState, onSync, onDelete)`
  - `@Composable fun AddSubscriptionDialog(onDismiss, onConfirm)`

- [ ] **Step 1: Добавить UI-строки в `strings.xml`**

Перед `</resources>`:

```xml
    <string name="subscriptions">Subscriptions</string>
    <string name="add_subscription">Add subscription</string>
    <string name="subscription_name">Name</string>
    <string name="subscription_url">Subscription URL</string>
    <string name="delete_subscription">Delete subscription</string>
    <string name="subscription_servers_count">%1$d servers</string>
    <string name="subscription_never_updated">Never updated</string>
    <string name="subscription_updated_at">Updated %1$s</string>
    <string name="manual_configs">Manual</string>
```

- [ ] **Step 2: Реализовать композаблы секции**

`app/src/main/kotlin/com/simplexray/an/ui/screens/SubscriptionSection.kt`:

```kotlin
package com.simplexray.an.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplexray.an.R
import com.simplexray.an.data.model.Subscription
import com.simplexray.an.viewmodel.SubscriptionSyncState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SubscriptionCard(
    sub: Subscription,
    syncState: SubscriptionSyncState?,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    sub.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = syncState?.error
                    ?: stringResource(R.string.subscription_servers_count, sub.files.size) +
                    " · " + formatUpdated(sub.lastUpdated)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncState?.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (syncState?.syncing == true) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = 4.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = onSync) {
                    Icon(painterResource(R.drawable.refresh), contentDescription = "Refresh")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.delete), contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun formatUpdated(lastUpdated: Long): String {
    return if (lastUpdated == 0L) {
        stringResource(R.string.subscription_never_updated)
    } else {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        stringResource(R.string.subscription_updated_at, sdf.format(Date(lastUpdated)))
    }
}

@Composable
fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_subscription)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.subscription_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.subscription_url)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = { onConfirm(name.trim(), url.trim()) }
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
```

- [ ] **Step 3: Проверить наличие иконки refresh**

Run: `ls app/src/main/res/drawable/ | grep -E "refresh|reload|sync"`
Expected: есть `refresh.xml` (или аналог). Если нет — использовать существующую (напр. `R.drawable.edit` заменить на подходящую из `ls app/src/main/res/drawable/`), либо добавить vector drawable `refresh.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:tint="?attr/colorControlNormal">
    <path android:fillColor="@android:color/white"
        android:pathData="M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"/>
</vector>
```

- [ ] **Step 4: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
export GIT_AUTHOR_NAME="IvanTopGaming" GIT_AUTHOR_EMAIL="zjarc0@mail.ru" \
       GIT_COMMITTER_NAME="IvanTopGaming" GIT_COMMITTER_EMAIL="zjarc0@mail.ru"
git add app/src/main/kotlin/com/simplexray/an/ui/screens/SubscriptionSection.kt \
        app/src/main/res/values/strings.xml app/src/main/res/drawable/
git commit -m "feat: add subscription card and add dialog composables"
```

---

### Task 9: Интеграция секции подписок и группировки в ConfigScreen

**Files:**
- Modify: `app/src/main/kotlin/com/simplexray/an/ui/screens/ConfigScreen.kt`

**Interfaces:**
- Consumes: `SubscriptionCard`, `AddSubscriptionDialog`, `MainViewModel.subscriptions`, `subscriptionSync`, `subscriptionByFile`, `addSubscription`, `syncSubscription`, `deleteSubscription`.
- Изменяет поведение: серверы группируются заголовками подписок + секция «Свои»; у серверов подписки нет кнопки удаления; reorder только для «Свои».

- [ ] **Step 1: Собрать данные подписок в `ConfigScreen`**

В начале `ConfigScreen`, после существующих `collectAsState()` (строки 63-66), добавить:

```kotlin
    val subscriptions by mainViewModel.subscriptions.collectAsState()
    val subscriptionSync by mainViewModel.subscriptionSync.collectAsState()
    val subscriptionByFile by mainViewModel.subscriptionByFile.collectAsState()
    val showAddSubscriptionDialog = remember { mutableStateOf(false) }
    val showDeleteSubDialog = remember { mutableStateOf<com.simplexray.an.data.model.Subscription?>(null) }
```

И в `DisposableEffect`/`LaunchedEffect(Unit)` (строки 70-84), где вызывается `refreshConfigFileList()`, добавить рядом `mainViewModel.refreshSubscriptions()`.

- [ ] **Step 2: Разбить файлы на группы**

Перед `Column(...)` (строка 92) добавить вычисление групп:

```kotlin
    val manualFiles = files.filter { it.name !in subscriptionByFile.keys }
    val filesBySub: Map<String, List<File>> = subscriptions.associate { sub ->
        sub.id to files.filter { subscriptionByFile[it.name] == sub.id }
    }
```

- [ ] **Step 3: Перестроить `LazyColumn` — подписки, группы серверов, ручные**

Заменить тело `LazyColumn` (строки 110-176, блок `items(files, ...) { ... }`) на:

```kotlin
            LazyColumn(
                modifier = Modifier.fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 10.dp, top = 10.dp),
                state = listState
            ) {
                item(key = "subs_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.subscriptions),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = { showAddSubscriptionDialog.value = true }) {
                            Icon(painterResource(R.drawable.add), contentDescription = "Add")
                        }
                    }
                }

                items(subscriptions, key = { "sub_" + it.id }) { sub ->
                    SubscriptionCard(
                        sub = sub,
                        syncState = subscriptionSync[sub.id],
                        onSync = { mainViewModel.syncSubscription(sub.id) },
                        onDelete = { showDeleteSubDialog.value = sub }
                    )
                }

                subscriptions.forEach { sub ->
                    val subFiles = filesBySub[sub.id].orEmpty()
                    if (subFiles.isNotEmpty()) {
                        item(key = "subgroup_" + sub.id) {
                            GroupHeader(sub.name)
                        }
                        items(subFiles, key = { it.absolutePath }) { file ->
                            ConfigRow(
                                file = file,
                                isSelected = file == selectedFile,
                                isServiceEnabled = isServiceEnabled,
                                showDelete = false,
                                onSelect = {
                                    mainViewModel.updateSelectedConfigFile(file)
                                    if (isServiceEnabled) onReloadConfig()
                                },
                                onEdit = { onEditConfigClick(file) },
                                onDelete = {}
                            )
                        }
                    }
                }

                if (manualFiles.isNotEmpty()) {
                    item(key = "manual_header") {
                        GroupHeader(stringResource(R.string.manual_configs))
                    }
                    items(manualFiles, key = { it.absolutePath }) { file ->
                        ConfigRow(
                            file = file,
                            isSelected = file == selectedFile,
                            isServiceEnabled = isServiceEnabled,
                            showDelete = true,
                            onSelect = {
                                mainViewModel.updateSelectedConfigFile(file)
                                if (isServiceEnabled) onReloadConfig()
                            },
                            onEdit = { onEditConfigClick(file) },
                            onDelete = { showDeleteDialog.value = file }
                        )
                    }
                }
            }
```

Примечание по reorder (важно): drag-reorder в v1 **убираем**. Причина: с заголовками-элементами внутри `LazyColumn` индексы `from.index`/`to.index` в `rememberReorderableLazyListState` считаются по позициям LazyColumn, а `moveConfigFile` двигает по `_configFiles` (только файлы) — индексы не совпадают, перетаскивание сломается. Поэтому:
- Удалить блок `rememberReorderableLazyListState { ... }` (строки 87-90) и импорты `sh.calvin.reorderable.*`, `HapticFeedbackType`, `LocalHapticFeedback` если станут неиспользуемыми (компилятор подскажет предупреждением; ошибок не будет, но лучше почистить).
- `ConfigRow` больше не принимает `dragHandleModifier` — параметр удалить из сигнатуры в Step 4.

Reorder ручных конфигов с корректным маппингом индексов (offset на заголовки/подписки) — отдельная follow-up доработка.

- [ ] **Step 4: Вынести переиспользуемые `GroupHeader` и `ConfigRow`**

В конец файла `ConfigScreen.kt` добавить:

```kotlin
@Composable
private fun GroupHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ConfigRow(
    file: File,
    isSelected: Boolean,
    isServiceEnabled: Boolean,
    showDelete: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    file.name.removeSuffix(".json"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onEdit) {
                    Icon(painterResource(R.drawable.edit), contentDescription = "Edit")
                }
                if (showDelete) {
                    IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.delete), contentDescription = "Delete")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Добавить диалоги подписок в конец composable `ConfigScreen`**

После существующего блока `showDeleteDialog.value?.let { ... }` (строки 179-201) добавить:

```kotlin
    if (showAddSubscriptionDialog.value) {
        AddSubscriptionDialog(
            onDismiss = { showAddSubscriptionDialog.value = false },
            onConfirm = { name, url ->
                showAddSubscriptionDialog.value = false
                mainViewModel.addSubscription(name, url)
            }
        )
    }

    showDeleteSubDialog.value?.let { sub ->
        AlertDialog(
            onDismissRequest = { showDeleteSubDialog.value = null },
            title = { Text(stringResource(R.string.delete_subscription)) },
            text = { Text(sub.name) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSubDialog.value = null
                    mainViewModel.deleteSubscription(sub.id)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSubDialog.value = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
```

- [ ] **Step 6: Добавить недостающие импорты в `ConfigScreen.kt`**

Убедиться, что импортированы: `androidx.compose.material3.OutlinedTextField` (не нужен здесь), `com.simplexray.an.data.model.Subscription` (использован через полный путь — ок), `stringResource` (уже есть), `R` (есть). Проверить, что `R.drawable.add` существует:

Run: `ls app/src/main/res/drawable/ | grep -E "^add"`
Expected: есть `add.xml`. Если нет — использовать существующую иконку добавления (проверить `ls app/src/main/res/drawable/`).

- [ ] **Step 7: Компиляция**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Полная сборка**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, APK собран.

- [ ] **Step 9: Commit**

```bash
export GIT_AUTHOR_NAME="IvanTopGaming" GIT_AUTHOR_EMAIL="zjarc0@mail.ru" \
       GIT_COMMITTER_NAME="IvanTopGaming" GIT_COMMITTER_EMAIL="zjarc0@mail.ru"
git add app/src/main/kotlin/com/simplexray/an/ui/screens/ConfigScreen.kt
git commit -m "feat: group subscription servers and add subscription section in config screen"
```

---

### Task 10: Ручная проверка на устройстве

**Files:** нет (валидация).

- [ ] **Step 1: Установить и проверить сценарии**

Run: `./gradlew :app:installDebug`

Проверить вручную:
1. Экран Config → «+» в секции Subscriptions → ввести имя + рабочий subscription URL → сервера появляются под заголовком подписки.
2. Кол-во серверов и «Updated …» на карточке корректны.
3. У серверов подписки **нет** кнопки удаления; у ручных — есть.
4. 🔄 обновляет подписку (спиннер → новый список), старые серверы группы заменяются, ручные конфиги не тронуты.
5. Битый URL → на карточке ошибка, существующие сервера целы.
6. Удаление подписки удаляет её сервера, ручные не тронуты.
7. Выбор сервера подписки и коннект работают; при удалении выбранного рефрешем — выбор сбрасывается.
8. Ручной импорт `vmess://` / `ss://` / `trojan://` из буфера создаёт валидный конфиг.

- [ ] **Step 2: Зафиксировать результат**

Если всё ок — фича готова. Найденные баги — новые задачи/итерация.

---

## Notes / Deviations from Spec

- **Роутинг в сгенерированных конфигах:** спек говорил «полный конфиг с template-роутингом», но фактическое поведение существующего `VlessLinkConverter` — минимальный конфиг **без** routing. Парсеры повторяют это (консистентность с текущим импортом). Template-роутинг — тема отдельной фичи «правила роутинга».
- **Редактирование сервера подписки** оставлено доступным (кнопка Edit есть), удаление — заблокировано. Правки сервера затираются при следующем рефреше подписки.

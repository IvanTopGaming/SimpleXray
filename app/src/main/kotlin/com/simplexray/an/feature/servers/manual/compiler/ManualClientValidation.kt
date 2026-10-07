package com.simplexray.an.feature.servers.manual.compiler

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.util.Base64
import java.util.Locale

internal object ManualClientValidation {
    fun validate(protocol: String, settings: JsonObject, stream: JsonObject?) {
        if (protocol != "wireguard") address(settings.text("address"), "Адрес сервера")
        when (protocol) {
            "vless",
            "vmess" ->
                check(
                    validId(settings.text("id")),
                    "ID пользователя",
                    "нужен UUID или строка до 30 байт",
                )
            "wireguard" -> wireguard(settings)
            "shadowsocks" -> shadowsocks(settings)
        }
        if (protocol == "vless") {
            val encrypted = vlessEncryption(settings.text("encryption"))
            if (settings.text("flow").isNotEmpty() && !encrypted) {
                check(
                    stream != null &&
                        stream.text("network") in listOf("", "raw", "tcp") &&
                        stream.text("security") in listOf("tls", "reality"),
                    "Flow",
                    "Vision требует RAW с TLS или REALITY",
                )
                stream?.obj("tlsSettings")?.let {
                    check(
                        it.text("maxVersion") in listOf("", "1.3"),
                        "Максимальная версия TLS",
                        "Vision требует TLS 1.3",
                    )
                }
            }
        }
        if (protocol == "hysteria") {
            check(
                stream?.text("network") == "hysteria",
                "Транспорт",
                "Hysteria 2 требует транспорт Hysteria 2",
            )
        }
        stream?.let(::stream)
    }

    private fun stream(value: JsonObject) {
        value.text("address").takeIf { it.isNotEmpty() }?.let { address(it, "Адрес транспорта") }
        val network = value.text("network")
        val security = value.text("security")
        if (network == "hysteria") check(security == "tls", "Защита", "Hysteria 2 требует TLS")
        if (security == "reality") {
            check(
                network in listOf("", "raw", "tcp", "xhttp", "grpc"),
                "Транспорт",
                "REALITY требует RAW, XHTTP или gRPC",
            )
            reality(value.obj("realitySettings") ?: invalid("REALITY", "заполни параметры"))
        }
        value.obj("tlsSettings")?.let(::tls)
        value.obj("xhttpSettings")?.let(::xhttp)
        value.obj("kcpSettings")?.let {
            val mtu = it["mtu"]?.asLong ?: 1350L
            val window = it["maxSendingWindow"]?.asLong ?: 2_097_152L
            check(window >= mtu, "Максимальное окно отправки", "размер должен быть не меньше MTU")
        }
        value.obj("httpupgradeSettings")?.obj("headers")?.let(::noHostHeader)
        value.obj("finalmask")?.let { mask ->
            for (transport in listOf("tcp", "udp")) {
                mask.list(transport).forEach { element ->
                    val entry = element.asJsonObject
                    val settings = entry.obj("settings") ?: JsonObject()
                    when (entry.text("type")) {
                        "sudoku" -> sudoku(settings)
                        "udphop" -> {
                            check(
                                settings.text("mode").split(',').all {
                                    it.lowercase(Locale.ROOT) in
                                        listOf("intervallocal", "intervalremote", "perconnremote")
                                },
                                "Режим смены адреса",
                                "используй intervalLocal, intervalRemote или perConnRemote через запятую",
                            )
                            val interval = range(settings, "interval", "Интервал смены портов")
                            check(
                                interval == (0L to 0L) || interval.first >= 5,
                                "Интервал смены портов",
                                "нужен ноль или минимум 5 секунд",
                            )
                        }
                        "header-custom" -> {
                            if (transport == "tcp") {
                                for (key in listOf("clients", "servers")) settings
                                    .list(key)
                                    .forEach { sequence ->
                                        sequence.asJsonArray.forEach { packet(it.asJsonObject) }
                                    }
                            } else
                                for (key in listOf("client", "server")) settings.list(key).forEach {
                                    packet(it.asJsonObject)
                                }
                        }
                        "noise" -> {
                            range(settings, "reset", "Период сброса", 0)
                            settings.list("noise").forEach { packet(it.asJsonObject) }
                        }
                        "fragment" -> {
                            range(settings, "length", "Размер фрагмента", 1)
                            range(settings, "delay", "Задержка", 0)
                            range(settings, "maxSplit", "Число частей", 0)
                            val packets = settings.text("packets")
                            if (packets !in listOf("", "tlshello")) {
                                val parsed = parseRange(packets, "Фрагментируемые пакеты")
                                check(
                                    parsed.first > 0 && parsed.second <= Int.MAX_VALUE,
                                    "Фрагментируемые пакеты",
                                    "нужен положительный диапазон пакетов",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun wireguard(settings: JsonObject) {
        wireguardKey(settings.text("secretKey"), "Приватный ключ")
        settings.list("remoteDNS").forEach {
            check(
                ipBits(it.asString) != null,
                "DNS внутри WireGuard",
                "нужен IPv4 или IPv6 без порта",
            )
        }
        settings.list("address").forEach { element ->
            val value = element.asString
            val parts = value.split('/')
            val bits = ipBits(parts.first())
            check(
                bits != null &&
                    parts.size <= 2 &&
                    (parts.size == 1 || parts[1].toIntOrNull() in 0..bits),
                "Адреса интерфейса",
                "нужен IP или адрес с корректной маской CIDR",
            )
        }
        settings.list("peers").forEach { element ->
            val peer = element.asJsonObject
            wireguardKey(peer.text("publicKey"), "Публичный ключ")
            peer
                .text("preSharedKey")
                .takeIf { it.isNotEmpty() }
                ?.let { wireguardKey(it, "Общий секрет") }
            val endpoint = peer.text("endpoint")
            val host: String
            val port: String
            if (endpoint.startsWith('[')) {
                val close = endpoint.indexOf(']')
                check(
                    close > 1 && endpoint.getOrNull(close + 1) == ':',
                    "Адрес пира",
                    "нужен адрес и порт",
                )
                host = endpoint.substring(1, close)
                port = endpoint.substring(close + 2)
                check(ipBits(host) == 128, "Адрес пира", "нужен IPv6 в квадратных скобках")
            } else {
                check(endpoint.count { it == ':' } == 1, "Адрес пира", "нужен адрес и порт")
                host = endpoint.substringBefore(':')
                port = endpoint.substringAfter(':')
            }
            address(host, "Адрес пира")
            check(port.toIntOrNull() in 1..65535, "Адрес пира", "нужен порт от 1 до 65535")
            peer.list("allowedIPs").forEach { network ->
                val parts = network.asString.split('/')
                val bits = ipBits(parts.first())
                val prefix = parts.getOrNull(1)?.toIntOrNull()
                check(
                    parts.size == 2 && bits != null && prefix != null && prefix in 0..bits,
                    "Разрешённые сети",
                    "нужна сеть IPv4 или IPv6 в CIDR",
                )
            }
        }
    }

    private fun wireguardKey(value: String, label: String) {
        if (value.length == 64 && value.all(::hexDigit)) return
        val raw = value.removeSuffix("=")
        val url = '+' !in raw && '/' !in raw
        check(
            decodeBase64(raw, url, false)?.size == 32,
            label,
            "нужен ключ из 32 байт в Base64 или HEX",
        )
    }

    private fun shadowsocks(settings: JsonObject) {
        val method = settings.text("method")
        if (!method.startsWith("2022-")) return
        val passwords = settings.text("password").split(':')
        check(
            method != "2022-blake3-chacha20-poly1305" || passwords.size == 1,
            "Пароль или ключ",
            "ChaCha20 SS2022 допускает один ключ",
        )
        val minimum = if (method == "2022-blake3-aes-128-gcm") 16 else 32
        check(
            passwords.all { (decodeBase64(it, false, true)?.size ?: 0) >= minimum },
            "Пароль или ключ",
            "нужны ключи Base64 достаточной длины",
        )
    }

    private fun vlessEncryption(value: String): Boolean {
        if (value == "none") return false
        val label = "Шифрование VLESS"
        val parts = value.split('.')
        check(
            parts.size >= 4 &&
                parts[0] == "mlkem768x25519plus" &&
                parts[1] in listOf("native", "xorpub", "random") &&
                parts[2] in listOf("1rtt", "0rtt"),
            label,
            "проверь формат режима и ключей",
        )
        val parameters = parts.drop(3)
        val padding = parameters.takeWhile { it.length < 20 }
        val keys = parameters.drop(padding.size)
        check(
            keys.isNotEmpty() &&
                keys.all { decodeBase64(it, true, false)?.size in listOf(32, 1184) },
            label,
            "нужны ключи сервера Base64URL",
        )
        var total = 0L
        padding.forEachIndexed { index, parameter ->
            val numbers = parameter.split('-').map { it.toLongOrNull() }
            check(
                numbers.size >= 3 &&
                    numbers.take(3).all { it != null && it >= 0 && it <= Int.MAX_VALUE },
                label,
                "проверь параметры заполнения",
            )
            val chance = numbers[0]!!
            val minimum = numbers[1]!!
            val maximum = numbers[2]!!
            check(
                index != 0 || chance >= 100 && minimum >= 35 && maximum >= 35,
                label,
                "первый размер заполнения должен быть от 35",
            )
            if (index % 2 == 0) total += maxOf(minimum, maximum)
        }
        check(total <= 65553, label, "слишком большое заполнение")
        return true
    }

    private fun reality(settings: JsonObject) {
        check(
            settings.text("fingerprint").lowercase(Locale.ROOT) !in
                listOf("", "unsafe", "hellogolang"),
            "TLS-отпечаток",
            "выбери отпечаток для REALITY",
        )
        check(
            decodeBase64(settings.text("password"), true, false)?.size == 32,
            "Пароль",
            "нужен публичный ключ REALITY Base64URL из 32 байт",
        )
        val shortId = settings.text("shortId")
        check(
            shortId.length <= 16 && shortId.length % 2 == 0 && shortId.all(::hexDigit),
            "Короткий ID",
            "нужно до 16 символов HEX, чётное число",
        )
        settings
            .text("mldsa65Verify")
            .takeIf { it.isNotEmpty() }
            ?.let {
                check(
                    decodeBase64(it, true, false)?.size == 1952,
                    "Ключ ML-DSA-65",
                    "нужен ключ Base64URL из 1952 байт",
                )
            }
        settings
            .text("spiderX")
            .takeIf { it.isNotEmpty() }
            ?.let {
                check(
                    it.startsWith('/') && runCatching { URI(it) }.isSuccess,
                    "Путь обходчика",
                    "нужен корректный путь, начинающийся с /",
                )
            }
    }

    private fun tls(settings: JsonObject) {
        val minimum = settings.text("minVersion")
        val maximum = settings.text("maxVersion")
        check(
            minimum.isEmpty() || maximum.isEmpty() || minimum <= maximum,
            "Версия TLS",
            "минимум больше максимума",
        )
        val alpn = settings.list("alpn")
        check(
            alpn.none { it.asString == "fromMitm" } || alpn.size == 1,
            "Протоколы ALPN",
            "fromMitm должен быть единственным протоколом",
        )
        settings.list("certificates").forEach {
            val certificate = it.asJsonObject
            check(
                certificate.text("certificateFile").isNotEmpty() ||
                    certificate.list("certificate").any { line -> line.asString.isNotEmpty() },
                "Сертификаты доверия",
                "добавь файл или сертификат PEM",
            )
        }
        settings
            .text("pinnedPeerCertSha256")
            .split(',')
            .filter { it.isNotBlank() }
            .forEach {
                val hash = it.trim().replace(":", "")
                check(
                    hash.length == 64 && hash.all(::hexDigit),
                    "SHA-256 сертификатов",
                    "нужен SHA-256 из 32 байт в HEX",
                )
            }
    }

    private fun xhttp(settings: JsonObject) {
        val effective = settings.obj("extra") ?: settings
        val mode = settings.text("mode").ifEmpty { "auto" }
        effective.obj("headers")?.let(::noHostHeader)
        check(
            effective.text("uplinkHTTPMethod").uppercase(Locale.ROOT) != "GET" ||
                mode == "packet-up",
            "Метод отправки",
            "GET требует packet-up",
        )
        check(
            effective.text("uplinkDataPlacement") !in listOf("cookie", "header") ||
                mode == "packet-up",
            "Расположение данных",
            "cookie и header требуют packet-up",
        )
        check(
            mode != "stream-one" || effective.obj("downloadSettings") == null,
            "Транспорт скачивания",
            "несовместим со stream-one",
        )
        effective.obj("xmux")?.let {
            check(
                range(it, "maxConnections", "Число соединений").second <= 0 ||
                    range(it, "maxConcurrency", "Параллельные запросы").second <= 0,
                "Мультиплексирование XHTTP",
                "выбери число соединений или параллельные запросы",
            )
        }
        val padding = range(effective, "xPaddingBytes", "Размер заполнения")
        check(
            padding == (0L to 0L) || padding.first > 0,
            "Размер заполнения",
            "заполнение должно быть больше нуля",
        )
        for ((key, label) in
            listOf(
                "uplinkChunkSize" to "Размер блока данных",
                "scMaxEachPostBytes" to "Размер POST",
                "scMinPostsIntervalMs" to "Интервал POST",
            )) {
            range(effective, key, label)
        }
        effective.obj("downloadSettings")?.let(::stream)
    }

    private fun noHostHeader(headers: JsonObject) {
        check(
            headers.keySet().none { it.equals("host", true) },
            "Заголовки",
            "укажи Host в отдельном поле HTTP-хоста",
        )
    }

    private fun sudoku(settings: JsonObject) {
        if (settings.text("ascii") !in listOf("ascii", "prefer_ascii")) {
            val tables =
                settings
                    .list("customTables")
                    .map { it.asString }
                    .ifEmpty { listOf(settings.text("customTable")) }
            tables.forEach {
                val table = it.trim().lowercase(Locale.ROOT).replace(" ", "")
                check(
                    table.isEmpty() ||
                        table.length == 8 &&
                            table.count { c -> c == 'x' } == 2 &&
                            table.count { c -> c == 'p' } == 2 &&
                            table.count { c -> c == 'v' } == 4,
                    "Таблица кодирования",
                    "нужны 2 x, 2 p и 4 v",
                )
            }
        }
        check(
            settings.number("paddingMin") <= settings.number("paddingMax"),
            "Заполнение Sudoku",
            "минимум больше максимума",
        )
    }

    private fun packet(settings: JsonObject) {
        val random = range(settings, "rand", "Случайные байты", 0)
        range(settings, "randRange", "Диапазон байтов", 0, 255)
        range(settings, "delay", "Задержка", 0)
        val packet = settings["packet"] ?: return
        val bytes =
            when (settings.text("type")) {
                "hex" -> {
                    val value = packet.asString
                    check(
                        value.length % 2 == 0 && value.all(::hexDigit),
                        "Содержимое пакета",
                        "нужны байты HEX",
                    )
                    value.length / 2
                }
                "base64" ->
                    decodeBase64(packet.asString, false, true)?.size
                        ?: invalid("Содержимое пакета", "нужен Base64")
                "str" -> packet.asString.toByteArray(Charsets.UTF_8).size
                else -> packet.asJsonArray.size()
            }
        check(
            bytes == 0 || random.second <= 0,
            "Содержимое пакета",
            "нельзя задавать вместе со случайными байтами",
        )
    }

    private fun range(
        settings: JsonObject,
        key: String,
        label: String,
        minimum: Long = Int.MIN_VALUE.toLong(),
        maximum: Long = Int.MAX_VALUE.toLong(),
    ): Pair<Long, Long> {
        val value = settings[key] ?: return 0L to 0L
        val range = parseRange(value.asString, label)
        check(
            range.first >= minimum && range.second <= maximum,
            label,
            "диапазон выходит за допустимые границы",
        )
        return range
    }

    private fun parseRange(value: String, label: String): Pair<Long, Long> {
        val match =
            Regex("(-?\\d+)(?:-(-?\\d+))?").matchEntire(value)
                ?: invalid(label, "некорректный диапазон")
        val first = match.groupValues[1].toLongOrNull() ?: invalid(label, "слишком большое число")
        val last =
            if (match.groupValues[2].isEmpty()) first
            else match.groupValues[2].toLongOrNull() ?: invalid(label, "слишком большое число")
        return first to last
    }

    private fun validId(value: String): Boolean {
        val size = value.toByteArray(Charsets.UTF_8).size
        if (size in 1..30) return true
        if (size !in 32..36) return false
        var offset = 0
        for (length in listOf(8, 4, 4, 4, 12)) {
            if (value.getOrNull(offset) == '-') offset++
            val part =
                value.substring(
                    offset.coerceAtMost(value.length),
                    (offset + length).coerceAtMost(value.length),
                )
            if (part.length != length || !part.all(::hexDigit)) return false
            offset += length
        }
        return true
    }

    private fun ipBits(value: String): Int? {
        if (validIpv4(value)) return 32
        if (':' !in value || value.any { it !in "0123456789abcdefABCDEF:." }) return null
        val halves = value.split("::")
        if (halves.size > 2) return null
        val groups = halves.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
        var count = 0
        for ((index, group) in groups.withIndex()) {
            if ('.' in group) {
                if (index != groups.lastIndex || !value.endsWith(group) || !validIpv4(group))
                    return null
                count += 2
            } else {
                if (group.length !in 1..4 || !group.all(::hexDigit)) return null
                count++
            }
        }
        return if (halves.size == 2 && count < 8 || halves.size == 1 && count == 8) 128 else null
    }

    private fun validIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 &&
            parts.all {
                it.isNotEmpty() &&
                    it.all { c -> c in '0'..'9' } &&
                    (it.length == 1 || it[0] != '0') &&
                    it.toIntOrNull() in 0..255
            }
    }

    private fun address(value: String, label: String) {
        check(
            value.isNotBlank() &&
                value.none { it.isWhitespace() || it.isISOControl() } &&
                '/' !in value,
            label,
            "нужен домен или IP без пробелов",
        )
    }

    private fun decodeBase64(value: String, url: Boolean, padded: Boolean): ByteArray? {
        val clean = value.replace("\r", "").replace("\n", "")
        if (padded && clean.length % 4 != 0 || !padded && '=' in clean) return null
        return runCatching {
                (if (url) Base64.getUrlDecoder() else Base64.getDecoder()).decode(clean)
            }
            .getOrNull()
    }

    private fun hexDigit(value: Char) = value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

    private fun JsonObject.text(key: String) = get(key)?.asString.orEmpty()

    private fun JsonObject.number(key: String) = get(key)?.asLong ?: 0L

    private fun JsonObject.obj(key: String) = getAsJsonObject(key)

    private fun JsonObject.list(key: String): List<JsonElement> =
        getAsJsonArray(key)?.toList().orEmpty()

    private fun check(condition: Boolean, label: String, message: String) {
        if (!condition) invalid(label, message)
    }

    private fun invalid(label: String, message: String): Nothing =
        throw IllegalArgumentException("$label: $message")
}

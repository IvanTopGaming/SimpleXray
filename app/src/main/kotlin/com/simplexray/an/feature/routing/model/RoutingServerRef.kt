package com.simplexray.an.feature.routing.model

data class RoutingServerRef(
    val fileName: String,
    val name: String,
    val subscriptionId: String? = null,
    val fingerprint: String = "",
    val matchFingerprint: Boolean = false,
) {
    fun validate() {
        require(
            fileName.isNotBlank() &&
                fileName.length <= 255 &&
                fileName.endsWith(".json") &&
                fileName.none { it == '/' || it == '\\' || it.isISOControl() }
        ) {
            "Некорректная ссылка на сервер. Выбери сервер заново"
        }
        require(name.isNotBlank() && name.length <= 512 && name.none(Char::isISOControl)) {
            "Некорректное название сервера. Выбери сервер заново"
        }
        require(
            subscriptionId == null || subscriptionId.isNotBlank() && subscriptionId.length <= 100
        ) {
            "Некорректная ссылка на подписку. Выбери сервер заново"
        }
        require(fingerprint.isEmpty() || fingerprint.matches(Regex("[a-fA-F0-9]{64}"))) {
            "Некорректная ссылка на сервер. Выбери сервер заново"
        }
        require(!matchFingerprint || subscriptionId != null && fingerprint.isNotEmpty()) {
            "Некорректная ссылка на сервер. Выбери сервер заново"
        }
    }
}

package com.simplexray.an.core.network.socks

fun socksAuthenticationEnabled(username: String, password: String): Boolean =
    username.isNotEmpty() && password.isNotEmpty()

package com.simplexray.an.core.geodata

object GeodataFiles {
    const val GEOIP = "geoip.dat"
    const val GEOSITE = "geosite.dat"

    private val subscriptionFile = Regex("sub-[A-Za-z0-9-]{1,100}-(geoip|geosite)\\.dat")
    private val subscriptionId = Regex("[A-Za-z0-9-]{1,100}")

    fun isKnown(name: String): Boolean =
        name == GEOIP || name == GEOSITE || subscriptionFile.matches(name)

    fun isSubscription(name: String): Boolean = subscriptionFile.matches(name)

    fun isGeoip(name: String): Boolean = name == GEOIP || isSubscription(name) && name.endsWith(GEOIP)

    fun forSubscription(id: String, base: String): String? {
        require(base == GEOIP || base == GEOSITE)
        return if (subscriptionId.matches(id)) "sub-$id-$base" else null
    }

    fun subscriptionPrefix(id: String): String = "sub-$id-"
}

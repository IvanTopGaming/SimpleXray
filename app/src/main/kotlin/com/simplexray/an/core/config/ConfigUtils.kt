package com.simplexray.an.core.config

import android.util.Log
import com.simplexray.an.prefs.Preferences
import org.json.JSONException
import org.json.JSONObject

object ConfigUtils {
    private const val TAG = "ConfigUtils"

    @Throws(JSONException::class)
    fun formatConfigContent(content: String): String {
        val jsonObject = JSONObject(content)
        jsonObject.optJSONObject("log")?.apply {
            if (has("access") && optString("access") != "none") {
                remove("access")
                Log.d(TAG, "Removed log.access")
            }
            if (has("error") && optString("error") != "none") {
                remove("error")
                Log.d(TAG, "Removed log.error")
            }
        }
        var formattedContent = jsonObject.toString(2)
        formattedContent = formattedContent.replace("\\/", "/")
        return formattedContent
    }

    @Throws(JSONException::class)
    fun injectStatsService(
        prefs: Preferences,
        configContent: String,
        trafficStatsEnabled: Boolean = true,
        preserveTrafficPolicy: Boolean = false,
    ): String {
        return injectStatsService(
            prefs.apiAddress,
            prefs.apiPort,
            configContent,
            trafficStatsEnabled,
            preserveTrafficPolicy,
        )
    }

    fun injectStatsService(
        address: String,
        port: Int,
        configContent: String,
        trafficStatsEnabled: Boolean = true,
        preserveTrafficPolicy: Boolean = false,
    ): String {
        val jsonObject = JSONObject(configContent)

        val apiObject = JSONObject()
        apiObject.put("tag", "api")
        apiObject.put("listen", "$address:$port")
        val servicesArray = org.json.JSONArray()
        servicesArray.put("StatsService")
        apiObject.put("services", servicesArray)

        jsonObject.put("api", apiObject)
        jsonObject.put("stats", JSONObject())

        val policyObject = jsonObject.optJSONObject("policy") ?: JSONObject()
        val systemObject = policyObject.optJSONObject("system") ?: JSONObject()
        listOf(
                "statsInboundUplink",
                "statsInboundDownlink",
                "statsOutboundUplink",
                "statsOutboundDownlink",
            )
            .forEach { key ->
                if (!preserveTrafficPolicy || !systemObject.has(key))
                    systemObject.put(key, trafficStatsEnabled)
            }
        policyObject.put("system", systemObject)

        jsonObject.put("policy", policyObject)

        return jsonObject.toString(2)
    }

    fun extractPortsFromJson(jsonContent: String): Set<Int> {
        val ports = mutableSetOf<Int>()
        try {
            val jsonObject = JSONObject(jsonContent)
            extractPortsRecursive(jsonObject, ports)
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing JSON for port extraction", e)
        }
        Log.d(TAG, "Extracted ports: $ports")
        return ports
    }

    private fun extractPortsRecursive(jsonObject: JSONObject, ports: MutableSet<Int>) {
        for (key in jsonObject.keys()) {
            when (val value = jsonObject.get(key)) {
                is Int -> {
                    if (value in 1..65535) {
                        ports.add(value)
                    }
                }

                is JSONObject -> {
                    extractPortsRecursive(value, ports)
                }

                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        if (item is JSONObject) {
                            extractPortsRecursive(item, ports)
                        }
                    }
                }
            }
        }
    }
}

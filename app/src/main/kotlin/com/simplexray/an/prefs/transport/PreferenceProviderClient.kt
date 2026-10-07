package com.simplexray.an.prefs.transport

import android.content.ContentResolver
import android.content.ContentValues
import android.util.Log
import com.simplexray.an.prefs.PrefsContract

internal class PreferenceProviderClient(private val contentResolver: ContentResolver) {
    fun read(key: String): Pair<String?, String?> {
        val uri = PrefsContract.PrefsEntry.CONTENT_URI.buildUpon().appendPath(key).build()
        try {
            contentResolver
                .query(
                    uri,
                    arrayOf(
                        PrefsContract.PrefsEntry.COLUMN_PREF_VALUE,
                        PrefsContract.PrefsEntry.COLUMN_PREF_TYPE,
                    ),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val valueColumnIndex =
                            cursor.getColumnIndex(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE)
                        val typeColumnIndex =
                            cursor.getColumnIndex(PrefsContract.PrefsEntry.COLUMN_PREF_TYPE)
                        val value =
                            if (valueColumnIndex != -1) cursor.getString(valueColumnIndex) else null
                        val type =
                            if (typeColumnIndex != -1) cursor.getString(typeColumnIndex) else null
                        return Pair(value, type)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading preference data for key: $key (${e.javaClass.simpleName})")
        }
        return Pair(null, null)
    }

    fun boolean(key: String, default: Boolean): Boolean {
        val (value, type) = read(key)
        if (value != null && "Boolean" == type) {
            return value.toBoolean()
        }
        return default
    }

    fun write(key: String, value: Any?) {
        val uri = PrefsContract.PrefsEntry.CONTENT_URI.buildUpon().appendPath(key).build()
        val values = ContentValues()
        when (value) {
            is String -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Int -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Boolean -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Long -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Float -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            else -> {
                if (value != null) {
                    Log.e(TAG, "Unsupported type for key: $key")
                    return
                }
                values.putNull(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE)
            }
        }
        try {
            val rows = contentResolver.update(uri, values, null, null)
            if (rows == 0) {
                Log.w(TAG, "Update failed or key not found for: $key")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting preference for key: $key (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "Preferences"
    }
}

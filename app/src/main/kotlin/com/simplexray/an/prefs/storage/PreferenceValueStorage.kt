package com.simplexray.an.prefs.storage

import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.util.Log
import com.simplexray.an.prefs.PrefsContract

internal class PreferenceValueStorage(private val prefs: SharedPreferences) {
    fun query(key: String?): Cursor {
        val cursor =
            MatrixCursor(
                arrayOf(
                    PrefsContract.PrefsEntry.COLUMN_PREF_KEY,
                    PrefsContract.PrefsEntry.COLUMN_PREF_VALUE,
                    PrefsContract.PrefsEntry.COLUMN_PREF_TYPE,
                )
            )
        if (key != null) {
            var value: Any? = null
            var type: String? = null
            if (prefs.contains(key)) {
                val valueAndType: Pair<Any?, String?>? =
                    try {
                        prefs.getString(key, null)?.let { v -> Pair<Any?, String?>(v, "String") }
                    } catch (e: ClassCastException) {
                        null
                    }
                        ?: try {
                            Pair<Any?, String?>(prefs.getBoolean(key, false), "Boolean")
                        } catch (e: ClassCastException) {
                            null
                        }
                        ?: try {
                            Pair<Any?, String?>(prefs.getInt(key, 0), "Integer")
                        } catch (e: ClassCastException) {
                            null
                        }
                        ?: try {
                            Pair<Any?, String?>(prefs.getLong(key, 0L), "Long")
                        } catch (e: ClassCastException) {
                            null
                        }
                        ?: try {
                            Pair<Any?, String?>(prefs.getFloat(key, 0f), "Float")
                        } catch (e: ClassCastException) {
                            null
                        }
                        ?: try {
                            prefs.getStringSet(key, null)?.let { v ->
                                Pair<Any?, String?>(v, "StringSet")
                            }
                        } catch (e: ClassCastException) {
                            Log.w(TAG, "Error retrieving key '$key' as StringSet, giving up", e)
                            null
                        }

                value = valueAndType?.first
                type = valueAndType?.second
            }
            if (value != null) {
                cursor.addRow(arrayOf<Any?>(key, value.toString(), type))
            }
        }
        return cursor
    }

    fun write(key: String, value: Any?) {
        val editor = prefs.edit()
        when (value) {
            null -> {
                editor.remove(key)
            }

            is String -> {
                editor.putString(key, value)
            }

            is Int -> {
                editor.putInt(key, value)
            }

            is Boolean -> {
                editor.putBoolean(key, value)
            }

            is Long -> {
                editor.putLong(key, value)
            }

            is Float -> {
                editor.putFloat(key, value)
            }

            is Set<*> -> {
                val stringSet = value.filterIsInstance<String>().toSet()
                if (stringSet.size == value.size) {
                    editor.putStringSet(key, stringSet)
                } else {
                    Log.e(
                        TAG,
                        "Value for key $key is a Set but contains non-String or null elements (putStringSet requires Set<String>).",
                    )
                }
            }

            else -> {
                Log.e(TAG, "Unsupported value type for key: $key")
            }
        }
        editor.apply()
    }

    companion object {
        private const val TAG = "PrefsProvider"
    }
}

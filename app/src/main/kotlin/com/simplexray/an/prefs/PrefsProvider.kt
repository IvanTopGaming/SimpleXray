package com.simplexray.an.prefs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.preference.PreferenceManager
import com.simplexray.an.prefs.routing.RoutingPreferencesStorage
import com.simplexray.an.prefs.storage.PreferenceValueStorage

class PrefsProvider : ContentProvider() {
    private lateinit var prefs: SharedPreferences
    private lateinit var routingStorage: RoutingPreferencesStorage
    private lateinit var valueStorage: PreferenceValueStorage

    override fun onCreate(): Boolean {
        prefs = PreferenceManager.getDefaultSharedPreferences(context!!)
        routingStorage = RoutingPreferencesStorage(context, prefs)
        valueStorage = PreferenceValueStorage(prefs)
        return true
    }

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Preferences are private")
        }
        return routingStorage.call(method, extras)
    }

    @Synchronized
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Preferences are private")
        }
        return routingStorage.openFile(uri, mode)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val match = sUriMatcher.match(uri)
        var key: String? = null
        if (match == PREFS_WITH_KEY) {
            key = uri.lastPathSegment
        } else if (match != PREFS) {
            throw UnsupportedOperationException("Unknown uri: $uri")
        }
        return valueStorage.query(key)
    }

    override fun getType(uri: Uri): String {
        val match = sUriMatcher.match(uri)
        return when (match) {
            PREFS -> PrefsContract.PrefsEntry.CONTENT_TYPE
            PREFS_WITH_KEY -> PrefsContract.PrefsEntry.CONTENT_ITEM_TYPE
            else -> throw UnsupportedOperationException("Unknown uri: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Insert not supported by this provider.")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("Delete not supported by this provider.")
    }

    @Synchronized
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        val match = sUriMatcher.match(uri)
        var rowsAffected = 0
        if (match == PREFS_WITH_KEY) {
            val key = uri.lastPathSegment
            if (
                key != null &&
                    values != null &&
                    values.containsKey(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE)
            ) {
                valueStorage.write(key, values[PrefsContract.PrefsEntry.COLUMN_PREF_VALUE])
                rowsAffected = 1
                val context = context
                context?.contentResolver?.notifyChange(uri, null)
            }
        } else {
            throw UnsupportedOperationException("Unknown uri for update: $uri")
        }
        return rowsAffected
    }

    companion object {
        private const val PREFS = 100
        private const val PREFS_WITH_KEY = 101
        private val sUriMatcher = buildUriMatcher()

        private fun buildUriMatcher(): UriMatcher {
            val matcher = UriMatcher(UriMatcher.NO_MATCH)
            val authority = PrefsContract.AUTHORITY
            matcher.addURI(authority, PrefsContract.PATH_PREFS, PREFS)
            matcher.addURI(authority, PrefsContract.PATH_PREFS + "/*", PREFS_WITH_KEY)
            return matcher
        }
    }
}

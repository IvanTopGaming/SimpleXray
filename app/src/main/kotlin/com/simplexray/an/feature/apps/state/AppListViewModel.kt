package com.simplexray.an.feature.apps.state

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplexray.an.R
import com.simplexray.an.feature.apps.model.AppRoutingClipboard
import com.simplexray.an.feature.apps.model.AppRoutingMode
import com.simplexray.an.prefs.Preferences
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListViewModel(application: Application) : AndroidViewModel(application) {
    val prefs = Preferences(getApplication<Application>().applicationContext)
    private val packageList = mutableStateListOf<Package>()
    var isLoading by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var showSystemApps by mutableStateOf(true)
    var appRoutingMode by mutableStateOf(prefs.appRoutingMode)
        private set

    private var selectedPackages = prefs.apps.orEmpty().filterNotNull().toSet()
    val selectionEnabled
        get() = appRoutingMode != AppRoutingMode.ALL && !prefs.disableVpn && !isLoading

    val selectionError by derivedStateOf {
        if (
            appRoutingMode == AppRoutingMode.INCLUDE &&
                !isLoading &&
                !hasSelectedApplications &&
                !prefs.disableVpn
        )
            "Выберите хотя бы одно приложение для VPN."
        else null
    }
    val hasSelectedApplications by derivedStateOf { packageList.any { it.selected } }

    private val _uiEvent = Channel<AppListViewUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    val filteredList by derivedStateOf {
        packageList.filter { pkg ->
            (showSystemApps || !pkg.isSystemApp) &&
                pkg.label
                    .lowercase(Locale.getDefault())
                    .contains(searchQuery.lowercase(Locale.getDefault()))
        }
    }

    init {
        loadAppList()
    }

    private fun loadAppList() {
        isLoading = true
        val pm = getApplication<Application>().packageManager
        val appPackageName = getApplication<Application>().packageName
        viewModelScope.launch(Dispatchers.IO) {
            var loadedPackages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            val startTime = System.currentTimeMillis()
            while (
                (loadedPackages.isEmpty() || loadedPackages.size == 1) &&
                    System.currentTimeMillis() - startTime < 10000
            ) {
                loadedPackages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                delay(500)
            }
            val list =
                loadedPackages
                    .asSequence()
                    .mapNotNull {
                        if (it.packageName == appPackageName) return@mapNotNull null
                        val appInfo = it.applicationInfo ?: return@mapNotNull null
                        val hasInternetPermission =
                            it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                        if (!hasInternetPermission) return@mapNotNull null
                        val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                        val label = appInfo.loadLabel(pm).toString()
                        val icon = appInfo.loadIcon(pm) ?: pm.defaultActivityIcon
                        Package(
                            selected = false,
                            label = label,
                            icon = icon,
                            packageName = it.packageName,
                            isSystemApp = isSystemApp,
                        )
                    }
                    .toList()
            withContext(Dispatchers.Main) {
                packageList.clear()
                packageList.addAll(
                    list
                        .map { it.copy(selected = it.packageName in selectedPackages) }
                        .sortedWith(
                            compareByDescending<Package> { it.selected }.thenBy { it.label }
                        )
                )
                isLoading = false
            }
        }
    }

    fun onPackageSelected(pkg: Package, isSelected: Boolean) {
        if (!selectionEnabled) return
        val index = packageList.indexOf(pkg)
        if (index != -1) {
            val updatedPackage = pkg.copy(selected = isSelected)
            packageList[index] = updatedPackage
            selectedPackages =
                if (isSelected) selectedPackages + pkg.packageName
                else selectedPackages - pkg.packageName
            prefs.apps = selectedPackages
        }
    }

    fun onSearchQueryChange(query: String) {
        searchQuery = query
    }

    fun onShowSystemAppsChange(show: Boolean) {
        showSystemApps = show
    }

    fun onAppRoutingModeChange(mode: AppRoutingMode) {
        appRoutingMode = mode
        prefs.appRoutingMode = mode
    }

    fun canLeaveScreen(): Boolean {
        val error =
            if (appRoutingMode == AppRoutingMode.INCLUDE && isLoading && !prefs.disableVpn)
                "Дождитесь загрузки списка приложений."
            else selectionError
        if (error != null) _uiEvent.trySend(AppListViewUiEvent.ShowSnackbar(error))
        return error == null
    }

    fun exportAppsToClipboard(context: Context) {
        val exportString = AppRoutingClipboard(appRoutingMode, selectedPackages).encode()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("App List", exportString))
        _uiEvent.trySend(
            AppListViewUiEvent.ShowSnackbar(context.getString(R.string.export_success))
        )
    }

    fun importAppsFromClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text =
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        if (text.isNullOrBlank()) {
            _uiEvent.trySend(
                AppListViewUiEvent.ShowSnackbar(context.getString(R.string.import_failed))
            )
            return
        }
        val imported =
            try {
                AppRoutingClipboard.decode(text)
            } catch (_: IllegalArgumentException) {
                _uiEvent.trySend(
                    AppListViewUiEvent.ShowSnackbar(
                        context.getString(R.string.import_invalid_format)
                    )
                )
                return
            }
        selectedPackages = imported.packages
        prefs.apps = selectedPackages
        onAppRoutingModeChange(imported.mode)
        for (index in packageList.indices) {
            val pkg = packageList[index]
            packageList[index] = pkg.copy(selected = pkg.packageName in selectedPackages)
        }
        _uiEvent.trySend(
            AppListViewUiEvent.ShowSnackbar(context.getString(R.string.import_success))
        )
    }

    fun selectAll() {
        if (!selectionEnabled) return
        for (index in packageList.indices) {
            packageList[index] = packageList[index].copy(selected = true)
        }
        selectedPackages = selectedPackages + packageList.map { it.packageName }
        prefs.apps = selectedPackages
    }

    fun inverseSelection() {
        if (!selectionEnabled) return
        for (index in packageList.indices) {
            val pkg = packageList[index]
            packageList[index] = pkg.copy(selected = !pkg.selected)
            selectedPackages =
                if (pkg.selected) selectedPackages - pkg.packageName
                else selectedPackages + pkg.packageName
        }
        prefs.apps = selectedPackages
    }
}

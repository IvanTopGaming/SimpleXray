package com.simplexray.an.support

import androidx.compose.ui.test.*
import com.simplexray.an.app.state.MainViewModel
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(value = MainViewModel::class, isInAndroidSdk = false)
class HostCoreVersionRead {
    @Implementation fun loadKernelVersion() {}
}

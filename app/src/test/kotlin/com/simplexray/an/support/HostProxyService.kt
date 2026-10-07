package com.simplexray.an.support

import com.simplexray.an.service.TProxyService
import org.junit.Assert.*
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers

@Implements(value = TProxyService::class, isInAndroidSdk = false)
class HostProxyService : ShadowService() {
    companion object {
        @JvmStatic
        @Implementation
        fun __staticInitializer__() {
            ReflectionHelpers.setStaticField(
                TProxyService::class.java,
                "Companion",
                ReflectionHelpers.callConstructor(TProxyService.Companion::class.java),
            )
        }
    }
}

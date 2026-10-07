package com.simplexray.an.ui.navigation

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class MainTaskManifestTest {
    @Test
    fun launcherActivityRemainsInRecentsAfterGoingToBackground() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val android = "http://schemas.android.com/apk/res/android"
        val main =
            (0 until activities.length)
                .map { activities.item(it) as Element }
                .single {
                    it.getAttributeNS(android, "name") == "com.simplexray.an.activity.MainActivity"
                }
        assertFalse(
            "Launcher task must stay visible in Recents",
            main.getAttributeNS(android, "excludeFromRecents").toBoolean(),
        )
        assertFalse(
            "Backgrounding must not discard the activity",
            main.getAttributeNS(android, "noHistory").toBoolean(),
        )
    }
}

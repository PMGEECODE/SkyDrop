package com.famage.skydrop_tv_app

import android.content.SharedPreferences
import com.famage.skydrop_tv_app.settings.TvSettingsStore
import com.famage.skydrop_tv_app.ui.theme.ThemePreference
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class TvSettingsStoreTest {

    private fun createMockPrefs(): SharedPreferences {
        val memoryStore = mutableMapOf<String, Any>()

        lateinit var editorProxy: SharedPreferences.Editor

        val editorHandler = object : InvocationHandler {
            override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                when (method.name) {
                    "putBoolean" -> {
                        if (args != null) memoryStore[args[0] as String] = args[1] as Boolean
                        return editorProxy
                    }
                    "putString" -> {
                        if (args != null) memoryStore[args[0] as String] = args[1] as String
                        return editorProxy
                    }
                    "putInt" -> {
                        if (args != null) memoryStore[args[0] as String] = args[1] as Int
                        return editorProxy
                    }
                    "apply" -> return null
                    "commit" -> return true
                    else -> return editorProxy
                }
            }
        }

        editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            editorHandler
        ) as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> memoryStore[args[0] as String] as? Boolean ?: (args[1] as Boolean)
                "getString" -> memoryStore[args[0] as String] as? String ?: (args[1] as? String)
                "getInt" -> memoryStore[args[0] as String] as? Int ?: (args[1] as Int)
                "edit" -> editorProxy
                else -> null
            }
        } as SharedPreferences
    }

    @Test
    fun testThemePreferenceDefaultsAndMutation() {
        val prefs = createMockPrefs()
        val store = TvSettingsStore(prefs)

        // Initial default should be SYSTEM
        assertEquals(ThemePreference.SYSTEM, store.themePreference.value)

        // Change to DARK
        store.setThemePreference(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, store.themePreference.value)

        // Change to LIGHT
        store.setThemePreference(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, store.themePreference.value)
    }

    @Test
    fun testReceiverActiveToggle() {
        val prefs = createMockPrefs()
        val store = TvSettingsStore(prefs)

        assertTrue(store.isReceiverActive.value)

        store.setReceiverActive(false)
        assertFalse(store.isReceiverActive.value)

        store.setReceiverActive(true)
        assertTrue(store.isReceiverActive.value)
    }

    @Test
    fun testPortAndSaveLocationDefaults() {
        val prefs = createMockPrefs()
        val store = TvSettingsStore(prefs)

        assertEquals(8444, store.listeningPort.value)
        assertEquals("Downloads/SkyDrop", store.saveLocation.value)
    }
}

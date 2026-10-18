package app.bellterm.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { System, Light, Dark }

/** App preferences, observed by the UI and applied live. */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    /** Material You colours from the wallpaper, where the device has them. */
    val dynamicColor: Boolean = false,
    val darkScheme: String = "bellterm-dark",
    val lightScheme: String = "bellterm-light",
    val font: String = "jetbrains-mono",
    val fontSize: Float = 13f,
    val vibrateOnBell: Boolean = true,
    val keepScreenOn: Boolean = false,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", d.themeMode.name)!!) }.getOrDefault(d.themeMode),
            dynamicColor = prefs.getBoolean("dynamic_color", d.dynamicColor),
            darkScheme = prefs.getString("dark_scheme", d.darkScheme)!!,
            lightScheme = prefs.getString("light_scheme", d.lightScheme)!!,
            font = prefs.getString("font", d.font)!!,
            fontSize = prefs.getFloat("font_size", d.fontSize),
            vibrateOnBell = prefs.getBoolean("vibrate_on_bell", d.vibrateOnBell),
            keepScreenOn = prefs.getBoolean("keep_screen_on", d.keepScreenOn),
        )
    }

    fun update(change: (Settings) -> Settings) {
        val next = change(_settings.value)
        prefs.edit()
            .putString("theme_mode", next.themeMode.name)
            .putBoolean("dynamic_color", next.dynamicColor)
            .putString("dark_scheme", next.darkScheme)
            .putString("light_scheme", next.lightScheme)
            .putString("font", next.font)
            .putFloat("font_size", next.fontSize)
            .putBoolean("vibrate_on_bell", next.vibrateOnBell)
            .putBoolean("keep_screen_on", next.keepScreenOn)
            .apply()
        _settings.value = next
    }

    /** The terminal size last laid out, so a new connection starts close to it. */
    var lastGrid: IntArray
        get() = intArrayOf(prefs.getInt("cols", 80), prefs.getInt("rows", 24), prefs.getInt("cw", 0), prefs.getInt("ch", 0))
        set(value) {
            prefs.edit().putInt("cols", value[0]).putInt("rows", value[1]).putInt("cw", value[2]).putInt("ch", value[3]).apply()
        }
}

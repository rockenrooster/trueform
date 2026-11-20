package dev.trueform.player.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Where a setting's change takes effect (docs/plan/09-settings.md). */
enum class Applies { LIVE, RELOAD_STREAM, RESCAN, RECREATE_PLAYER, NEXT_START }

enum class Category(val title: String) {
    LIBRARY("Library"), PLAYBACK("Playback"), AUDIO("Audio"), EQUALIZER("Equalizer"), LOOK("Look and feel"),
    CAST("Cast"), BLUETOOTH("Bluetooth and outputs"), UPDATES("Updates"), ADVANCED("Advanced"),
}

/** One setting: its key, default and how the settings screen shows it. */
sealed class Setting<T>(
    val key: String,
    val default: T,
    val category: Category,
    val title: String,
    val summary: String?,
    val keywords: String,
    val applies: Applies,
) {
    internal abstract fun read(p: SharedPreferences): T
    internal abstract fun write(e: SharedPreferences.Editor, v: T)
    open val stored: Boolean get() = true
}

class Toggle(key: String, default: Boolean, category: Category, title: String, summary: String? = null,
             keywords: String = "", applies: Applies = Applies.LIVE) :
    Setting<Boolean>(key, default, category, title, summary, keywords, applies) {
    override fun read(p: SharedPreferences) = p.getBoolean(key, default)
    override fun write(e: SharedPreferences.Editor, v: Boolean) { e.putBoolean(key, v) }
}

class Choice<E : Enum<E>>(key: String, default: E, category: Category, title: String, val options: List<E>,
                          val label: (E) -> String, summary: String? = null, keywords: String = "",
                          applies: Applies = Applies.LIVE) :
    Setting<E>(key, default, category, title, summary, keywords, applies) {
    override fun read(p: SharedPreferences): E {
        val name = p.getString(key, null) ?: return default
        return options.firstOrNull { it.name == name } ?: default
    }
    override fun write(e: SharedPreferences.Editor, v: E) { e.putString(key, v.name) }

    /** The label of a value of this choice, for code that only has a Choice<*>. */
    @Suppress("UNCHECKED_CAST")
    fun labelOf(v: Any?): String = label(v as E)
}

class Number(key: String, default: Int, category: Category, title: String, val range: IntRange, val step: Int,
             val unit: String, summary: String? = null, keywords: String = "", applies: Applies = Applies.LIVE) :
    Setting<Int>(key, default, category, title, summary, keywords, applies) {
    override fun read(p: SharedPreferences) = p.getInt(key, default).coerceIn(range)
    override fun write(e: SharedPreferences.Editor, v: Int) { e.putInt(key, v.coerceIn(range)) }
}

class Decibels(key: String, default: Float, category: Category, title: String,
               val range: ClosedFloatingPointRange<Float>, val step: Float, summary: String? = null,
               keywords: String = "", applies: Applies = Applies.LIVE) :
    Setting<Float>(key, default, category, title, summary, keywords, applies) {
    override fun read(p: SharedPreferences) = p.getFloat(key, default).coerceIn(range)
    override fun write(e: SharedPreferences.Editor, v: Float) { e.putFloat(key, v.coerceIn(range)) }
}

class Text(key: String, default: String, category: Category, title: String, summary: String? = null,
           keywords: String = "", applies: Applies = Applies.LIVE) :
    Setting<String>(key, default, category, title, summary, keywords, applies) {
    override fun read(p: SharedPreferences) = p.getString(key, default) ?: default
    override fun write(e: SharedPreferences.Editor, v: String) { e.putString(key, v) }
}

/** A row that runs something (rescan, export, open a screen). Not stored. */
/** Settings search (docs/plan/09-settings.md): title, summary and keywords, ignoring case. */
fun Setting<*>.matches(query: String): Boolean {
    val q = query.trim().lowercase()
    return q.isNotEmpty() && (title.lowercase().contains(q) || (summary ?: "").lowercase().contains(q) || keywords.lowercase().contains(q))
}

class Action(key: String, category: Category, title: String, summary: String? = null, keywords: String = "",
             val id: String = key) :
    Setting<Unit>(key, Unit, category, title, summary, keywords, Applies.LIVE) {
    override fun read(p: SharedPreferences) = Unit
    override fun write(e: SharedPreferences.Editor, v: Unit) = Unit
    override val stored get() = false
}

/** A read-only row; its value comes from [AppInfo] at display time. */
class Info(key: String, category: Category, title: String, keywords: String = "") :
    Setting<Unit>(key, Unit, category, title, null, keywords, Applies.LIVE) {
    override fun read(p: SharedPreferences) = Unit
    override fun write(e: SharedPreferences.Editor, v: Unit) = Unit
    override val stored get() = false
}

/** SharedPreferences behind typed settings, with flows for Compose and synchronous reads for audio threads. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    /** Bumps on every change; [flow] maps it. */
    val version: StateFlow<Int> = _version
    @Suppress("unused") // kept strongly: SharedPreferences holds listeners weakly
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _version.value++ }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    operator fun <T> get(s: Setting<T>): T = s.read(prefs)

    operator fun <T> set(s: Setting<T>, v: T) {
        if (!s.stored) return
        prefs.edit().also { s.write(it, v) }.apply()
    }

    fun <T> flow(s: Setting<T>): Flow<T> = version.map { s.read(prefs) }.distinctUntilChanged()

    fun reset(s: Setting<*>) {
        if (s.stored) prefs.edit().remove(s.key).apply()
    }

    fun resetAll() = prefs.edit().clear().apply()

    fun setChoice(s: Choice<*>, v: Any?) {
        if (v is Enum<*>) prefs.edit().putString(s.key, v.name).apply()
    }

    /** Raw access for export and import (docs/plan/09-settings.md). */
    fun all(): Map<String, *> = prefs.all
    fun putRaw(key: String, value: Any?) {
        val e = prefs.edit()
        when (value) {
            null -> e.remove(key)
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is Long -> e.putLong(key, value)
            is Float -> e.putFloat(key, value)
            is Double -> e.putFloat(key, value.toFloat())
            is String -> e.putString(key, value)
        }
        e.apply()
    }

    /** Small private values that are not settings (the cast key, flags). */
    fun privateString(key: String): String? = prefs.getString("_$key", null)
    fun putPrivateString(key: String, v: String?) = prefs.edit().putString("_$key", v).apply()
    fun privateLong(key: String, def: Long = 0): Long = prefs.getLong("_$key", def)
    fun putPrivateLong(key: String, v: Long) = prefs.edit().putLong("_$key", v).apply()
}

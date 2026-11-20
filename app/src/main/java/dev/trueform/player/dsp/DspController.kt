package dev.trueform.player.dsp

import android.content.Context
import dev.trueform.player.settings.S
import dev.trueform.player.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class EqMode { GRAPHIC, TONE }

/** What the Equalizer screen edits. Slider values, not corrected gains. */
data class EqState(
    val on: Boolean = false,
    val mode: EqMode = EqMode.GRAPHIC,
    val layout: BandLayout = BandLayout.B10,
    val gains: FloatArray = FloatArray(10),
    val tone: FloatArray = FloatArray(4),
    val autoPreamp: Boolean = true,
    val preampDb: Float = 0f,
    val balance: Float = 0f,
    val mono: Boolean = false,
    val preset: String? = null,
    val presetModified: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("on", on).put("mode", mode.name).put("bands", layout.count)
        .put("gains", JSONArray(gains.map { it.toDouble() })).put("tone", JSONArray(tone.map { it.toDouble() }))
        .put("auto", autoPreamp).put("preamp", preampDb.toDouble()).put("balance", balance.toDouble()).put("mono", mono)
        .put("preset", preset ?: JSONObject.NULL).put("modified", presetModified)

    override fun equals(other: Any?) = other is EqState && toJson().toString() == other.toJson().toString()
    override fun hashCode() = toJson().toString().hashCode()

    companion object {
        fun fromJson(o: JSONObject): EqState {
            val layout = BandLayout.of(o.optInt("bands", 10))
            fun floats(key: String, n: Int): FloatArray {
                val a = o.optJSONArray(key) ?: return FloatArray(n)
                return FloatArray(n) { if (it < a.length()) a.optDouble(it, 0.0).toFloat() else 0f }
            }
            return EqState(
                on = o.optBoolean("on", false),
                mode = runCatching { EqMode.valueOf(o.optString("mode")) }.getOrDefault(EqMode.GRAPHIC),
                layout = layout,
                gains = floats("gains", layout.count),
                tone = floats("tone", 4),
                autoPreamp = o.optBoolean("auto", true),
                preampDb = o.optDouble("preamp", 0.0).toFloat(),
                balance = o.optDouble("balance", 0.0).toFloat(),
                mono = o.optBoolean("mono", false),
                preset = if (o.isNull("preset")) null else o.optString("preset"),
                presetModified = o.optBoolean("modified", false),
            )
        }
    }
}

/** A named curve. Built-ins are 10-band curves interpolated to the layout in use. */
data class EqPreset(val name: String, val builtIn: Boolean, val mode: EqMode, val layout: BandLayout,
                    val gains: FloatArray, val tone: FloatArray) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("mode", mode.name).put("bands", layout.count)
        .put("gains", JSONArray(gains.map { it.toDouble() })).put("tone", JSONArray(tone.map { it.toDouble() }))

    companion object {
        fun fromJson(o: JSONObject): EqPreset {
            val layout = BandLayout.of(o.optInt("bands", 10))
            val g = o.optJSONArray("gains"); val t = o.optJSONArray("tone")
            return EqPreset(o.getString("name"), false,
                runCatching { EqMode.valueOf(o.optString("mode")) }.getOrDefault(EqMode.GRAPHIC), layout,
                FloatArray(layout.count) { if (g != null && it < g.length()) g.optDouble(it).toFloat() else 0f },
                FloatArray(4) { if (t != null && it < t.length()) t.optDouble(it).toFloat() else 0f })
        }

        private fun b(name: String, vararg g: Float) =
            EqPreset(name, true, EqMode.GRAPHIC, BandLayout.B10, g, FloatArray(4))

        /** Starting points to retune with the owner (docs/plan/06-dsp-equalizer.md T8). */
        val BUILT_IN = listOf(
            b("Flat", 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            b("Bass boost", 6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f),
            b("Bass cut", -6f, -5f, -4f, -2f, -1f, 0f, 0f, 0f, 0f, 0f),
            b("Treble boost", 0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 6f),
            b("Vocal", -2f, -1.5f, -1f, 1f, 3f, 4f, 3f, 1.5f, 0f, -1f),
            b("Rock", 4.5f, 3.5f, 2f, 0f, -1.5f, -1f, 1f, 2.5f, 3.5f, 4f),
            b("Pop", -1f, 0f, 1.5f, 3f, 3.5f, 2.5f, 1f, 0f, -0.5f, -1f),
            b("Jazz", 3f, 2f, 1f, 1.5f, -1f, -1f, 0f, 1f, 2f, 3f),
            b("Classical", 3.5f, 2.5f, 1.5f, 1f, 0f, 0f, 0f, 1.5f, 2.5f, 3.5f),
            b("Electronic", 5f, 4f, 1.5f, 0f, -1.5f, 1f, 0.5f, 1.5f, 4f, 5f),
            b("Loudness", 6f, 4f, 1.5f, 0f, -1f, -1f, 0f, 1.5f, 4f, 5.5f),
            b("Speech", -4f, -3f, -1f, 1f, 3f, 4f, 3.5f, 2f, 0f, -2f),
        )
    }
}

/**
 * Owns the editable EQ state, persists it (per output when M8's profiles are on), and publishes immutable
 * [DspParams] for the audio threads (docs/plan/06-dsp-equalizer.md T6). Main thread for edits.
 */
class DspController(context: Context, private val settings: Settings, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "eq.json")
    private val presetsFile = File(context.filesDir, "presets.json")

    private val _state = MutableStateFlow(load())
    val state: StateFlow<EqState> = _state

    @Volatile var params: DspParams = DspParams.NEUTRAL
        private set
    private val _paramsFlow = MutableStateFlow(params)
    val paramsFlow: StateFlow<DspParams> = _paramsFlow

    private val _userPresets = MutableStateFlow(loadPresets())
    val userPresets: StateFlow<List<EqPreset>> = _userPresets

    /** Current output route key and label (M8); the EQ state is saved under it. */
    @Volatile var routeKey: String = "default"
        private set
    private val _routeLabel = MutableStateFlow<String?>(null)
    val routeLabel: StateFlow<String?> = _routeLabel

    private var publishJob: Job? = null
    private var saveJob: Job? = null

    init {
        publishNow()
        scope.launch { settings.version.collect { republishIfSettingsChanged() } }
    }

    private var lastSettings: List<Any>? = null
    private fun republishIfSettingsChanged() {
        val l = listOf(settings[S.limiter], settings[S.limiterCeiling], settings[S.accurateBands], settings[S.gainRange])
        if (l != lastSettings) { lastSettings = l; publishNow() }
    }

    fun update(change: (EqState) -> EqState) {
        val next = change(_state.value)
        if (next == _state.value) return
        _state.value = next
        publishSoon()
        saveSoon()
    }

    fun setOn(on: Boolean) = update { it.copy(on = on) }
    fun setMode(mode: EqMode) = update { it.copy(mode = mode) }
    fun setBand(i: Int, db: Float) = update {
        val g = it.gains.copyOf(); g[i] = db
        it.copy(gains = g, presetModified = it.preset != null)
    }
    fun setBands(gains: FloatArray) = update { it.copy(gains = gains, presetModified = it.preset != null) }
    fun setTone(i: Int, db: Float) = update {
        val t = it.tone.copyOf(); t[i] = db
        it.copy(tone = t, presetModified = it.preset != null)
    }
    fun setLayout(layout: BandLayout) = update {
        it.copy(layout = layout, gains = EqDesign.convert(it.layout, it.gains, layout))
    }
    fun setAutoPreamp(on: Boolean) = update { it.copy(autoPreamp = on) }
    fun setPreamp(db: Float) = update { it.copy(preampDb = db) }
    fun setBalance(b: Float) = update { it.copy(balance = b) }
    fun setMono(m: Boolean) = update { it.copy(mono = m) }
    fun reset() = update {
        if (it.mode == EqMode.TONE) it.copy(tone = FloatArray(4), preset = null, presetModified = false)
        else it.copy(gains = FloatArray(it.layout.count), preset = null, presetModified = false)
    }

    fun applyPreset(p: EqPreset) = update {
        if (p.mode == EqMode.TONE) it.copy(mode = EqMode.TONE, tone = p.tone.copyOf(), preset = p.name, presetModified = false)
        else it.copy(mode = EqMode.GRAPHIC, gains = EqDesign.convert(p.layout, p.gains, it.layout), preset = p.name, presetModified = false, on = true)
    }

    fun saveAsPreset(name: String) {
        val s = _state.value
        val p = EqPreset(name, false, s.mode, s.layout, s.gains.copyOf(), s.tone.copyOf())
        _userPresets.value = _userPresets.value.filter { it.name != name } + p
        savePresets()
        update { it.copy(preset = name, presetModified = false) }
    }

    fun deletePreset(name: String) {
        _userPresets.value = _userPresets.value.filter { it.name != name }
        savePresets()
    }

    fun renamePreset(old: String, new: String) {
        _userPresets.value = _userPresets.value.map { if (it.name == old) it.copy(name = new) else it }
        savePresets()
        if (_state.value.preset == old) update { it.copy(preset = new) }
    }

    fun exportPresets(): String = JSONArray(_userPresets.value.map { it.toJson() }).toString(1)

    /** Adds the presets in [json] (replacing same names); returns how many. */
    fun importPresets(json: String): Int {
        val a = JSONArray(json)
        val imported = (0 until a.length()).map { EqPreset.fromJson(a.getJSONObject(it)) }
        val names = imported.map { it.name }.toSet()
        _userPresets.value = _userPresets.value.filter { it.name !in names } + imported
        savePresets()
        return imported.size
    }

    /** Switches to the EQ saved for an output (M8); the current one is saved under the old key first. */
    fun switchRoute(key: String, label: String, profiles: Boolean) {
        if (!profiles) { _routeLabel.value = null; return }
        if (key == routeKey && _routeLabel.value == label) return
        saveNow()
        routeKey = key
        _routeLabel.value = label
        val stored = readProfiles().optJSONObject(key)
        if (stored != null) _state.value = EqState.fromJson(stored)
        publishNow()
        saveNow()
    }

    fun profiles(): List<Pair<String, String>> {
        val o = readProfiles()
        return o.keys().asSequence().filter { it != "default" }.map { it to (o.optJSONObject(it)?.optString("label") ?: it) }.toList()
    }

    fun deleteProfile(key: String) {
        val o = readProfiles(); o.remove(key); writeProfiles(o)
    }

    // --- publishing -----------------------------------------------------------------------------------------------

    private fun publishSoon() {
        if (publishJob?.isActive == true) return
        publishJob = scope.launch(Dispatchers.Default) {
            delay(10)
            publishNow()
        }
    }

    private fun publishNow() {
        val s = _state.value
        val filters = when (s.mode) {
            EqMode.GRAPHIC -> {
                val gains = if (settings[S.accurateBands]) EqDesign.correctedGains(s.layout, s.gains, settings[S.gainRange].db)
                else DoubleArray(s.layout.count) { s.gains[it].toDouble() }
                if (s.gains.all { it == 0f }) emptyList() else EqDesign.graphicSpecs(s.layout, gains)
            }
            EqMode.TONE -> if (s.tone.all { it == 0f }) emptyList() else ToneTuning.specs(s.tone)
        }
        val p = DspParams(s.on, filters, s.autoPreamp, s.preampDb, s.balance, s.mono, settings[S.limiter],
            settings[S.limiterCeiling].db)
        params = p
        _paramsFlow.value = p
    }

    // --- persistence ------------------------------------------------------------------------------------------------

    private fun load(): EqState = runCatching {
        val o = readProfiles().optJSONObject("default") ?: return EqState()
        EqState.fromJson(o)
    }.getOrDefault(EqState())

    private fun readProfiles(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private fun writeProfiles(o: JSONObject) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) { delay(500); saveNow() }
    }

    @Synchronized private fun saveNow() {
        val o = readProfiles()
        val state = _state.value.toJson().put("label", _routeLabel.value ?: "")
        o.put(routeKey, state)
        if (routeKey != "default") o.put("default", state)   // the last used curve also starts the next unknown output
        writeProfiles(o)
    }

    private fun loadPresets(): List<EqPreset> = runCatching {
        val a = JSONArray(presetsFile.readText())
        (0 until a.length()).map { EqPreset.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun savePresets() {
        scope.launch(Dispatchers.IO) { presetsFile.writeText(exportPresets()) }
    }
}

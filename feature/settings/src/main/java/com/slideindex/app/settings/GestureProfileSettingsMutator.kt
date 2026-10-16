package com.slideindex.app.settings

import androidx.datastore.preferences.core.MutablePreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 侧滑手势「按应用分档」的写回（GitHub issue #26）。
 *
 * 每次编辑都是"读整份档列表 → 改一份 → 整份写回 string-set"。档数量是个位数、每档几百行，
 * 这点开销可以忽略；换来的是没有"局部改一半"的中间态。
 */
@Singleton
class GestureProfileSettingsMutator @Inject constructor(
    private val editor: SettingsPreferencesEditor,
) {
    suspend fun setIndependentAppGestureEnabled(enabled: Boolean) = editor.edit {
        it[SettingsPreferenceKeys.INDEPENDENT_APP_GESTURE_ENABLED] = enabled
    }

    /** 新建/覆盖一个档（按 id 覆盖）。建档时调用方负责把全局那一整片复制进 [profile.snapshot]。 */
    suspend fun upsertGestureProfile(profile: GestureProfile) = editor.edit { prefs ->
        val current = read(prefs)
        write(prefs, current.filterNot { it.id == profile.id } + profile)
    }

    suspend fun removeGestureProfile(id: String) = editor.edit { prefs ->
        val current = read(prefs)
        if (current.none { it.id == id }) return@edit
        write(prefs, current.filterNot { it.id == id })
    }

    /** 档内页每改一处就整片写回（见 `GestureAppProfileScope` 的"内存里编辑、整片落盘"）。 */
    suspend fun setGestureProfileSnapshot(id: String, snapshot: GestureProfileSnapshot) = editor.edit { prefs ->
        val current = read(prefs)
        if (current.none { it.id == id }) return@edit
        write(prefs, current.map { if (it.id == id) it.withSnapshot(snapshot) else it })
    }

    /** 改名 / 增删绑定 App（整档替换）。 */
    suspend fun updateGestureProfile(profile: GestureProfile) = editor.edit { prefs ->
        val current = read(prefs)
        if (current.none { it.id == profile.id }) return@edit
        write(prefs, current.map { if (it.id == profile.id) profile else it })
    }

    private fun read(prefs: MutablePreferences): List<GestureProfile> =
        SettingsSnapshotReader.readGestureProfiles(prefs)

    private fun write(prefs: MutablePreferences, profiles: List<GestureProfile>) {
        prefs[SettingsPreferenceKeys.GESTURE_PROFILES] = GestureProfileCodec.encodeAll(profiles)
    }
}

// ── 内存侧（乐观更新 / UI 读取）────────────────────────────────────────────

fun AppSettings.gestureProfileById(id: String): GestureProfile? = gestureProfiles.firstOrNull { it.id == id }

/** 某个包名当前挂在哪个档下（UI 做"一个 App 只能绑一个档"的校验）。 */
fun AppSettings.profileIdOwning(packageName: String): String? =
    gestureProfiles.firstOrNull { packageName in it.packageNames }?.id

fun AppSettings.withIndependentAppGestureEnabled(enabled: Boolean): AppSettings =
    copy(independentAppGestureEnabled = enabled)

fun AppSettings.upsertGestureProfile(profile: GestureProfile): AppSettings =
    copy(gestureProfiles = gestureProfiles.filterNot { it.id == profile.id } + profile)

fun AppSettings.removeGestureProfile(id: String): AppSettings =
    copy(gestureProfiles = gestureProfiles.filterNot { it.id == id })

fun AppSettings.withGestureProfileSnapshotApplied(id: String, snapshot: GestureProfileSnapshot): AppSettings =
    copy(
        gestureProfiles = gestureProfiles.map {
            if (it.id == id) it.withSnapshot(snapshot) else it
        },
    )

/**
 * **档内编辑页的读入口**：把"全局 + 该档那一片"materialize 成一个 `AppSettings`。
 *
 * 这样档内页可以整套复用全局那批页面与纯函数（`withSlotAction` / `withTriggerHandle*` /
 * `withGestureRules`…），改动全在内存里发生，最后由
 * [GestureProfileSettingsMutator.setGestureProfileSnapshot] 整片落盘 —— 不需要给每个 mutator
 * 再加一个"写到哪个档"的作用域参数。
 */
fun AppSettings.materializeGestureProfile(id: String): AppSettings {
    val profile = gestureProfileById(id) ?: return this
    return withGestureProfileSnapshot(profile.snapshot)
}

/** materialize 出来的那份 `AppSettings` 反过来提取快照（档内页保存时用）。 */
fun AppSettings.extractGestureProfileSnapshot(): GestureProfileSnapshot = gestureSnapshot()

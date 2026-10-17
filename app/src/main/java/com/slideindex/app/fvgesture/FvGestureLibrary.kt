/*
 * FV「自定义手势」参考实现 —— 第 2 层：手势库（FooView q1.o 的等价物）
 *
 * 与 FooView 1.6.4 的对应关系：
 *   q1.o.h(int)              ->  FvGestureLibrary.of(type)
 *   q1.o.A(boolean)          ->  load(force)
 *   q1.o.H(g, rawId) / G(g)  ->  recognize(gesture, rawId) / recognizeForTrigger(gesture)
 *   q1.o.b / O / J           ->  add / update / delete
 *   q1.o.l / t / v / o / s   ->  gestureOf / mirrorGestureOf / baseGestureOf / userIdAt / userCount
 *   q1.o.u / y / z / k       ->  allocId / isTaken / isMirrorId / normalizeId
 *   q1.o.B / N / E / F / M   ->  元数据 JSON 读写（本文件内实现）
 *
 * 与 FV 的差异（有意）：
 *   1. FV 的动作表在独立的 `i0.g`（按 id 索引、另一份 gestureCfg 文件）。本实现把动作负载
 *      **存在手势自己的元数据里**，字段名 `action`，值是 `QuickLauncherItemCodec.encodeActionPayload()`
 *      的输出（与快速启动器/圆环等槽位共用同一套动作编解码）。
 *   2. FV 在加载时会拿 `i0.g.g(k(id))` 校验「动作是否还存在」，不存在就删手势；本实现把这条规则
 *      改成「动作负载解析不出来、或解析成 None → 删手势」，语义等价。
 *   3. 镜像 ID（+500）依旧**不**单独存动作：写入时镜像条目 `action` 恒为 None，读取一律经
 *      [actionOf] 先 `-500` 归一（等价于 FV 的 `q1.o.k()`）。
 *
 * 依赖：平台 android.gesture.GestureStore（与 FV 完全同源，识别行为逐位一致）。
 */
package com.slideindex.app.fvgesture

import android.content.Context
import android.gesture.Gesture
import android.gesture.GestureStore
import android.gesture.Prediction
import android.util.Log
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.isEffective
import com.slideindex.app.launcher.QuickLauncherItemCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class FvGestureLibrary private constructor(
    /** true = 图标手势库（h(1)，设置→手势→自定义手势）；false = 通用手势库（h(2)，圆环） */
    val isIconLibrary: Boolean
) {

    companion object {
        private const val TAG = "FvGestureLibrary"

        /** 镜像手势 ID 偏移（FV: 500） */
        const val MIRROR_OFFSET = 500

        /**
         * `icon_gestures_info` 里「该手绘轨迹触发什么动作」的 JSON 键名。
         *
         * 值 = `QuickLauncherItemCodec.encodeActionPayload(action)`，与快速启动器 / 圆环 /
         * 悬浮球手势槽位**共用同一套动作编解码**（形态：`<动作 type id>\u001C<动作正文>`，
         * 其中 LAUNCH_APP 的正文还会带启动形态后缀 `\u001D<mode id>`）。
         * 例：`{"name":"2001","id":2001,"simple":true,"d_1":4,"d_2":1,"action":"1\u001Ccom.tencent.mm"}`
         */
        const val KEY_ACTION = "action"

        /** FV: q1.o.p / q1.o.q */
        private val iconLibrary = FvGestureLibrary(true)
        private val genericLibrary = FvGestureLibrary(false)

        lateinit var appContext: Context
            private set

        /** FV: q1.o.h(int) —— 非法值回落到图标库 */
        fun of(type: Int): FvGestureLibrary =
            if (type == 2) genericLibrary else iconLibrary

        const val TYPE_ICON = 1
        const val TYPE_GENERIC = 2

        /** 建议在 Application.onCreate 里调用一次；**只记录 context，不读盘**（库文件在首次访问时惰性加载） */
        fun init(context: Context) {
            appContext = context.applicationContext
        }
    }

    // ------------------------------------------------------------ 常量（FV 原值）

    /** FV: q1.o.g() */
    val baseId: Int get() = if (isIconLibrary) 2000 else 1000

    /** FV: q1.o.a() —— Prediction.score 的最低门槛 */
    val scoreThreshold: Float get() = if (isIconLibrary) 2.0f else 1.6f

    /** 本次判定用的直线容差 = 4 × getScaledTouchSlop() */
    private val lineTol: Int get() = lineTolerance(appContext)

    // ------------------------------------------------------------ 状态

    /** 主库：复杂手势（isSimple == false） */
    private var mainStore = GestureStore()

    /** 简单库：方向手势（isSimple == true）。只 save/load，识别不走它 */
    private var simpleStore = GestureStore()

    /** FV: q1.o.e —— 元数据列表（唯一真相源） */
    private val infos = ArrayList<FvGestureInfo>()

    /** FV: q1.o.c —— 就绪标志 */
    var isReady = false
        private set

    /** FV: q1.o.d —— 已加载标志 */
    private var isLoaded = false

    /** 正在加载（防止 load() 内部回调触发重入的 ensureLoaded） */
    private var isLoading = false

    /**
     * 惰性加载：任何需要读库文件的公开 API 都会先调它。
     * 这样 `init()` 可以放在 Application.onCreate 里而不产生磁盘 I/O。
     */
    private fun ensureLoaded() {
        if (isLoaded || isLoading) return
        load(force = false)
    }

    // ------------------------------------------------------------ 路径

    private val baseDir: File get() = File(appContext.filesDir, "fooView")

    /** FV: q1.o.n() */
    private val mainFile: File
        get() = File(baseDir, if (isIconLibrary) "icon_gestures" else "gestures")

    /** FV: q1.o.x() */
    private val simpleFile: File
        get() = File(baseDir, if (isIconLibrary) "icon_gestures_simple" else "gestures_simple")

    /** FV: q1.o.r() */
    private val infoFile: File
        get() = File(baseDir, if (isIconLibrary) "icon_gestures_info" else "gestures_info")

    // ------------------------------------------------------------ 加载 / 保存

    /** FV: q1.o.A(boolean) / I() */
    fun load(force: Boolean) {
        if (isLoaded && !force) return
        isLoading = true
        try {
            loadInfoList()                                     // B()
            mainStore = loadStore(mainFile)                    // a
            simpleStore = loadStore(simpleFile)                // b
            var changed = purgeOrphans(simpleStore)            // e(b)
            changed = purgeOrphans(mainStore) || changed       // e(a)

            // 清理「手势文件里没有」或「动作已失效」的元数据。
            // FV 的规则是 `i0.g.g(k(info.id)) == null` → 删手势；这里换成「动作负载解析不出 → 删」。
            val dead = ArrayList<FvGestureInfo>()
            for (info in infos) {
                if (gestureOfInternal(info.id) == null) { dead.add(info); continue }
                if (actionOfInternal(info.id) == null) {
                    Log.e(TAG, "gesture id: ${info.id} has no usable action, deleted")
                    dead.add(info)
                }
            }
            if (dead.isNotEmpty()) {
                for (info in dead) { removeEntry(info); infos.remove(info) }
                changed = true
            }

            // 首次运行灌入预置手势（移植时可选；本参考实现默认不灌，见 seedPredefinedIfNeeded）
            if (seedPredefinedIfNeeded()) changed = true

            if (changed) save()
            isLoaded = true
            isReady = true
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            isLoaded = false
            isReady = false
        } finally {
            isLoading = false
        }
    }

    private fun loadStore(file: File): GestureStore {
        val store = GestureStore()
        if (file.exists()) {
            FileInputStream(file).use { store.load(it) }
        }
        return store
    }

    /** FV: q1.o.M() —— 落盘（元数据 + 两个库文件） */
    fun save(): Boolean = try {
        baseDir.mkdirs()
        saveInfoList()
        FileOutputStream(mainFile).use { mainStore.save(it) }
        FileOutputStream(simpleFile).use { simpleStore.save(it) }
        true
    } catch (e: Exception) {
        Log.e(TAG, "save failed", e)
        false
    }

    /** FV: q1.o.e(GestureStore) —— 删掉 store 里没有元数据的 entry；返回是否有改动 */
    private fun purgeOrphans(store: GestureStore): Boolean {
        val orphans = store.gestureEntries.filter { infoByName(it) == null }
        for (name in orphans) store.removeEntry(name)
        return orphans.isNotEmpty()
    }

    /** FV: q1.o.B() */
    private fun loadInfoList() {
        infos.clear()
        if (!infoFile.exists()) return
        val text = infoFile.readText()
        if (text.isEmpty()) return
        val items = JSONObject(text).optJSONArray("items") ?: return
        for (i in 0 until items.length()) {
            infoFromJson(items.optJSONObject(i))?.let { infos.add(it) }
        }
    }

    /** FV: q1.o.N() */
    private fun saveInfoList() {
        val items = JSONArray()
        for (info in infos) items.put(infoToJson(info))
        val root = JSONObject()
        root.put("items", items)
        infoFile.parentFile?.mkdirs()
        infoFile.writeText(root.toString())
    }

    /**
     * FV: q1.o.E(n5.d0, q1.o$a)
     *
     * 与 FV 的差异：多写一个 `action` 字段（动作负载）。镜像条目没有动作，不写这个键。
     */
    private fun infoToJson(info: FvGestureInfo): JSONObject = JSONObject().apply {
        put("name", info.name)
        put("id", info.id)
        put("simple", info.isSimple)
        put("d_1", info.dir1)
        put("d_2", info.dir2)
        if (info.action.isEffective()) {
            put(KEY_ACTION, QuickLauncherItemCodec.encodeActionPayload(info.action))
        }
    }

    /** FV: q1.o.F(n5.d0) —— 过滤规则与 FV 一致；额外解析 [KEY_ACTION] */
    private fun infoFromJson(obj: JSONObject?): FvGestureInfo? {
        if (obj == null) return null
        val info = FvGestureInfo(
            name = obj.optString("name", ""),
            id = obj.optInt("id", 0),
            isSimple = obj.optBoolean("simple", false),
            dir1 = obj.optInt("d_1", 0),
            dir2 = obj.optInt("d_2", 0),
            // 解析失败按 None 处理：加载结束后的清理会把这类条目删掉（等价 FV 的「动作不存在就删手势」）
            action = obj.optString(KEY_ACTION, "").let { raw ->
                if (raw.isEmpty()) GestureAction.None
                else QuickLauncherItemCodec.parseActionPayload(raw) ?: GestureAction.None
            }
        )
        if (info.name.isEmpty()) return null
        if (info.isSimple && (info.dir1 == 0 || info.dir2 == 0)) return null
        return info
    }

    /** FV 的预置手势灌入（可选）。返回是否有改动。 */
    private fun seedPredefinedIfNeeded(): Boolean = false   // 见文档 02 §4；需要时自行实现

    // ------------------------------------------------------------ ID 工具

    /** FV: q1.o.z(int) —— 是否镜像手势 ID */
    fun isMirrorId(id: Int): Boolean = id >= baseId + MIRROR_OFFSET

    /** FV: q1.o.k(int) —— 镜像 ID 归一到基 ID（注意门槛与 z() 差 1，FV 原样） */
    fun normalizeId(id: Int): Int =
        if (id > baseId + MIRROR_OFFSET) id - MIRROR_OFFSET else id

    /** FV: q1.o.u() */
    fun allocId(): Int {
        var id = baseId + 1
        while (isTaken(id)) id++
        return id
    }

    /** FV: q1.o.y(int) */
    fun isTaken(id: Int): Boolean = id < baseId || infos.any { it.id == id }

    // ------------------------------------------------------------ 查询

    /** FV: q1.o.s() —— 用户可见手势数量（跳过镜像项） */
    val userCount: Int get() {
        ensureLoaded()
        return infos.count { !isMirrorId(it.id) }
    }

    /** FV: q1.o.o(int) —— 第 pos 个用户可见手势的动作 ID */
    fun userIdAt(pos: Int): Int {
        if (pos < 0) return -1
        ensureLoaded()
        var index = 0
        for (info in infos) {
            if (!isMirrorId(info.id)) {
                if (index == pos) return info.id
                index++
            }
        }
        return -1
    }

    /** FV: q1.o.l(int) */
    fun gestureOf(id: Int): Gesture? {
        ensureLoaded()
        return gestureOfInternal(id)
    }

    /** 不做惰性加载的内部版本（load() 自己要用，避免重入） */
    private fun gestureOfInternal(id: Int): Gesture? {
        val info = infoById(id) ?: return null
        val store = if (info.isSimple) simpleStore else mainStore
        val list = store.getGestures(info.name) ?: return null
        return if (list.isEmpty()) null else list[0]
    }

    /** FV: q1.o.t(int) —— 取镜像手势 */
    fun mirrorGestureOf(id: Int): Gesture? {
        ensureLoaded()
        var target = id
        if (target < baseId + MIRROR_OFFSET) target += MIRROR_OFFSET
        return gestureOfInternal(target)
    }

    /** FV: q1.o.v(int) —— 取基手势 */
    fun baseGestureOf(id: Int): Gesture? {
        ensureLoaded()
        var target = id
        if (target >= baseId + MIRROR_OFFSET) target -= MIRROR_OFFSET
        return gestureOfInternal(target)
    }

    /** FV: q1.o.m(q1.o$a) */
    fun gestureOf(info: FvGestureInfo?): Gesture? = info?.let { gestureOf(it.id) }

    /**
     * 该手绘轨迹要执行的动作（镜像 ID 会先 `-500` 归一到基 ID，等价 FV 的 `q1.o.k()`）。
     *
     * @return 手势不存在、或该手势没有有效动作时返回 null（FV 里对应 `i0.g.g(id) == null`）
     */
    fun actionOf(id: Int): GestureAction? {
        ensureLoaded()
        return actionOfInternal(id)
    }

    /** 不做惰性加载的内部版本（load() 自己要用，避免重入） */
    private fun actionOfInternal(id: Int): GestureAction? {
        val info = infoById(normalizeId(id)) ?: return null
        return info.action.takeIf { it.isEffective() }
    }

    fun infoById(id: Int): FvGestureInfo? = infos.firstOrNull { it.id == id }

    fun infoByName(name: String): FvGestureInfo? = infos.firstOrNull { it.name == name }

    /** 供 UI 迭代（不可变快照） */
    fun userInfos(): List<FvGestureInfo> {
        ensureLoaded()
        return infos.filter { !isMirrorId(it.id) }
    }

    // ------------------------------------------------------------ 识别

    /**
     * FV: q1.o.G(Gesture) —— 运行时触发判定入口（内部 rawId = false，会做 -500 归一）
     *
     * @return  > 0 命中的动作 ID；0 画的是直线（无效简单手势）；-1 未命中/库未就绪
     */
    fun recognizeForTrigger(gesture: Gesture): Int = recognize(gesture, rawId = false)

    /** FV: q1.o.H(Gesture, boolean) —— 逐行等价 */
    fun recognize(gesture: Gesture, rawId: Boolean): Int {
        try {
            ensureLoaded()
            if (!isReady) return -1
            val probe = FvGestureInfo()
            FvGestureClassifier.classify(0, gesture, probe, lineTol)
            if (probe.isStraightLine) return 0                    // ★ 直线手势：返回 0

            var match: FvGestureInfo? = null
            if (probe.isSimple) {
                // 通道 A：方向对匹配（与尺度/位置无关）
                match = infos.firstOrNull {
                    it.isSimple && it.dir1 == probe.dir1 && it.dir2 == probe.dir2
                }
            } else {
                // 通道 B：平台识别器
                val predictions: ArrayList<Prediction>? = mainStore.recognize(gesture)
                if (predictions == null || predictions.isEmpty()) return -1
                var best = 0.0
                var bestName: String? = null
                for (p in predictions) {
                    if (p.score.isNaN()) continue
                    Log.d(TAG, "score:${p.score}, name ${p.name}")
                    if (p.score < scoreThreshold.toDouble()) continue
                    if (p.score > best) {
                        bestName = p.name
                        best = p.score
                    }
                }
                if (!bestName.isNullOrEmpty()) match = infoByName(bestName)
            }
            val found = match ?: return -1
            var id = found.id
            if (!rawId && isMirrorId(id)) id -= MIRROR_OFFSET
            return id
        } catch (e: Exception) {
            return -1
        }
    }

    // ------------------------------------------------------------ 增删改

    /**
     * FV: q1.o.b(Gesture, Gesture) —— 返回新 ID，失败返回 -1
     *
     * @param action 该手势要执行的动作（动作负载随手势一起落盘；无有效动作会被拒绝，与 FV
     *   的「执行不能为空」一致）
     */
    fun add(gesture: Gesture, mirror: Gesture?, action: GestureAction): Int {
        ensureLoaded()
        if (!action.isEffective()) return -1
        val id = allocId()
        val info = FvGestureInfo()
        FvGestureClassifier.classify(id, gesture, info, lineTol)
        info.action = action
        removeEntry(info)
        writeEntry(info, gesture)
        infos.add(info)

        var mirrorInfo: FvGestureInfo? = null
        if (mirror != null) {
            // 镜像条目只存手势形态，不存动作（读取时经 actionOf() 归一到基 ID）
            mirrorInfo = info.mirror()
            removeEntry(mirrorInfo)
            writeEntry(mirrorInfo, mirror)
            infos.add(mirrorInfo)
        }
        if (save()) return id

        infos.remove(info); removeEntry(info)
        mirrorInfo?.let { infos.remove(it); removeEntry(it) }
        return -1
    }

    /** FV: q1.o.O(int, Gesture, Gesture) */
    fun update(id: Int, gesture: Gesture, mirror: Gesture?, action: GestureAction): Boolean {
        ensureLoaded()
        if (!action.isEffective()) return false
        val info = infoById(id) ?: return false
        val fresh = FvGestureInfo()
        FvGestureClassifier.classify(0, gesture, fresh, lineTol)
        removeEntry(info)
        info.isSimple = fresh.isSimple
        info.dir1 = fresh.dir1
        info.dir2 = fresh.dir2
        info.splitIndex = fresh.splitIndex
        info.action = action
        writeEntry(info, gesture)

        val mirrorInfo = infoById(id + MIRROR_OFFSET)
        removeEntry(id + MIRROR_OFFSET)
        if (mirror != null) {
            if (mirrorInfo != null) {
                mirrorInfo.isSimple = info.isSimple
                mirrorInfo.dir1 = mirrorDir(info.dir1)
                mirrorInfo.dir2 = mirrorDir(info.dir2)
                writeEntry(mirrorInfo, mirror)
            } else {
                val created = info.mirror()
                writeEntry(created, mirror)
                infos.add(created)
            }
        } else if (mirrorInfo != null) {
            infos.remove(mirrorInfo)
        }
        return save()
    }

    /** FV: q1.o.J(int) —— 同时删除 id 与 id+500 */
    fun delete(id: Int): Boolean {
        ensureLoaded()
        val info = infoById(id) ?: return true
        val mirrorInfo = infoById(id + MIRROR_OFFSET)
        infoById(id)?.let { removeEntry(it) }
        mirrorInfo?.let { removeEntry(it) }
        infos.remove(info)
        mirrorInfo?.let { infos.remove(it) }
        return save()
    }

    /** FV: q1.o.K(q1.o$a) */
    private fun removeEntry(info: FvGestureInfo) {
        if (info.isSimple) simpleStore.removeEntry(info.name) else mainStore.removeEntry(info.name)
    }

    /** 按 ID 删除 store 中对应 entry（用于 update 前清理旧版本） */
    private fun removeEntry(id: Int) {
        val info = infoById(id)
        if (info == null) {
            // 元数据还没建立时按默认假设：两个库都清一遍
            mainStore.removeEntry(id.toString())
            simpleStore.removeEntry(id.toString())
            return
        }
        removeEntry(info)
    }

    /** FV: q1.o.d(q1.o$a, Gesture) */
    private fun writeEntry(info: FvGestureInfo, gesture: Gesture) {
        if (info.isSimple) simpleStore.addGesture(info.name, gesture)
        else mainStore.addGesture(info.name, gesture)
    }
}

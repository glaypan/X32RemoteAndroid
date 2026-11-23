package com.x32remote.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject

/**
 * 效果器图鉴（v1.8）。
 *
 * 数据来自 `X32Simulator/tools/export_fx_gallery.py` 导出的 `assets/fx/fxlib.json`，
 * 图片是**真机界面截图**：X32 侧由官方编辑器 X32-Edit 4.4 离线模式逐算法渲染后截取，
 * WING 侧取自官方手册站的 FX 选择页。图片只用来「认这是哪个效果器」，不参与参数运算。
 *
 * 资源目录结构与 JSON 的 img 字段一致（x32/HALL.webp、wing/premium/hall-reverb.webp），
 * 所以取图就是 assets.open("fx/" + img)。
 */
object FxGallery {

    /** 一条图鉴条目。X32 有 code/cn/en/n/heavy，WING 只有 slug/name，合成一个结构。 */
    data class Item(
        val id: String,
        val title: String,      // 主标题（X32 中文名 / WING 真台英文名）
        val sub: String?,       // 真台面板上印的英文名
        val tag: String?,       // 右下角小标签（仅 FX1-4 / N 参数）
        val heavy: Boolean,
        val img: String
    )

    data class Group(val id: String, val title: String, val label: String, val items: List<Item>)

    var groups: List<Group> = emptyList()
        private set

    private val byCode = HashMap<String, Item>()
    private val cache = LruCache<String, Bitmap>(96)

    /** 在 Application/Activity 启动时调一次；重复调用无副作用。 */
    fun init(context: Context) {
        if (groups.isNotEmpty()) return
        val app = context.applicationContext
        val root = runCatching {
            JSONObject(app.assets.open("fx/fxlib.json").bufferedReader().use { it.readText() })
        }.getOrNull() ?: return

        val out = ArrayList<Group>()

        root.optJSONObject("x32")?.let { x32 ->
            val items = x32.optJSONArray("items")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val code = o.optString("code").ifBlank { null } ?: return@mapNotNull null
                    val cn = o.optString("cn")
                    val en = o.optString("en")
                    val n = o.optInt("n", 0)
                    val heavy = o.optInt("heavy", 0) == 1
                    val tag = when {
                        heavy -> "仅 FX1-4"
                        n > 0 -> "$n 参数"
                        else -> null
                    }
                    Item(
                        id = code,
                        title = cn.ifBlank { code },
                        sub = en.takeIf { it.isNotBlank() && it != cn },
                        tag = tag,
                        heavy = heavy,
                        img = o.optString("img")
                    ).also { byCode[code] = it }
                }
            } ?: emptyList()
            out += Group("x32", "X32 / M32", x32.optString("label", "X32 效果库"), items)
        }

        // 顺序固定：X32 → WING 高级 → WING 标准；出现新组按字母序追加
        val wing = root.optJSONObject("wing")
        if (wing != null) {
            val known = linkedMapOf("premium" to "WING 高级", "standard" to "WING 标准")
            val keys = ArrayList<String>()
            keys += known.keys.filter { wing.has(it) }
            keys += wing.keys().asSequence().filter { it !in known }.sorted().toList()
            for (k in keys) {
                val g = wing.optJSONObject(k) ?: continue
                val items = g.optJSONArray("items")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val slug = o.optString("slug").ifBlank { null } ?: return@mapNotNull null
                        val name = o.optString("name").ifBlank { slug }
                        Item(
                            id = slug,
                            title = name,
                            sub = null,
                            tag = null,
                            heavy = false,
                            img = o.optString("img")
                        )
                    }
                } ?: emptyList()
                out += Group(k, known[k] ?: k.uppercase(), g.optString("label", k), items)
            }
        }

        groups = out
    }

    /** 按真台算法枚举名取条目（FX 槽卡片用） */
    fun item(code: String?): Item? = if (code.isNullOrBlank()) null else byCode[code]

    /** 按 img 相对路径取位图；缺图返回 null（界面只显示文字）。 */
    fun bitmap(context: Context, rel: String): Bitmap? {
        if (rel.isBlank()) return null
        cache.get(rel)?.let { return it }
        val bmp = runCatching {
            context.assets.open("fx/$rel").use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        cache.put(rel, bmp)
        return bmp
    }
}

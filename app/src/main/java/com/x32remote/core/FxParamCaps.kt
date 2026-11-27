package com.x32remote.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 效果参数**可写性表**：当前控台上哪些算法参数没有地址（界面标"本控台未标定"）。
 *
 * 为什么 App 要单独拉一次：网页端每个参数在 `/api/state` 里自带一个 `w` 布尔字段，
 * 但 App 是 OSC 直连，从来没消费过那份 HTTP 状态流。进效果器详情页时打一次
 * `GET /api/fxcaps` 就够了（几百字节，见 x32sim/httpapi.py）。
 *
 * **拉不到一律当"全部可写"**：连真台 X32 时压根没有这个 HTTP 服务；
 * 若因为请求失败就把滑杆全灰掉，等于凭空把功能关了。宁可让用户拖了没反应
 * （真台会安全地拒掉 NODE NOT FOUND），也不能禁用本来能用的参数。
 *
 * 服务器实现见 `ConsoleProfile.fx_param_caps()`：WING 返回每槽可写的 par 号清单，
 * X32/M32 返回 null（表示"没有这个概念，全可写"）。
 */
object FxParamCaps {

    /**
     * @param all true = 该控台没有"未标定"概念（X32 / M32），一切可写
     * @param slots 槽号 → 可写的 par 号集合；**空集合**代表这个槽一个参数都不能写
     */
    data class Caps(
        val console: String,
        val all: Boolean,
        val slots: Map<Int, Set<Int>>
    ) {
        /** 缺这个槽 → 不猜，放行（服务端本该把 1..N 都列全，缺键说明对端不是本服务） */
        fun allows(slot: Int, par: Int): Boolean {
            if (all) return true
            val set = slots[slot] ?: return true
            return set.contains(par)
        }

        fun blockedCount(slot: Int, pars: List<Int>): Int =
            if (all) 0 else pars.count { !allows(slot, it) }

        /** 控台显示名（提示文案用）；拉不到控台名时退化成 "当前控台" */
        val consoleLabel: String get() = if (console.isEmpty()) "当前控台" else console.uppercase()
    }

    fun baseUrl(host: String, port: Int) = "http://$host:$port"

    /** 同步 GET（调用方自己放在后台线程）。失败抛异常，由调用方决定降级 */
    fun fetch(base: String): Caps = parse(getJson("$base/api/fxcaps"))

    private fun getJson(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection)
        c.requestMethod = "GET"
        c.connectTimeout = 2500
        c.readTimeout = 2500
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw IllegalStateException("HTTP $code: ${txt.take(120)}")
            return JSONObject(txt)
        } finally {
            c.disconnect()
        }
    }

    /**
     * `{"console":"wing","all":false,"slots":{"1":[1,2,5],"2":[]}}`
     *
     * 注意 JSON 的对象键只能是字符串，槽号必须 `toIntOrNull()` 转回来。
     */
    fun parse(root: JSONObject): Caps {
        // 缺 all 字段 → 当"全可写"：老版本服务端没有这个接口时不该误灰
        val all = root.optBoolean("all", true)
        val console = root.optString("console", "")
        val slotsObj = root.optJSONObject("slots") ?: JSONObject()
        val out = HashMap<Int, Set<Int>>()
        for (k in slotsObj.keys()) {
            val n = k.toIntOrNull() ?: continue
            val arr = slotsObj.optJSONArray(k) ?: continue
            val set = LinkedHashSet<Int>()
            for (i in 0 until arr.length()) set.add(arr.optInt(i))
            out[n] = set
        }
        return Caps(console, all, out)
    }
}

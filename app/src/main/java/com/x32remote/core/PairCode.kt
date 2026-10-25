package com.x32remote.core

import java.net.URI
import java.net.URLDecoder

/**
 * 手机 App 的扫码配对码（协议见《共享协议规范》§16.9，服务端 `x32sim/pair.py`）。
 *
 * 为什么不只认网页二维码：
 * 网页码是 `http://<ip>:<web>/`，那个端口是 **HTTP** 的；App 走 OSC/UDP，
 * 默认 10023 且可能被改过。改过端口时两者分叉，而且错得安静——连不上、
 * 也没有任何回包提示。
 *
 * 所以服务端另给一张 `x32remote://pair?v=1&host=…&osc=…&web=…` 的码。
 * 但解析器**同时接受**网页地址与 `host:port`：现场最常见的动作，
 * 就是拿 App 去扫屏幕上那张浏览器二维码。
 */
object PairCode {

    const val DEFAULT_OSC = 10023

    data class Code(val host: String, val osc: Int, val web: Int?) {
        val summary: String
            get() = if (web != null) "$host:$osc（网页 $web）" else "$host:$osc"
    }

    private val HOST_RE = Regex("^[A-Za-z0-9]([A-Za-z0-9.\\-]{0,62})$")

    fun parse(raw: String?): Code? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > 200) return null

        val lower = s.lowercase()
        var host = ""
        var osc = DEFAULT_OSC
        var web: Int? = null

        when {
            lower.startsWith("x32remote://") -> {
                val u = try {
                    URI(s)
                } catch (e: Exception) {
                    return null
                }
                // netloc 只允许空或 pair：别的 host 说明是串了别人的 deep link
                if (!(u.host.isNullOrEmpty() || u.host == "pair")) return null
                val q = query(u.rawQuery)
                host = (q["host"] ?: "").trim()
                if (!q["osc"].isNullOrEmpty()) {
                    val n = q["osc"]!!.toIntOrNull() ?: return null
                    if (!validPort(n)) return null
                    osc = n
                }
                if (!q["web"].isNullOrEmpty()) {
                    val n = q["web"]!!.toIntOrNull() ?: return null
                    if (!validPort(n)) return null
                    web = n
                }
            }

            lower.startsWith("http://") || lower.startsWith("https://") -> {
                val u = try {
                    URI(s)
                } catch (e: Exception) {
                    // 端口越界（如 :99999）在这里就会抛，直接判为无效
                    return null
                }
                host = u.host ?: return null
                val p = u.port
                if (p != -1) {
                    if (!validPort(p)) return null
                    web = p
                }
                osc = DEFAULT_OSC          // 网页地址里没有 OSC 端口，只能按默认
            }

            else -> {
                // host[:port]，允许尾巴带路径（有人会把地址连 / 一起复制）
                val head = s.split("/", limit = 2).first()
                val parts = head.split(":", limit = 2)
                if (parts.size == 2) {
                    host = parts[0].trim()
                    val n = parts[1].toIntOrNull() ?: return null
                    if (!validPort(n)) return null
                    osc = n
                } else {
                    host = head.trim()
                }
            }
        }

        if (!validHost(host)) return null
        return Code(host, osc, web)
    }

    private fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val kv = pair.split("=", limit = 2)
            val k = decode(kv[0])
            val v = if (kv.size > 1) decode(kv[1]) else ""
            if (k.isNotEmpty() && !out.containsKey(k)) out[k] = v
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    fun validPort(p: Int): Boolean = p in 1..65535

    fun validHost(raw: String): Boolean {
        val h = raw.trim()
        if (h.isEmpty() || h.length > 63 || !HOST_RE.matches(h)) return false
        val lower = h.lowercase()
        // 回环地址扫了只会连到手机自己
        if (lower == "localhost" || lower.startsWith("127.") ||
            lower == "0.0.0.0" || lower == "::1"
        ) return false
        val octets = h.split(".")
        if (octets.size == 4 && octets.all { it.toIntOrNull() != null }) {
            return octets.all { (it.toIntOrNull() ?: 999) <= 255 }
        }
        return true
    }
}

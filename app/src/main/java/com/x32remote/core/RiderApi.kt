package com.x32remote.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动增益 / 自动推子：走**电脑端服务**的 HTTP 接口 `/api/rider`。
 *
 * ⚠️ 这不是 OSC 直连：控制律跑在电脑上（它要读电平表、按 50ms 一拍算），
 * 手机只是配置与显示。所以手机上用这个功能时，必须有一台电脑在跑模拟器/服务，
 * 且手机与电脑在同一网段。
 *
 * 协议与网页端完全一致（见 x32sim/rider.py），不做二次抽象，避免两端行为漂移。
 */
object RiderApi {

    data class Chan(
        val ch: Int,
        val on: Boolean,
        val target: Double,
        val targetNow: Double,
        val mode: String,        // fader / gain
        val speed: String,       // slow / medium / fast
        val tap: String,         // post / pre
        val sidechain: Int,
        val level: Double?,      // 平滑后的电平 (dBFS)，无信号为 null
        val applied: Double?,    // 当前执行器 dB
        val curve: Boolean,
        val paused: Boolean
    )

    data class Snapshot(val enabled: Boolean, val channels: List<Chan>)

    private fun open(base: String, path: String, body: String?): JSONObject {
        val c = (URL(base + path).openConnection() as HttpURLConnection)
        c.requestMethod = if (body == null) "GET" else "POST"
        c.connectTimeout = 2500
        c.readTimeout = 2500
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
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

    fun baseUrl(host: String, port: Int) = "http://$host:$port"

    fun fetch(base: String): Snapshot = parse(open(base, "/api/rider", null))

    fun post(base: String, body: JSONObject): Snapshot = parse(open(base, "/api/rider", body.toString()))

    fun parse(root: JSONObject): Snapshot {
        // GET 直接是配置对象；POST 包了一层 {"ok":true,"rider":{...}}
        val obj = root.optJSONObject("rider") ?: root
        val enabled = obj.optBoolean("enabled", true)
        val chs = obj.optJSONObject("channels") ?: JSONObject()
        val out = ArrayList<Chan>()
        for (k in chs.keys()) {
            val c = chs.optJSONObject(k) ?: continue
            val n = k.toIntOrNull() ?: continue
            val target = c.optDouble("target", -18.0)
            out.add(
                Chan(
                    ch = n,
                    on = c.optBoolean("on", false),
                    target = target,
                    targetNow = c.optDouble("target_now", c.optDouble("target", target)),
                    mode = c.optString("mode", "fader"),
                    speed = c.optString("speed", "medium"),
                    tap = c.optString("tap", "post"),
                    sidechain = c.optInt("sidechain", 0),
                    level = if (c.isNull("level")) null else c.optDouble("level"),
                    applied = if (c.isNull("applied")) null else c.optDouble("applied"),
                    curve = c.optBoolean("curve", false),
                    paused = c.optBoolean("paused", false)
                )
            )
        }
        out.sortBy { it.ch }
        return Snapshot(enabled, out)
    }

    fun targetBody(ch: Int, target: Double) = JSONObject().put("channel", ch).put("target", target)
    fun onBody(ch: Int, on: Boolean) = JSONObject().put("channel", ch).put("on", on)
    fun modeBody(ch: Int, mode: String) = JSONObject().put("channel", ch).put("mode", mode)
    fun speedBody(ch: Int, speed: String) = JSONObject().put("channel", ch).put("speed", speed)
    fun removeBody(ch: Int) = JSONObject().put("channel", ch).put("remove", true)
    fun enabledBody(on: Boolean) = JSONObject().put("enabled", on)
}

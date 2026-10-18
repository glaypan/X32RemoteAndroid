package com.x32remote.core

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** 局域网中发现的一台调音台 */
data class ConsoleInfo(
    val ip: String,
    val name: String,
    val model: String,
    val version: String
) {
    val display: String
        get() = buildString {
            append(name.ifEmpty { model.ifEmpty { "X32" } })
            if (version.isNotEmpty()) append(" · $version")
            append("\n$ip")
        }
}

/**
 * 局域网自动发现。
 *
 * 与 X32-Edit 同款做法：向广播地址发 `/xinfo`，调音台回
 * `[ip, name, model, version]`。
 *
 * 两个必须处理的现实问题：
 *  1. **多网卡重复**：同一台设备会从每个网卡各回一次，必须按设备去重。
 *  2. **本机排除**：不要把跑在本机的模拟器之外的自己列出来。
 */
object Discovery {

    fun scan(context: Context? = null, timeoutMs: Int = 1500): List<ConsoleInfo> {
        val found = LinkedHashMap<String, ConsoleInfo>()
        val local = localAddresses()

        // 部分机型上广播需要多播锁才能稳定收到回包
        val wifi = context?.applicationContext
            ?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock("x32scan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }

        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = 150

            val probe = OscCodec.encodeMessage(OscMessage(X32.XINFO))
            val targets = broadcastAddresses()

            for (t in targets) {
                try {
                    sock.send(DatagramPacket(probe, probe.size, InetAddress.getByName(t), X32.DEFAULT_PORT))
                } catch (_: Exception) {
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)

            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)

                    for (m in OscCodec.decode(data)) {
                        if (m.address != X32.XINFO) continue

                        val ip = OscCodec.argAsString(m.args.getOrNull(0))
                            ?: pkt.address?.hostAddress
                            ?: continue
                        val name = OscCodec.argAsString(m.args.getOrNull(1)) ?: ""
                        val model = OscCodec.argAsString(m.args.getOrNull(2)) ?: ""
                        val version = OscCodec.argAsString(m.args.getOrNull(3)) ?: ""

                        // 排除本机回包
                        if (ip in local) continue

                        // 按设备身份去重：同一台设备的多个网卡回复视为同一台
                        val identity = "$name|$model|$version"
                        val info = ConsoleInfo(ip, name, model, version)
                        val existing = found[identity]
                        if (existing == null) {
                            found[identity] = info
                        } else if (existing.ip != ip) {
                            // 同设备多网卡：保留同一 /24 网段内的那个（更可能可达）
                            if (sameSubnet(existing.ip, ip)) found[identity] = info
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // 到 deadline 为止
                } catch (_: Exception) {
                    break
                }
            }
        } finally {
            try {
                sock?.close()
            } catch (_: Exception) {
            }
            try {
                if (lock?.isHeld == true) lock.release()
            } catch (_: Exception) {
            }
        }

        return found.values.toList()
    }

    private fun sameSubnet(a: String, b: String): Boolean {
        val pa = a.split('.')
        val pb = b.split('.')
        return pa.size == 4 && pb.size == 4 && pa[0] == pb[0] && pa[1] == pb[1] && pa[2] == pb[2]
    }

    private fun broadcastAddresses(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast ?: continue
                    if (b is Inet4Address) out.add(b.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) out.add("255.255.255.255")
        return out.toList()
    }

    private fun localAddresses(): Set<String> {
        val out = HashSet<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp) continue
                for (ia in ni.interfaceAddresses) {
                    ia.address?.hostAddress?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}

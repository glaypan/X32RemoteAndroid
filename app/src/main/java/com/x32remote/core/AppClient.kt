package com.x32remote.core

/**
 * 进程内唯一的台面客户端。
 *
 * 效果器详情页是独立 Activity，必须和主界面共用同一条 UDP 连接与同一份状态
 * （各自 new 一个会变成两条连接、两份状态，详情页的写入主界面看不到）。
 */
object AppClient {
    val client = X32Client()
}

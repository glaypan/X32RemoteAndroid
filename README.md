# X32 Remote — Android 客户端

Behringer X32 / Midas M32 数字调音台的 Android 遥控 App（Kotlin + View/XML，OSC over UDP，端口 10023）。

与控制台的共享契约见协议规范仓库：[glaypan/X32Remote-Protocol](https://github.com/glaypan/X32Remote-Protocol)（本工程遵循其中 v1.4 驱动的动作白名单与时间轴语义）。

## 构建

- **路径必须为纯 ASCII**：中文路径下 `assembleDebug` 能过，但测试 worker 的 classpath 会编码错乱，单元测试恒抛 `ClassNotFoundException`（已迁移至 `E:\AI\workbuddy\X32RemoteAndroid`，勿再改回中文路径）
- JDK 17+（构建用 JDK 21）、Android SDK 35、Gradle 由 wrapper 固定 8.11.1（AGP 8.7.3 要求 Gradle ≥ 8.9）

```bash
./gradlew :app:assembleRelease     # release 包
./gradlew test                     # 单元测试
```

## 签名说明

release 签名**不在本仓库内**，需自备两样东西（均已列入 `.gitignore`）：

| 文件 | 说明 |
|---|---|
| `x32release.jks` | 签名密钥库。自用发布用的是独立密钥（CN=X32 Remote，有效期 30 年），与 debug 密钥分离，保证版本升级可覆盖安装 |
| `keystore.properties` | 上面的密钥库密码，`app/build.gradle` 从这里读 |

两样齐全时 `assembleRelease` 产出签名包；缺失时构建不报错，但 release 包**不签名**（构建日志有告警）。用自己的密钥时，改 `keystore.properties` 指向你的库即可。

## 版本

versionName 1.1.0 / versionCode 2，minSdk 26 / targetSdk 35。与服务端规范 v1.4 对齐（12 种卡片动作）；`set` / `osc` 两种新动作未进本端白名单，避免三端配置互不兼容。

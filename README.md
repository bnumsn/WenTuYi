# 文图易

文图易是一个 Android 输入法 + 跨平台加密协议工具集。当前版本 (v0.5.2 / **WTY3**) 在 Android 内置软键盘上提供普通输入、文字转普通图片、加密文字、加密二维码；仓库同时提供 JVM 共享协议层、Windows/Linux CLI、Apple 输入法外壳代码，用于让非 Android 平台先互通同一套 `WTY3` / `WTYID1` / `WTYB1` / `WTYP1` 协议。v0.5 系列引入了基于 X25519 公钥的"扫码加好友"端到端加密通道，作为旧版本"共享密钥"模式的安全升级。

## 当前范围

- Android 原生 IME 是当前完整产品实现；除 `com.google.zxing:core` 与 `org.bouncycastle:bcprov-jdk18on` 两个**纯 Java**库外不引入 AndroidX。
- `shared-protocol` 是纯 JVM 协议核心，供桌面 CLI 和后续平台外壳复用。
- `desktop-cli` 提供 Windows/Linux 可运行命令行，用于加密、解密、身份、SAS、会话密钥和 QR 分片 smoke test。
- `platforms/apple` 提供 iOS Keyboard Extension / macOS InputMethodKit 的 Swift Package 外壳；平台受限能力见 [docs/CROSS_PLATFORM.md](docs/CROSS_PLATFORM.md)。
- `platforms/linux/ibus` 提供 Linux IBus 输入法入口；`platforms/windows/wentuyi-hotkey.ps1` 提供 Windows 登录会话里的全局热键输入桥。
- Kotlin 1.9 + Coroutines；Java 17。
- 系统输入法服务：`TextImageImeService`（由 `KeyboardUi` + `SendController` 组合而成）。
- 键盘模式：像普通键盘一样直接写入当前输入框，支持 `中/En` 在中文拼音和英文直输间切换。
- 中文输入：字母进入拼音候选栏，点候选或按空格上屏首选词；退格优先删拼音缓冲。
- 键盘动作：候选栏右侧提供普通文字图片、加密文字、加密二维码三个入口；动作会读取当前输入框文字或选中文本。加密文字在加密成功后替换选中文本/当前输入框内容；图片优先 `commitContent`，失败后走系统分享面板 fallback。长按加密入口可切换共享密钥或联系人会话密钥。
- 输入法视觉接近 Gboard 的 Material 键盘样式。
- Debug 构建提供"键盘本地测试"页，用于验证 `图` / `密图` 不经过社交应用也能插入图片。
- 加密算法：**AES-256-GCM** + 12 字节随机 IV + 16 字节随机 salt + **Argon2id** 派生密钥 (m=32 MB, t=3, p=1)；版本/类型/salt/IV 作为 AAD 绑定到密文，防止格式篡改与类型混淆。仍兼容解密 v1/v2 的 PBKDF2-HmacSHA256 旧密文。
- 加密图传输：**Reed-Solomon 纠错的标准 QR Code**（ZXing, ECC 级 H），替换了旧版易被 JPEG 压缩破坏的自研 `WTYBW2` 黑白栅格；长 payload 由文图易自有的 `WTYP1|id|N|T|chunk` 文本包装拆分到多张 QR，接收方按序号重组。
- 身份与密钥：
  - 主 App 可生成 X25519 身份码（公钥 + 名字打包成单张 QR），通过"扫码 / 导入二维码"添加联系人；
  - 双方扫到对方身份码后会显示 8 位 **SAS 校验码**，建议口头核对一致以防中间人；
  - 联系人列表保存于 `WentuyiSettings`（私钥由 Android Keystore-resident AES-GCM 包裹）。
- 旧"共享密钥"路径保留兼容；密钥读取失败时**硬失败**，不再回流明文。

## 项目结构

```
app/src/main/java/com/wentuyi/app/
  TextImageImeService.kt      # IME service shell (~350 行)
  KeyboardUi.kt               # 键盘按钮工厂 + 主题
  SendController.kt           # 发送动作 + commit/share fallback + 目标漂移防护
  PinyinCandidates.java       # 拼音词表（保留 Java，无逻辑变动）

  MainActivity.kt             # 主 App hub
  KeyManagementActivity.kt    # 身份码 + 联系人 + 共享密钥
  ScanActivity.kt             # 从图库导入二维码 → 自动判别身份码 / 加密内容
  DecryptActivity.kt          # 处理 ACTION_SEND / 选图 / 剪贴板解密
  KeyboardTestActivity.java   # Debug 本地测试页（保留 Java）

  SecurePayloadCodec.kt       # WTY3 加密；兼容 v1/v2 解密
  KeyExchange.kt              # X25519 keypair + ECDH + SAS + 联系人 JSON
  WentuyiSettings.kt          # 共享密钥 + 身份私钥的 Keystore 包裹存储
  CryptoUtils.kt              # Argon2id / HKDF / Base32 helpers
  TextImageCodec.kt           # QR encode/decode + multi-QR 拆分
  ImageStore.kt               # 缓存 PNG 写入 + 24h LRU 清理
  ImageContentProvider.kt     # content:// 提供者；query 支持 _data / MIME_TYPE
  BitmapUtils.kt              # 共享的图片 IO + 尺寸限制
  IntentHelpers.kt            # 选图 / 分享 helpers

shared-protocol/
  src/main/kotlin/com/wentuyi/protocol/
    SecurePayloadCodec.kt     # JVM WTY1/WTY2/WTY3 文本协议
    KeyExchange.kt            # JVM X25519 身份、备份码、SAS
    PayloadChunks.kt          # JVM WTYP1 文本分片

desktop-cli/
  src/main/kotlin/com/wentuyi/cli/WentuyiCli.kt

platforms/
  apple/                      # Swift Package: iOS 键盘外壳 + macOS InputMethodKit 外壳
  linux/                      # CLI wrapper + IBus engine + 远程 smoke 脚本
  windows/                    # PowerShell CLI wrapper + hotkey bridge + package smoke
```

## 加密格式 (WTY3)

文本载荷形如 `WTY3:` + Base64(header || ciphertext)。Header 31 字节：

| 偏移 | 大小 | 含义 |
|---|---|---|
| 0  | 1  | 版本号 = `0x03` |
| 1  | 1  | 类型 (1=文本, 2=图像, 3=分页图像, 4=图像分片) |
| 2  | 1  | 密钥模式 (0=Argon2id passphrase, 1=HKDF-SHA256 over 32-byte session key) |
| 3  | 16 | salt |
| 19 | 12 | IV |

整段 header 作为 GCM AAD，AES-256-GCM 输出包含 16 字节认证标签。

X25519 公钥交换：双方扫描对方身份码后通过 ECDH 得到 32 字节 shared secret，再用 HKDF-SHA256（salt = 排序拼接的两公钥）派生会话密钥；该密钥直接以"模式 1"喂给同一份 `SecurePayloadCodec`。`KeyExchange.shortAuthString` 用 HKDF 派生出 8 位 SAS 供双方口头核对。

## 构建

跨平台版本规划和状态见 [docs/CROSS_PLATFORM.md](docs/CROSS_PLATFORM.md)。Android 是完整 IME；非 Android 平台当前先提供协议 CLI 和平台输入法外壳。

桌面协议 CLI：

```bash
./gradlew :desktop-cli:installDist
desktop-cli/build/install/desktop-cli/bin/desktop-cli help
```

Linux 远程 smoke：

```bash
platforms/linux/test-remote.sh user@192.168.10.16
```

Linux IBus 安装入口见 [platforms/linux/README.md](platforms/linux/README.md)。

Windows 本机 smoke（在 Windows 主机 PowerShell 中运行）：

```powershell
platforms\windows\test-local.ps1
```

Windows zip 包 smoke / 热键桥 / 便携 JRE 说明见 [platforms/windows/README.md](platforms/windows/README.md)。

Apple 代码位于 `platforms/apple`；需要 macOS + Xcode/SwiftPM 构建 iOS Keyboard Extension 和 macOS InputMethodKit host。

Android debug：

```bash
./gradlew :app:assembleDebug
```

Release 构建启用 R8 压缩混淆：

```bash
./gradlew :app:assembleRelease
```

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

设备端 smoke test（覆盖 v3 加密、ECDH 对称、QR 经 JPEG 重压缩后仍可解码、多 QR 拆分重组、ImageStore/ImageContentProvider 读回等）：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

也可以手动安装 APK 后运行保留的 legacy instrumentation：

```bash
adb shell am instrument -w com.wentuyi.app.test/com.wentuyi.app.WentuyiSmokeInstrumentation
```

启用并切换文图易键盘：

```bash
adb shell ime enable com.wentuyi.app/.TextImageImeService
adb shell ime set com.wentuyi.app/.TextImageImeService
```

## 安全模型与已知限制

**安全性保证**：
- 静态保护：AES-256-GCM AEAD + Argon2id (m=32 MiB, t=3, p=1)，header 作 GCM AAD 绑定 version/type/key-mode/salt/IV。
- 身份认证：X25519 公钥指纹（SHA-256[..8] Base32）+ 8 位 HKDF SAS 供双方口外核对。
- 端到端：会话密钥由双方公钥 ECDH 后 HKDF-SHA256 派生；不经任何服务器。

**⚠ 重要：无前向保密 (Forward Secrecy / PFS)**
- 当前 X25519 是长期身份密钥；同一对身份生成的会话密钥**对所有消息都相同**。
- 含义：如果任何一方的私钥被攻破，**所有过往加密消息都可被回溯解密**。
- 想要 PFS 必须实施 Double Ratchet（Signal 协议），列在 v0.6+ 路线图。
- 在此之前：身份私钥的保护就是会话历史的保护。**务必抄写身份备份码并离线保管**。

**其他已知限制**：
- QR Code 解码已能容忍 JPEG q=80 的重压缩（smoke test 覆盖），但极低质量 (q≤40)、严重裁剪、二次摄屏仍可能失败 — 真机逐项验证微信/QQ/钉钉/飞书/Telegram/WhatsApp 的实际表现，并补充兼容性矩阵。
- 加密大图仍需要切多张 QR（每张 ≤ ~800 字节 payload）；v0.5 起 chunk-id 由 SHA-256(payload) 派生，重组时校验，可阻止 chunk 拼接攻击。
- ScanActivity 当前只从相册/系统图片选择器读取 — 实时摄像头预览需要 `android.hardware.camera2` 自实现（避免引入 CameraX/AndroidX）；待 v0.6 评估。
- Debug 构建保留默认 passphrase 仅用于开发体验；Release 构建必须先保存身份码或共享密钥。
- X25519 私钥目前仅靠 Keystore-wrapped SharedPreferences 保护；可考虑直接用 `KeyProperties.KEY_ALGORITHM_EC` 的硬件支持密钥（API 31+）。

## 版本兼容

- v3 (`WTY3:`) 是当前默认输出格式。
- v2 (`WTY2:`) 与 v1 (`WTY1:`) 文本载荷仍可被解密（PBKDF2 路径保留）。
- v2 自研的 `WTYBW2 / Dense / Grid` 黑白加密图**不再支持读取**（自研栅格不可救药），如有历史图片请用 v0.2 解密导出明文后用 v3 重新加密。
- v0.4 身份备份码 (64 字节) 与 v0.5 备份码 (68 字节 + CRC32) 都可被 v0.5 恢复。

## v0.5 修复了 v0.4 留下的隐患

1. **剪贴板自清除跟随 IME 生命周期一起死** — 改用进程级 Handler，60s 计时器活过 IME 销毁。
2. **私钥不清零** — `KeyExchange.deriveSharedSecret` / `shortAuthString` 用完 ECDH 中间产物立刻 wipe；备份对话框 dismiss 时 EditText 清零。
3. **KeyboardTestActivity exported=true** — 改为 false，去掉 release 包攻击面。
4. **`fieldId` 不可靠** — 微信/Telegram 全填 0 导致目标漂移检测失效；改为 IME 内部 sessionId 计数器。
5. **decryptPayloadAuto 重复实现** — 提取 `MessageDecryptor` 单源；错误分 `UNKNOWN_FORMAT / SHARED_KEY_MISMATCH / CONTACT_NOT_FOUND / NO_IDENTITY` 等明确状态。
6. **WTYB1 备份码无 checksum** — 加 4 字节 CRC32，单字符错可立即定位；同时剥离 NBSP/零宽字符。
7. **WTYP1 chunk-id 是随机 35 bit** — 改为 SHA-256(payload)[..5] 派生，重组后用 expectedId 校验防 chunk 拼接窜话。
8. **目标选择只能循环点击** — 改为 AlertDialog 单选菜单，多联系人时可直接定位。
9. **Onboarding 缺心智模型** — 末步弹"私钥丢失 = 永久失联 / 泄漏 = 被冒充"全屏强警告。
10. **PFS 缺乏告知** — README、Onboarding、备份对话框三处明示"无前向保密"。
11. **Argon2id m=32 MiB / t=3 偏弱** — 保留当前参数（OWASP 2023 下限），等 v0.6 引入 v4 envelope 时一并升级到 m=64 / t=4 并把参数写入 header。

## v0.5.2 修复（Codex + Claude 联合评审）

1. **联系人加密静默降级为共享密钥** — `resolveSendTarget` 在所选联系人消失或身份私钥不可读时会悄悄回落到共享密钥加密。现改为 **fail-closed**：返回 `SendTarget.Unavailable` 并拒绝发送，提示用户重新选择目标，绝不把"发给已验证联系人"降级成"人人可解"。
2. **加密二维码走分享 fallback 时原明文残留输入框** — `SendController.deliverImages` 仅在 `commitContent` 成功路径清空原文；现在分享路径也会先清空匹配的明文，分享失败再恢复，杜绝误发明文。
3. **目标漂移锚点加入 `fieldId`** — 在 `packageName + sessionId` 基础上追加 `EditorInfo.fieldId`（填 0 的 App 行为不变，填值的 App 多一道校验，只收紧不放松）。
4. **图片 URI 授权过宽** — 去掉给"所有能处理分享 intent 的包"预授权的逻辑，改为只靠 intent 的 `FLAG_GRANT_READ_URI_PERMISSION`（+ 直达包的定向授权），缩小缓存 PNG 的可读面。
5. **SAS 由 6 位升到 8 位** — MITM 伪造碰撞概率从 ~1e-6 降到 ~1e-8（app 与 shared-protocol 两份实现同步）。
6. **shared-protocol 与 app 协议解析不一致** — shared 版现在同样解包/校验 `WTYIPG1` 分页与 `WTYICH1` 分片，并补齐对应编码器，跨平台行为统一（新增 round-trip 测试）。
7. **身份备份码复制到剪贴板** — 复制后 60s 自动清除（仅当剪贴板内容仍是该备份码时），减少被剪贴板嗅探的窗口。
8. **legacy WTY2/WTY1 type 字节未认证** — 属旧格式固有限制（无 AAD），无法在不破坏既有密文的前提下补认证；仅影响兼容解密路径（类型混淆/DoS，非伪造），已在代码注释中明确标注。v3 已通过 AAD 绑定彻底关闭。
9. **无前向保密 (PFS)** — 设计限制，需 Double Ratchet，维持在 v0.6 路线图，本次未改动。

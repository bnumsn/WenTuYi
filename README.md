# 文图易

文图易是一个 Android 输入法 + 跨平台加密协议工具集。当前版本 (v0.7.4，Android 版本码 **15** / **WTY4 + WTY5**) 在 Android 内置软键盘上提供普通输入、文字转普通图片、加密文字、加密二维码，并提供 App 内独立输入后加密的页面；仓库同时提供 JVM 共享协议层和 Windows/Linux CLI，让桌面端与 Android 互通同一套 `WTY4` / `WTY5` / `WTYID1` / `WTYB1` / `WTYP1` 协议，并保留 `WTY1`-`WTY3` 兼容解密。v0.5 系列引入了基于 X25519 公钥的"扫码加好友"端到端加密通道，v0.6 系列进一步引入联系人消息的 Double Ratchet 前向保密。

**各平台成熟度不一样，按这个读**：Android 功能闭环较完整，仍需真机兼容性与安全验证；Windows/Linux 是**协议 CLI + 输入桥**（发送 WTY4/WTY5，兼容解密 WTY1–5，但不是完整系统输入法）；`platforms/apple` 目前**只有 UI 外壳、没有密码学实现**（`WentuyiCryptoBackend` 是个空 protocol），iOS/macOS 还不能用。

## v0.7.4 安全修复与升级说明

v0.7.4 汇集本轮安全与可靠性修复，正常 WTY4/WTY5 密文格式及会话密钥派生保持兼容。发布说明见 [docs/releases/v0.7.4.md](docs/releases/v0.7.4.md)；发布前仍须完成 CI 门禁。

- 桌面 profile 与原始 `ratchet-*` 命令在跨进程锁内完成读取、加解密和写入；状态经同步写入、原子替换成功后才输出结果，保存失败不输出密文。会话文件绑定双方公钥，更换联系人公钥或本机身份时清除旧会话。
- **桌面升级需要重置旧会话文件**：旧文件没有双方身份绑定，当前实现拒绝直接使用。profile 用户先核对完整安全码并执行 `peer-verify`，再对相应联系人执行 `peer-reset --peer NAME`；使用 `--state FILE` 的用户用原身份和对方公钥重新执行 `ratchet-init`。完成重置后发送一条新消息恢复通信；旧会话尚未接收的消息可能无法再解密。双方都使用旧桌面状态时，双方先完成重置，再由最后重置的一方先发消息。
- Android 屏幕解密结果改用进程内、单次消费且带请求标识的结果存储，不再接收外部广播中的“解密结果”。联系人选择绑定稳定指纹，身份、联系人变更与棘轮操作使用同一事务锁，并重新检查实际发送目标。
- **旧明文联系人迁移后必须重新核对 SAS**：迁移保留名称和公钥，清除未经认证的 `verified` 标记；迁移状态锚定于 Keystore，之后拒绝把联系人数据降级为明文 JSON。升级完整安全码后，旧 8 位校验留下的验证状态也会清除；双方需重新核对全部安全码。
- **WTY1–5 密文统一限制为 524,288 个字符（512 Ki 字符，包含前缀与 Base64）**。发送端先检查明文长度：WTY4 最多 393,157 字节，WTY5 最多 393,146 字节；图片页和分块另扣除元数据。超长发送不会推进棘轮状态。二维码仍受最多 32 张、每片正文 800 字符的更小传输限制。
- Argon2 保持正常 WTY4 的 `64 MiB / 4 / 1` 和旧 WTY3 的 `32 MiB / 3 / 1`，同时限制内存、总工作量及实际堆余量，并串行执行 KDF；不再接受超过 64 MiB 或 `memKB × iterations > 262144` 的 WTY4 参数。内存不足时拒绝操作，不降低派生强度。
- **身份认证升级**：安全码改为 `WTY-SAS-v2` 的完整 256 bit（16 组十六进制），旧 Android 验证标记和没有新版认证记录的桌面联系人都必须重新验证。桌面 `peer-add` 只添加待验证联系人；核对全部字符后执行 `peer-verify --peer NAME --code FULL_CODE`。认证记录绑定双方公钥，更换任一身份后失效。
- Android 密图插入失败转分享时保持原文已清除，只有全部交付均失败才恢复；扫描页和键盘保留已消费 WTY5 请求的进程内结果，切换输入框不会把结果写到新目标。QR 逐页生成、保存与回收，避免整组高分辨率图片同时常驻；单 QR 定位失败时补查完整候选，修复有效密集二维码被漏读的问题，多个不同候选会明确报歧义。
- 桌面 stdin 正文保留全部空白与换行；解密正文的 stdout 不添加换行。Windows 发送保留完整载荷，热键支持 profile 联系人，口令文件显式按 UTF-8 读取以兼容 PowerShell 5.1/7；Linux 插入正文通过 stdin 交给 `xdotool`，不进入子进程命令参数。
- 会话重置的新 epoch 严格大于已存 epoch，避免设备时钟回退或对方时钟较快时无法恢复。Android 身份与联系人同时不可读时，密钥管理仍提供明确的恢复/重建入口，不静默替换身份。
- 独立输入页仅在用户选择复制时写入密文剪贴板。**聊天框内转换无法撤回宿主应用已经读到的明文**；如需在交给聊天应用前加密，应在文图易内直接输入，再分享密文，并使用可信输入法。从其他应用分享或粘贴进来的原文也已被来源应用接触。

## 当前范围

- Android 原生 IME 是当前功能基准；除 `com.google.zxing:core` 与 `org.bouncycastle:bcprov-jdk18on` 两个**纯 Java**库外不引入 AndroidX。
- `shared-protocol` 是纯 JVM 协议核心，供桌面 CLI 和后续平台外壳复用。
- `desktop-cli` 提供 Windows/Linux 可运行命令行。推荐用 **profile 模式**：`init` / `peer-add` / `peer-verify` / `send [--peer NAME]` / `receive`，由 CLI 自己选协议（WTY5 棘轮 > WTY4 会话密钥 > 共享密钥），会话状态存在 `WENTUYI_HOME`（默认 `~/.config/wentuyi`，POSIX 文件权限为 0600）。不使用 profile 的底层命令（`encrypt-text` / `session-encrypt` / `ratchet-*` / `encrypted-qr` / `chunk`）仍保留，其中 `ratchet-*` 使用显式状态文件。
- Linux/Windows 的输入桥（`wentuyi-send` / `wentuyi-insert` / IBus 引擎）已接到 `send` / `receive`，因此**支持联系人与前向保密**；此前它们只调用 `encrypt-text`，也就是只有共享密钥一条路——Android 对已验证联系人默认发 WTY5，于是"双方验证得越认真，桌面端越收不到消息"。加 `--peer NAME`（或 `WENTUYI_PEER` 环境变量）即可。
- `platforms/apple` 只是 iOS Keyboard Extension / macOS InputMethodKit 的 Swift 外壳，**尚未接入任何密码学实现**，不可用于真实通信；平台受限能力见 [docs/CROSS_PLATFORM.md](docs/CROSS_PLATFORM.md)。
- `platforms/linux/ibus` 提供 Linux IBus 输入法入口；`platforms/windows/wentuyi-hotkey.ps1` 提供 Windows 登录会话里的全局热键输入桥。
- Kotlin 1.9 + Coroutines；Java 17。
- 系统输入法服务：`TextImageImeService`（由 `KeyboardUi` + `SendController` 组合而成）。
- 键盘模式：像普通键盘一样直接写入当前输入框，支持 `中/En` 在中文拼音和英文直输间切换。
- 中文输入：字母进入拼音候选栏，点候选或按空格上屏首选词；退格优先删拼音缓冲。词库为 `assets/pinyin.txt`（416 个音节 / 每音节最多 24 字 / 约 4.1 万词，按语料词频排序），由 `scripts/gen-pinyin-table.py` 从 pypinyin + jieba（均 MIT）生成并提交入库，构建与 CI 都不依赖 Python。
- 界面跟随系统深色模式（`Palette` + `values-night/`，不引入 AppCompat）；横屏自动压缩行高；IME 依赖系统避让导航栏，额外保留 8dp 底部间距。
- 键盘动作：候选栏右侧提供普通文字图片、加密文字、加密二维码三个入口；动作会读取当前输入框文字或选中文本。加密文字在加密成功后替换选中文本/当前输入框内容；图片优先 `commitContent`，失败后走系统分享面板 fallback。点击目标标签或长按“密图”选择共享密钥或联系人；长按“密文”打开空白的文图易内输入页。
- 输入法视觉接近 Gboard 的 Material 键盘样式。
- Debug 构建提供"键盘本地测试"页，用于验证 `图` / `密图` 不经过社交应用也能插入图片；debug 包还提供仅用于自动化的 `.KeyboardTestDebugActivity` alias，可用 `adb shell am start -n com.wentuyi.app/.KeyboardTestDebugActivity` 远程启动，release 不包含该 alias。
- WTY4 加密算法：**AES-256-GCM** + 12 字节随机 IV + 16 字节随机 salt + **Argon2id** 派生密钥；默认 m=64 MiB / t=4 / p=1，参数写入 header。版本/类型/密钥模式/Argon 参数/salt/IV 全部作为 AAD 绑定到密文。未经认证的 KDF 参数先通过内存、工作量与堆余量预算检查，再派生密钥；参数范围详见 [协议规范](docs/PROTOCOL.md)。仍兼容解密 WTY3（m=32/t=3）及 v1/v2 的 PBKDF2 旧密文。
- 加密图传输：**Reed-Solomon 纠错的标准 QR Code**（ZXing, ECC 级 H），替换了旧版易被 JPEG 压缩破坏的自研 `WTYBW2` 黑白栅格；长 payload 由文图易自有的 `WTYP1|id|N|T|chunk` 文本包装拆分到多张 QR，接收方按序号重组。
- 身份与密钥：
  - 主 App 可生成 X25519 身份码（公钥 + 名字打包成单张 QR），通过"扫码 / 导入二维码"添加联系人；
  - 双方交换身份码后显示完整 **256 bit 安全码**（64 个十六进制字符，分 16 组）；通过当面、电话等可信渠道逐组核对全部字符。不能只比较首尾，也不能沿用旧 8 位校验；
  - 联系人列表与身份私钥都由 Android Keystore-resident AES-GCM 包裹后存入私有 SharedPreferences，保护公钥和 `verified` 标记的完整性。读取失败时拒绝操作，不退化成"空联系人列表"；旧明文列表仅允许在首次迁移时导入，清除其验证标记，迁移后由 Keystore 标记阻止明文降级。此保护不等于抵御已控制应用进程或设备的攻击者。
- 旧"共享密钥"路径保留兼容；密钥读取失败时**硬失败**，不再回流明文。

## 项目结构

```
app/src/main/java/com/wentuyi/app/
  TextImageImeService.kt      # IME 生命周期、输入与动作路由
  KeyboardUi.kt               # 键盘按钮工厂 + 主题
  SendController.kt           # 发送动作 + commit/share fallback + 目标漂移防护
  PinyinEngine.kt             # 拼音查询：assets/pinyin.txt + 前缀匹配 + DP 整句分词
  Palette.kt                  # 浅色/深色两套配色（无 AndroidX，靠 uiMode 解析）

  MainActivity.kt             # 主 App hub
  EncryptActivity.kt          # 独立输入、共享文字加密与显式导出
  KeyManagementActivity.kt    # 身份码 + 联系人 + 共享密钥
  ScanActivity.kt             # 从图库导入二维码 → 自动判别身份码 / 加密内容
  DecryptActivity.kt          # 处理 ACTION_SEND / 选图 / 剪贴板解密
  KeyboardTestActivity.java   # Debug 本地测试页（保留 Java）

  KeyExchange.kt              # Android 身份 / 联系人存储 glue，协议实现委托 shared-protocol
  WentuyiSettings.kt          # 共享密钥 + 身份私钥的 Keystore 包裹存储
  TextImageCodec.kt           # QR encode/decode + multi-QR 拆分
  ImageStore.kt               # QR 逐页保存；待分享图 24h、解密图 10min 到期清理
  ImageContentProvider.kt     # content:// 提供者；query 支持 _data / MIME_TYPE
  BitmapUtils.kt              # 共享的图片 IO + 尺寸限制
  IntentHelpers.kt            # 选图 / 分享 helpers

shared-protocol/
  src/main/kotlin/com/wentuyi/protocol/
    SecurePayloadCodec.kt     # JVM WTY1-4 envelope；WTY4 当前默认输出
    DoubleRatchet.kt          # JVM WTY5 Double Ratchet
    KeyExchange.kt            # JVM X25519 身份、备份码、SAS
    PayloadChunks.kt          # JVM WTYP1 文本分片
    PayloadLimits.kt          # WTY1-5 大小预算与 UTF-8 预检查
    Argon2ResourceBudget.kt    # KDF 串行化、内存与工作量预算

desktop-cli/
  src/main/kotlin/com/wentuyi/cli/WentuyiCli.kt

platforms/
  apple/                      # Swift Package: iOS 键盘外壳 + macOS InputMethodKit 外壳
  linux/                      # CLI wrapper + IBus engine + 远程 smoke 脚本
  windows/                    # PowerShell CLI wrapper + hotkey bridge + package smoke
```

## 加密格式 (WTY4 / WTY5)

> 完整线上格式（WTY1–5 envelope、WTYID1/WTYB1/WTYP1、Double Ratchet 设计、SAS、安全边界）见 [docs/PROTOCOL.md](docs/PROTOCOL.md)——跨平台实现者对照文档。

当前共享密钥 / 会话密钥载荷形如 `WTY4:` + Base64(header || ciphertext)。Header 37 字节：

| 偏移 | 大小 | 含义 |
|---|---|---|
| 0  | 1  | 版本号 = `0x04` |
| 1  | 1  | 类型 (1=文本, 2=图像, 3=分页图像, 4=图像分片) |
| 2  | 1  | 密钥模式 (0=Argon2id passphrase, 1=HKDF-SHA256 over 32-byte session key) |
| 3  | 4  | Argon2id memory KB (uint32 BE；session-key 模式为 0) |
| 7  | 1  | Argon2id iterations（session-key 模式为 0） |
| 8  | 1  | Argon2id parallelism（session-key 模式为 0） |
| 9  | 16 | salt |
| 25 | 12 | IV |

整段 header 作为 GCM AAD，AES-256-GCM 输出包含 16 字节认证标签。已验证联系人发送时优先使用 `WTY5:` Double Ratchet；响应方尚未收到首条 WTY5 前会回退到 WTY4 session-key 路径，并在 UI 中提示本条暂无 PFS。

X25519 公钥交换：双方扫描对方身份码后通过 ECDH 得到 32 字节 shared secret，再用 HKDF-SHA256（salt = 排序拼接的两公钥）派生会话密钥；该密钥直接以"模式 1"喂给同一份 `SecurePayloadCodec`。`KeyExchange.shortAuthString` 使用 `WTY-SAS-v2` 域派生完整 256 bit 安全码，双方通过可信渠道核对全部 16 组。身份二维码与 WTY4/WTY5 密文格式不变。

## 构建

跨平台版本规划和状态见 [docs/CROSS_PLATFORM.md](docs/CROSS_PLATFORM.md)。Android 是完整 IME；非 Android 平台当前先提供协议 CLI 和平台输入法外壳。

CI（`.github/workflows/ci.yml`）包含协议与桌面回归、CLI 冒烟、IBus 自动发现测试、Android 编译/lint/主题检查，以及 **API 29 / API 34 模拟器上的 `connectedDebugAndroidTest`**。发布流程复用设备测试矩阵，任一门禁失败时停止发布。摄像头与第三方聊天应用的真实兼容性仍需真机测试。

本轮已在 **API 34 模拟器**通过 **93 项自动化测试和 11 项 UI 验证**，覆盖身份验证迁移与损坏恢复、WTY5 持久化及生命周期、发送回退、32 页二维码与密集二维码解码回归。真实 Android 设备、真实摄像头和第三方聊天宿主的验收仍未完成；Windows 桌面热键和真实窗口交互仍待验收。本地结果不能代替发布 CI，发布以对应版本标签的全部门禁通过为准。

本地跑协议核心（纯 JVM，几十秒）：

```bash
./gradlew :shared-protocol:test :desktop-cli:test :desktop-cli:installDist
./scripts/cli-smoke.sh          # 用真实 CLI 走两个身份：WTY4 / WTY5 / 乱序 / 失步恢复 / profile 互通 / QR 分片（包含身份验证、正文完整性和会话恢复回归）
```

桌面协议 CLI：

```bash
./gradlew :desktop-cli:installDist
desktop-cli/build/install/desktop-cli/bin/desktop-cli help
```

桌面 profile（与 Android 联系人互通的推荐用法）：

```bash
CLI=desktop-cli/build/install/desktop-cli/bin/desktop-cli
$CLI init                                          # 生成本机身份，存入 WENTUYI_HOME
$CLI whoami                                        # 拿到 publicKey / identityQr 给对方扫
$CLI peer-add --name alice --peer-qr 'WTYID1|...'  # 添加待验证联系人，打印完整安全码
# 通过可信渠道逐组核对双方显示的全部字符，确认一致后：
$CLI peer-verify --peer alice --code 'XXXX XXXX ... XXXX'
$CLI send --peer alice "要发的话"                   # 自动选协议，优先 WTY5
$CLI receive 'WTY5:...'                            # 自动识别协议与发信人
$CLI peer-reset --peer alice                       # 会话失步时的恢复入口
```

`peer-add` 不等于已验证。旧 profile 升级后先重新核对完整安全码，再执行 `peer-verify`，之后才能发送或重置该联系人的会话。`receive` 的解密正文原样输出，不额外补换行；脚本应直接读取 stdout，避免自行 `.trim()`。

`send` 在棘轮尚无发送链时会回落到 WTY4 会话密钥，并在 stderr 明确提示"本条无前向保密"，不会静默降级。`receive` 收到更新的 epoch 会自动重新自举（对方重装），收到已退休的 epoch 则拒绝（防重放）。

输入桥同样支持：`wentuyi-insert.sh --peer alice --encrypt-text -`、`wentuyi-send.ps1 -Peer alice -EncryptText "..."`，IBus 引擎读 `WENTUYI_PEER`。

⚠ 桌面没有 Keystore：`WENTUYI_HOME` 下的 `identity` 就是明文私钥，`peers/*.ratchet` 是明文会话状态。文件写入时置 0600（Windows 无 POSIX 权限时不生效），这是唯一的保护。

底层命令仍在（`ratchet-init` / `ratchet-encrypt` / `ratchet-decrypt` / `ratchet-info`，会话经 `--state` 文件），适合脚本化或不想使用 profile 的场景。它们同样使用锁和原子持久化；文件系统不支持原子替换时拒绝写入。旧版未绑定身份的状态文件需要按上方升级说明重置。

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

**签名**：真钥匙放 `app/keystore.properties`（已 gitignore）或同名环境变量：

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=wentuyi
keyPassword=...
```

未配置时**回落到 debug 密钥**——这样 R8 构建至少是可安装、可测试的（BouncyCastle 与 ZXing 靠反射进入，压缩混淆确实可能把它们打断，不能只靠"编译通过"）。debug 签名的包仅供测试，不可正式分发。

**发布**：推 `v*` tag 触发 `.github/workflows/release.yml`，在干净检出上先跑一遍协议单测 / CLI 冒烟 / 主题守卫 / lint，然后构建并附上 release APK、debug APK、桌面 CLI zip 和 `SHA256SUMS.txt`。设备矩阵与桌面回归也必须通过。仓库配置了 `WENTUYI_KEYSTORE_BASE64` 等 secret 时用正式密钥签名，否则 release 说明里会明确标注是 debug 签名。

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

设备端 smoke test（覆盖 WTY4 加密、WTY5 棘轮、ECDH 对称、QR 经 JPEG 重压缩后仍可解码、多 QR 拆分重组、ImageStore/ImageContentProvider 读回等）：

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

**安全机制**：

- 静态保护：AES-256-GCM AEAD + Argon2id（WTY4 默认 m=64 MiB / t=4 / p=1，派生前检查资源预算），header 作 GCM AAD 绑定 version/type/key-mode/argon 参数/salt/IV。
- 身份认证：完整 256 bit HKDF 安全码（`WTY-SAS-v2`）通过可信渠道核对；短 Base32 指纹仅用于列表识别，不可代替完整安全码。
- 端到端：会话密钥由双方公钥 ECDH 后 HKDF-SHA256 派生；不经任何服务器。
- **前向保密 (PFS)**：v0.6 起，发给**已验证联系人的加密文本和加密二维码**默认走 **WTY5 Double Ratchet**（Signal 式双棘轮）。其历史消息保护依赖发送状态不回滚、旧密钥被清除及下述首次回复条件；不能保护已经被端点读取的明文。

**⚠ PFS 的适用范围与限制**
- **有 PFS**：已验证联系人的**加密文本与加密二维码**（均走 WTY5 棘轮）。
- **暂无 PFS**：① 旧「共享密钥」路径；② 接收方首次回复前发出的**整个首发链**——接收方首次回复前（或响应方在收到首条前回退 WTY4 的消息），链含长期身份密钥成分，对方回复后完整生效（同 Signal 无 prekey 时）。
- 棘轮会话状态以 Keystore 包裹存于本机；身份私钥仍是信任根，**务必抄写身份备份码并离线保管**。
- **会话失步是可恢复的**（v0.6.1）：WTY5 header 带 8 字节会话 epoch。任一方重装 / 清数据 / 用备份码换机后，对端收到**更新的 epoch** 会自动重新自举；若失步的是自己，在「密钥管理 → 重置加密会话」开一个新 epoch 再发一条消息即可，对方无需操作。旧 epoch 的密文会被拒绝，不能被重放进新会话。

**其他已知限制**：

- 聊天框内先输入、再加密时，宿主应用已经有机会读取原文。App 内独立输入可以在分享给聊天应用前加密，但所用输入法仍可读取键入内容；从其他应用粘贴/分享文字也不能撤回来源应用已获得的明文。
- QR Code 解码已能容忍 JPEG q=80 的重压缩（smoke test 覆盖），但极低质量 (q≤40)、严重裁剪、二次摄屏仍可能失败 — 真机逐项验证微信/QQ/钉钉/飞书/Telegram/WhatsApp 的实际表现，并补充兼容性矩阵。
- 加密大图仍需要切多张 QR（每张 ≤ ~800 字节 payload）；v0.5 起 chunk-id 由 SHA-256(payload) 派生，重组时校验，可阻止 chunk 拼接攻击。
- v0.6 起新增 `CameraScanActivity` 实时摄像头扫码（`android.hardware.camera2` + ZXing 自实现，不引入 CameraX/AndroidX）：预览后台线程从 Y 平面解码首个 QR，文本交回 `ScanActivity` 的保留解密操作统一路由。⚠ 该路径依赖真实摄像头，未纳入 CI/instrumentation 自动化，需真机对二维码逐项实测。仍保留相册/图片选择器导入。
- Debug 构建保留默认 passphrase 仅用于开发体验；Release 构建必须先保存身份码或共享密钥。
- X25519 私钥目前仅靠 Keystore-wrapped SharedPreferences 保护；可考虑直接用 `KeyProperties.KEY_ALGORITHM_EC` 的硬件支持密钥（API 31+）。
- 解密产物的落盘窗口：解密出的**明文图片**写入应用私有缓存，进程存活时定时清理，读取或下次启动相关流程时也拒绝/删除超过 10 分钟的文件（密文/待分享图仍为 24 小时）；屏幕解密的**明文文本只存在于进程内存**，不落盘，进程结束即消失。
- 桌面 CLI 没有 Keystore：`--state` 棘轮会话文件与 `WENTUYI_BACKUP` 备份码都是明文私钥材料。会话文件写入时置 0600（Windows 无 POSIX 权限时不生效），但桌面端的密钥保护整体仍弱于 Android。
- **这是"可被识别"的加密**：一大段 `WTY5:` Base64 或一张纯二维码在聊天流里非常显眼，平台无需解密即可识别、标记或限流。协议强度挡不住"此人在用加密工具"这条元信息本身。

## 版本兼容

- v5 (`WTY5:`) header 在 v0.6.1 由 40 字节增至 48 字节（前置 8 字节会话 epoch），当前**不支持旧 40 字节开发格式**。v0.7.4 保持线上 header 不变，但桌面本地状态增加双方身份绑定，旧本地状态需按 [升级说明](#v074-安全修复与升级说明)重置。
- v4 (`WTY4:`) 是当前默认输出格式（Argon2 参数写入 header）；正常历史默认参数保持兼容，超出当前大小或 KDF 预算的载荷会被拒绝。
- v3 (`WTY3:`) 仍可被解密（旧版默认；Argon2 m=32/t=3 硬编码）。
- v2 (`WTY2:`) 与 v1 (`WTY1:`) 文本载荷仍可被解密（PBKDF2 路径保留）。
- v2 自研的 `WTYBW2 / Dense / Grid` 黑白加密图**不再支持读取**（自研栅格不可救药），如有历史图片请用 v0.2 解密导出明文后用 v0.6 重新加密。
- v0.4 身份备份码 (64 字节) 与 v0.5+ 备份码 (68 字节 + CRC32) 都可被当前版本恢复。

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
11. **Argon2id m=32 MiB / t=3 偏弱** — v0.6 已引入 WTY4 envelope，默认升级到 m=64 MiB / t=4 / p=1，并把参数写入 header。

## v0.5.2 / v0.6 修复（Codex + Claude 联合评审）

1. **联系人加密静默降级为共享密钥** — `resolveSendTarget` 在所选联系人消失或身份私钥不可读时会悄悄回落到共享密钥加密。现改为 **fail-closed**：返回 `SendTarget.Unavailable` 并拒绝发送，提示用户重新选择目标，绝不把"发给已验证联系人"降级成"人人可解"。
2. **加密二维码走分享 fallback 时原明文残留输入框** — `SendController.deliverImages` 仅在 `commitContent` 成功路径清空原文；现在分享路径也会先清空匹配的明文，分享失败再恢复，杜绝误发明文。
3. **目标漂移锚点加入 `fieldId`** — 在 `packageName + sessionId` 基础上追加 `EditorInfo.fieldId`（填 0 的 App 行为不变，填值的 App 多一道校验，只收紧不放松）。
4. **图片 URI 授权过宽** — 去掉给"所有能处理分享 intent 的包"预授权的逻辑，改为只靠 intent 的 `FLAG_GRANT_READ_URI_PERMISSION`（+ 直达包的定向授权），缩小缓存 PNG 的可读面。
5. **历史 SAS 由 6 位升到 8 位** — 此旧方案不能抵御非交互身份交换中的双端碰撞搜索，当前已由完整 256 bit 安全码取代；旧验证结论须重新核对。
6. **shared-protocol 与 app 协议解析不一致** — shared 版现在同样解包/校验 `WTYIPG1` 分页与 `WTYICH1` 分片，并补齐对应编码器，跨平台行为统一（新增 round-trip 测试）。
7. **身份备份码复制到剪贴板** — 复制后 60s 自动清除（仅当剪贴板内容仍是该备份码时），减少被剪贴板嗅探的窗口。
8. **legacy WTY2/WTY1 type 字节未认证** — 属旧格式固有限制（无 AAD），无法在不破坏既有密文的前提下补认证；仅影响兼容解密路径（类型混淆/DoS，非伪造），已在代码注释中明确标注。v3 已通过 AAD 绑定彻底关闭。
9. **无前向保密 (PFS)** — 已在 v0.6 引入 WTY5 Double Ratchet；共享密钥、WTY4 session-key fallback 与对端首次回复前发出的全部棘轮消息仍无完整 PFS，UI 会提示适用范围。

## v0.6.2 修复（UI/UX 评审）

1. **拼音引擎无法用于中文输入**（最严重）—— 旧词表是手写的 435 条、每音节仅 1–3 个候选，而**正确的字往往根本不在表里**：`jin` 下没有「今」、`bei` 下没有「北」、`wen` 下没有「问」，于是 `wojintianyaochuqu` 出「我进天要出去」，且整句只给一个候选、无从修正。这直接击穿了"把加密做进输入法、不用切 App"的产品前提——用户打不出中文就会切回原键盘，加密功能也一起切走。现改为 `assets/pinyin.txt`：416 个音节（普通话全部）、每音节最多 24 字、约 4.1 万词，全部按语料词频排序；查询顺序改为「整词 → 本音节单字 → 前缀词 → 整句分词」，并把贪心最长匹配换成按块数罚分的 DP 分词。实测：`wojintianyaochuqu` → 我今天要出去，`womingtianqubeijing` → 我明天去北京，`womenyiqichifan` → 我们一起吃饭。
2. **没有深色模式** —— 主题写死 `Theme.Material.Light`，键盘 15 个颜色常量全是浅色硬编码，活动里另有 29 处 `Color.rgb(...)` 字面量。深色微信上弹出一块纯白键盘是键盘类应用最被诟病的缺陷，而这个 App 按定位就在暗处使用。新增 `Palette` + `values-night/`，两套配色的 9 组前景/背景对比度**均已核算 ≥ 4.5:1**（顺带把沿用的警告色 `#B4641F` 调深到 `#A55916`，原值只有 4.00:1）。图片与二维码生成刻意**不**跟随主题——否则深色下会产出深底图片、二维码对比度失控。
3. **关键触控目标低于 48dp** —— 工具条与解密面板按钮 32dp、候选词 42dp、目标 chip 40dp，全部抬到 48dp。解密面板的「写入/复制/关闭」恰是整个流程最关键的一刻。
4. **解密结果被截断成 3 行且无法展开** —— 改为可滚动、限高，长消息不必复制出去才能读全。
5. **无障碍基本不可用** —— 全部按键补 `contentDescription`（标点按名读而非读字形），模式切换、解密结果、发送状态改为 `announceForAccessibility` 播报。键盘按钮本就不可获焦（IME 不能抢焦点），此前这意味着读屏用户完全收不到反馈。
6. **完全没有横屏适配** —— 横屏时行高压缩到 72%，不再让键盘吃掉整块屏幕。
7. **底部安全区硬编码 34dp** —— 改为读真实 `WindowInsets` 的导航栏 inset，三键导航设备与平板不再有死区。
8. **主页看不出键盘是否启用** —— 新增状态横幅：未启用/未选中时置顶提示并一键跳转。此前用户换过键盘回来，主页毫无提示，只会得出"这 App 坏了"。
9. **反馈双通道与术语过重** —— Toast 从 26 处收敛为仅"需要注意"的消息（失败/拒绝/目标变更），常规确认只走键盘内的状态 chip，不再遮挡输入区；引导第三步从"X25519 公钥 / 前向保密 (Double Ratchet) / 棘轮首条消息"改写为可执行的白话，密码学细节留在密钥管理和备份对话框里。
10. **字母键与面板同色** —— `COLOR_KEY` 定义了却从未被引用，字母键一直用面板色绘制，键位没有可见边界。已改用 `COLOR_KEY`。

## v0.6.1 修复（全仓评审）

1. **棘轮会话失步后永久失联，且无恢复路径** — 任一方重装 / 清数据 / 用备份码换机后本地棘轮状态丢失，会从同一个确定性根密钥重启，而对端根密钥早已推进：此后双向每条消息都 AEAD 失败且**永不恢复**，双方还分不清这和"消息损坏"。唯一出路是两人同时删好友重加，而 UI 从没提示过。现在 WTY5 header 前置 8 字节**会话 epoch**（header 40 → 48 字节）：收到更新的 epoch 自动重新自举，收到已退休的 epoch 拒绝（防旧会话密文重放进活会话），另加「密钥管理 → 重置加密会话」供自己失步时主动开新会话。协议层、app 持久化层与 CLI 三处都有覆盖测试。
2. **联系人列表明文存储** — 当时引入 Keystore AES-GCM 包裹联系人列表，读取失败不退化成空列表。但当时的明文兼容读取仍可被降级绕过；v0.7.4 增加 Keystore 迁移标记，并在迁移旧明文列表时清除 `verified`，要求重新核对完整安全码，详见 [升级说明](#v074-安全修复与升级说明)。
3. **桌面端收不到 Android 发的棘轮消息** — Android 对已验证联系人默认发 WTY5，而 desktop-cli 只实现到 WTY4，"验证得越认真越用不了"。现补齐 `ratchet-init` / `ratchet-encrypt` / `ratchet-decrypt` / `ratchet-info`，会话存于 `--state` 文件（0600），失步恢复规则与 Android 一致。
4. **没有 CI** — 加 `.github/workflows/ci.yml`：协议核心单测 + CLI 端到端冒烟 + Android 编译/lint。另加 `scripts/cli-smoke.sh`，用真实 CLI 二进制跑 13 项断言（SAS 双向一致、WTY4 中文往返、错误密钥拒绝、会话密钥、棘轮双向、乱序、失步恢复、旧 epoch 重放拒绝、QR 分片重组）。
5. **解密明文的落盘窗口** — 屏幕解密的明文文本原本写进 SharedPreferences，无人消费就一直留在磁盘上；改为仅存进程内存，进程结束即消失。解密出的明文图片从 24 小时缩短到 10 分钟（密文/待分享图仍 24 小时），并在解密入口主动清扫。
6. **文档与实际不符** — `platforms/apple` 只有 UI 外壳、没有密码学实现，README 曾把它写得像可用；`protocol-fixtures/README.md` 还在说 codec 被实现了两次（app 侧副本早已删除）。均已改正，并在开头加了各平台成熟度说明。

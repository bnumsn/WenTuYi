# 文图易全平台开发方案

目标不是让所有系统拥有完全相同的输入法能力，而是让所有平台互通同一种文图易协议：`WTY3:` 文本、身份码、联系人 SAS、QR 分片和解密结果一致。系统输入法外壳按平台单独实现。

## 平台矩阵

| 平台 | 可行性 | 输入法形态 | 仓库当前状态 |
|---|---:|---|---|
| Android | 高 | `InputMethodService` | 完整 IME，仍是功能基准 |
| iOS / iPadOS | 中低 | Keyboard Extension + 主 App + Share Extension | 已有 Swift 键盘外壳和共享模型；需 macOS/Xcode 接入真实 crypto backend 后测试 |
| macOS | 中高 | `InputMethodKit` | 已有 Swift `IMKInputController` 外壳；需 macOS/Xcode 打包和系统输入法注册测试 |
| Windows | 中高 | 全局热键输入桥 / 后续 TSF IME | 已有 PowerShell hotkey bridge、便携 JRE 支持和 JVM CLI 包；Windows 测试机已通过 package smoke 和 RDP 交互 UI smoke |
| Linux / BSD | 中 | IBus engine / direct insert helper / Fcitx5 后续可选 | 已有 IBus engine、wrapper、direct insert helper 和远程 smoke 脚本；Linux 测试机已通过 CLI、IBus self-test、Xvfb/GTK Entry UI smoke 和富文本 direct insert smoke |
| Web | 低 | Web 工具或浏览器扩展 | 尚未实现；只承诺后续协议工具，不承诺系统级输入法 |

## 共享协议层

所有平台必须共享以下行为，避免 Android 以外平台产生不兼容密文：

1. `WTY3` envelope：AES-256-GCM、31 字节 header、AAD 绑定 version/type/key-mode/salt/IV。
2. 旧格式解密：继续兼容 `WTY1:` / `WTY2:` 文本载荷。
3. 密钥路径：共享密钥模式使用 Argon2id；联系人模式使用 X25519 + HKDF-SHA256。
4. 身份码：`WTYID1|<name>|<base64-public-key>`。
5. 备份码：`WTYB1-...`，保留 CRC32 校验和 v0.4 兼容读取。
6. QR 传输：单 QR 直接承载 payload；多 QR 使用 `WTYP1|id|N|T|chunk`，id 由 payload hash 派生。
7. 错误语义：区分格式错误、共享密钥不匹配、缺身份、缺联系人、找不到联系人。

## 当前工程结构

Android 代码里的协议逻辑仍保留在 app module 作为基准；新增的 JVM `shared-protocol` 已先覆盖桌面 CLI 所需协议，Apple 侧通过 protocol-injected backend 预留接入点：

```text
shared-protocol/     # JVM WTY1/2/3, WTYID1, WTYB1, WTYP1, X25519/SAS
desktop-cli/         # Windows/Linux 桌面协议 CLI
platforms/
  apple/             # Swift Package: iOS Keyboard Extension + macOS InputMethodKit 外壳
  windows/           # PowerShell wrapper + direct insert helper + hotkey prototype + package smoke
  linux/             # shell wrapper + IBus engine + direct insert helper + 远程 smoke
```

短期不移动 Android module，避免破坏已有 IME；下一步再把 Android 与 `shared-protocol` 对齐到同一份测试向量。

## 分阶段开发

### P0: 协议冻结

- 写 `WTY3`、`WTYID1`、`WTYB1`、`WTYP1` 的跨语言测试向量。
- 每个测试向量包含明文、密钥材料、salt/iv、公钥、密文、QR chunk 文本。
- Android 当前实现必须能读写这些向量。

### P1: Android 基准版

- 保持现有 Android IME 是功能基准。
- 把未验证联系人、目标选择、QR 兼容矩阵继续补强。
- `connectedDebugAndroidTest` 必须真实跑出非 0 测试数。

### P2: 桌面文本优先

- macOS/Windows/Linux 第一版只承诺普通文本输入和 `WTY3:` 密文文本直接插入当前光标/选区。
- 桌面端不能把复制/粘贴作为富文本主链路；Windows 需要 TSF 或不抢焦点的直接插入 helper，Linux 需要 IBus/Fcitx `commit_text`，macOS 需要 InputMethodKit `insertText`。
- QR 图片生成、解密、身份管理放在伴随设置 App；如果平台支持内容插入则直接插入当前位置，否则走系统分享/拖放，不把剪贴板作为默认动作。
- 平台输入法只调用共享协议层，不单独实现密码学。

### P3: iOS 受限版

- Keyboard Extension 只做文本输入和密文文本插入。
- 主 App 管理身份、联系人、备份码、QR 展示。
- Share Extension 负责从聊天 App 分享来的密文/图片解密。
- 不承诺安全输入框、电话输入框、禁用第三方键盘的 App 内可用。

### P4: QR/图片增强

- 桌面平台通过原生内容插入、文件分享面板或拖放处理 QR 图片；剪贴板只能作为显式手动 fallback，不能作为默认发送流程。
- iOS 通过主 App/分享扩展处理 QR 和图片，键盘扩展不作为主链路。
- 每个平台维护兼容性矩阵。

## 不做的承诺

- 不承诺所有平台都能像 Android 一样直接向宿主 App 插入图片。
- 不承诺 iOS 在 secure text field 或禁用第三方键盘的 App 内可用。
- 不把 Web 版本包装成系统输入法。
- 不在各平台分别发明新协议；所有平台必须互通 `WTY3`。

## 下一步开发入口

1. 新增跨平台测试向量文档和 fixtures。
2. 把 Android 的 `SecurePayloadCodec`、`KeyExchange`、`TextImageCodec` 里与 Android API 无关的部分标记为待抽取共享层。
3. 先做 macOS 或 Windows 的密文文本 MVP；这两个平台比 iOS 更接近完整系统输入法能力。

## 当前落地状态

- `shared-protocol`：已新增纯 JVM 协议核心，覆盖 `WTY3` 文本加密/解密、`WTY1/WTY2` 文本解密、X25519 身份、SAS、备份码、`WTYP1` 文本分片。
- `desktop-cli`：已新增 Windows/Linux 可运行的桌面协议 CLI，用于先验证非 Android 平台的协议互通。
- Linux：已新增 `platforms/linux/wentuyi-cli`、`platforms/linux/wentuyi-insert.sh`、`platforms/linux/ibus/wentuyi_ibus.py`、`platforms/linux/install-ibus.sh`、`platforms/linux/test-remote.sh`、`platforms/linux/ui-smoke.sh` 和 `platforms/linux/ui-rich-smoke.sh`；测试机已安装 Java 17，远程脚本通过共享密钥、X25519 session-key、IBus self-test、direct insert self-test 和 GTK 输入框普通文本 UI smoke。GTK `TextView` 富文本 direct insert smoke 已通过：`wentuyi-insert.sh` 可不使用剪贴板把 `WTY3:` 直接插入富文本光标位置，再直接解密回明文并保留前后格式。GTK `TextView` 对 synthetic key event 进入 IBus engine 的路径在 Xvfb 中不稳定，因此富文本默认走 direct insert helper。
- Windows 测试机：SMB/SCM 可达；CLI zip、PowerShell 脚本和便携 JRE zip 已上传到 `C:\Temp\wentuyi`，package smoke 已通过。
- Windows：已新增 `platforms/windows/wentuyi-cli.ps1`、`wentuyi-insert.ps1`、`wentuyi-hotkey.ps1`、`install-hotkey.ps1`、`test-local.ps1`、`test-package.ps1`、`ui-smoke.ps1`、`ui-rich-smoke.ps1` 和 `ui-direct-insert-smoke.ps1`；支持系统 Java 17+ 或同目录 `jre-windows.zip`，RDP 交互桌面普通文本 UI smoke 已验证 Notepad 选中文本加密/解密，包自检覆盖直接 Unicode 插入 helper。RichTextBox 直接插入 UI smoke 已通过：`wentuyi-insert.ps1 -TargetHwnd` 可把 `WTY3:` 直接插入目标富文本位置，再直接替换回明文并保留前后格式。RichTextBox hotkey/clipboard UI smoke 也已真实运行，结论是 PowerShell clipboard hotkey 只能保留为普通文本兼容桥，富文本目标必须转 TSF/目标 HWND 直插。
- Apple：已新增 `platforms/apple` Swift Package，包含共享键盘模型、iOS `UIInputViewController` 外壳、macOS `IMKInputController` 外壳；当前 crypto backend 是注入接口，需在 macOS/Xcode 环境接入真实协议实现并编译测试。

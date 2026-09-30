# 文图易全平台开发方案

目标不是让所有系统拥有完全相同的输入法能力，而是让所有平台互通同一种文图易协议：当前默认 `WTY4:` 文本、已验证联系人 `WTY5:` 棘轮消息、身份码、联系人 SAS、QR 分片和解密结果一致；`WTY1`-`WTY3` 仅作为兼容解密保留。系统输入法外壳按平台单独实现。

本文对应 v0.7.5（Android 版本码 16）。正常线上格式不变，桌面旧会话文件需要重置，Android 旧明文联系人及旧 8 位验证迁移后需要重新核对全部 16 组安全码；桌面须先 `peer-verify` 再重置会话；详见 [README 升级说明](../README.md#v075-安全修复与升级说明)。

## 平台矩阵

| 平台 | 可行性 | 输入法形态 | 仓库当前状态 |
|---|---:|---|---|
| Android | 高 | `InputMethodService` + App 内独立输入 | 功能闭环较完整，仍是功能基准；需真机验证，独立输入用于在交给聊天应用前加密 |
| iOS / iPadOS | 中低 | Keyboard Extension + 主 App + Share Extension | **仅 UI 外壳，无密码学实现**（`WentuyiCryptoBackend` 是空 protocol，调用返回 `cryptoBackendMissing`）。不可用于真实通信 |
| macOS | 中高 | `InputMethodKit` | **仅 UI 外壳，无密码学实现**，同上。需 macOS/Xcode 打包和系统输入法注册测试 |
| Windows | 中高 | 全局热键输入桥 / 后续 TSF IME | PowerShell hotkey bridge + 便携 JRE + JVM CLI 包；桥接已接到 `send`/`receive`，**支持联系人与 WTY5 前向保密**（`-Peer NAME`）；已有版本通过 package smoke 和 RDP 交互 UI smoke；本轮 Windows CI 门禁覆盖真实 OS 的 CLI 与桥接自动化，桌面热键和真实窗口交互仍待验收 |
| Linux / BSD | 中 | IBus engine / direct insert helper / Fcitx5 后续可选 | IBus engine + wrapper + direct insert helper + 远程 smoke；桥接已接到 `send`/`receive`，**支持联系人与 WTY5**（`--peer NAME` 或 `WENTUYI_PEER`）；Linux 测试机已通过 CLI、IBus self-test、Xvfb/GTK Entry UI smoke 和富文本 direct insert smoke |
| Web | 低 | Web 工具或浏览器扩展 | 尚未实现；只承诺后续协议工具，不承诺系统级输入法 |

## 共享协议层

所有平台必须共享以下行为，避免 Android 以外平台产生不兼容密文：

1. `WTY4` envelope：AES-256-GCM、37 字节 header、AAD 绑定 version/type/key-mode/Argon 参数/salt/IV。
2. `WTY5` envelope：已验证联系人消息优先使用 Double Ratchet；拿到发送链前可回退 WTY4 session-key 路径并必须对用户可见。header 48 字节，前 8 字节为会话 epoch：收到更新的 epoch 可按其重新自举试解，只有认证成功后才保存；收到已退休的 epoch 必须拒绝（防重放），并必须提供"重置加密会话"入口。任何实现 WTY5 加密的平台都必须同时实现解密。
3. 旧格式解密：继续兼容 `WTY1:` / `WTY2:` / `WTY3:` 文本载荷。
4. 密钥路径：共享密钥模式使用 Argon2id；联系人 fallback 使用 X25519 + HKDF-SHA256。
5. 身份码：`WTYID1|<name>|<base64-public-key>`。
6. 备份码：`WTYB1-...`，保留 CRC32 校验和 v0.4 兼容读取。
7. QR 传输：单 QR 直接承载 payload；多 QR 使用 `WTYP1|id|N|T|chunk`，id 由 payload hash 派生。
8. 错误语义：区分格式错误、共享密钥不匹配、缺身份、缺联系人、找不到联系人。
9. 收发大小：WTY1–5 密文最多 524288 字符（含前缀/Base64）；WTY4 明文最多 393157 字节，WTY5 最多 393146 字节，图像页/分块另扣元数据。文本先检查 UTF-8 字节数，接收先检查密文长度，超长发送不得推进会话。QR 传输另限 32 张 × 800 字符正文。
10. KDF 预算：WTY4 的 `memKB ∈ [8192,65536]`、`iter ∈ [1,10]`、`par ∈ [1,4]`，且 `memKB × iter ≤ 262144`。串行化派生并预留实现开销和可用内存，不应仅捕获 OOM 或相信未认证 header；JVM 预算细节见 [协议规范](PROTOCOL.md#2-wty4-envelope当前默认)。
11. 会话持久化：锁覆盖读取、加解密、同步保存全过程；桌面需要跨进程锁与原子替换。密文只能在状态保存成功后输出。会话绑定双方身份，更换身份/联系人公钥不能沿用旧状态；联系人选择不能依赖列表下标。

## 当前工程结构

JVM `shared-protocol` 是协议核心；Android app 只保留身份/联系人/二维码/系统输入法 glue，Apple 侧通过 protocol-injected backend 预留接入点：

```text
shared-protocol/     # JVM WTY1-5, WTYID1, WTYB1, WTYP1, X25519/SAS
desktop-cli/         # Windows/Linux 桌面协议 CLI
platforms/
  apple/             # Swift Package: iOS Keyboard Extension + macOS InputMethodKit 外壳
  windows/           # PowerShell wrapper + direct insert helper + hotkey prototype + package smoke
  linux/             # shell wrapper + IBus engine + direct insert helper + 远程 smoke
```

Android 与 `shared-protocol` 已共享测试向量；后续平台必须以 shared-protocol / docs/PROTOCOL.md 为准。

## 分阶段开发

### P0: 协议冻结

- 写 `WTY1`-`WTY5`、`WTYID1`、`WTYB1`、`WTYP1` 的跨语言测试向量。
- 每个测试向量包含明文、密钥材料、salt/iv、公钥、密文、QR chunk 文本。
- Android 当前实现必须能读写这些向量。

### P1: Android 基准版

- 保持现有 Android IME 是功能基准。
- 把未验证联系人、目标选择、QR 兼容矩阵继续补强。
- `connectedDebugAndroidTest` 必须真实跑出非 0 测试数。

### P2: 桌面文本优先

- Windows/Linux CLI 已支持 WTY4/WTY5 发送与 WTY1–5 兼容解密。桌面 profile（`init` / `peer-add` / `peer-verify` / `send --peer` / `receive`，状态在 `WENTUYI_HOME`）由 CLI 自行选协议，输入桥只透传文本——协议路由不应该写在 shell/PowerShell/Python 里三份。macOS 仍待 crypto backend 接入。
- profile 与原始 `--state` 命令已共用锁和原子持久化规则。旧会话文件没有双方公钥绑定，升级时必须执行 `peer-reset --peer NAME` 或用原身份/对方公钥重新 `ratchet-init`；状态重置后通过一条新消息恢复，旧会话未接收消息可能失效。
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

- 聊天框内转换不会阻止宿主在加密前读取原文。若需把明文留在文图易侧，必须使用独立输入页或对应平台的独立编辑区，之后仅传送密文；输入法和来源应用的可见范围仍需明确告知。
- 不承诺所有平台都能像 Android 一样直接向宿主 App 插入图片。
- 不承诺 iOS 在 secure text field 或禁用第三方键盘的 App 内可用。
- 不把 Web 版本包装成系统输入法。
- 不在各平台分别发明新协议；所有平台必须互通当前默认 `WTY4`，并兼容读取 `WTY1`-`WTY3`。

## 下一步开发入口

1. 补齐 WTY5 跨语言测试向量和非 JVM 实现说明。
2. 把 Windows/Linux smoke 从固定共享密钥文本扩展到 WTY4 兼容解密矩阵。
3. 先做 macOS 或 Windows 的身份/联系人状态接入；这两个平台比 iOS 更接近完整系统输入法能力。

## 当前落地状态

v0.7.5 已在 API 34 模拟器通过 93 项自动化测试及 11 项 UI 验证；真实 Android 设备、真实摄像头和第三方聊天宿主仍待验收，发布以对应版本标签的 CI 门禁通过为准。本轮 Windows 验证以真实 OS 上 PowerShell 5.1/7 CLI 与桥接自动化的 CI 门禁结果为准，桌面热键和真实窗口交互仍待验收。以下 Windows/Linux 交互结果是已有版本的记录，不能视作本轮交互验收已完成。

- `shared-protocol`：纯 JVM 协议核心覆盖 `WTY4` 文本加密/解密、`WTY5` Double Ratchet、`WTY1`-`WTY3` 文本解密、X25519 身份、完整安全码、备份码、`WTYP1` 文本分片；v0.7.5 统一收发大小预算、限制 Argon2 资源消耗，并保证已知会话重置 epoch 严格递增。
- `desktop-cli`：Windows/Linux 协议 CLI；v0.7.5 增加跨进程状态事务、原子保存、双方身份绑定、完整安全码认证与换公钥后清除旧会话，并保留正文空白与换行。
- Android：v0.7.5 将屏幕解密结果改为进程内按请求消费，扫描页旋转及键盘切换输入目标后保留当前结果，联系人选择绑定指纹，旧明文联系人及旧 8 位验证迁移后需重新核对完整安全码；密图分享成功不恢复原文，二维码逐页处理并增加密集二维码定位回退；独立输入页加密后仅在用户显式操作时复制或分享。
- Linux：已新增 `platforms/linux/wentuyi-cli`、`platforms/linux/wentuyi-insert.sh`、`platforms/linux/ibus/wentuyi_ibus.py`、`platforms/linux/install-ibus.sh`、`platforms/linux/test-remote.sh`、`platforms/linux/ui-smoke.sh` 和 `platforms/linux/ui-rich-smoke.sh`；测试机已安装 Java 17，远程脚本通过共享密钥、X25519 session-key、IBus self-test、direct insert self-test 和 GTK 输入框普通文本 UI smoke。GTK `TextView` 富文本 direct insert smoke 已通过：`wentuyi-insert.sh` 可不使用剪贴板把 `WTY4:` 直接插入富文本光标位置，再直接解密回明文并保留前后格式。GTK `TextView` 对 synthetic key event 进入 IBus engine 的路径在 Xvfb 中不稳定，因此富文本默认走 direct insert helper。
- Windows 测试机：SMB/SCM 可达；CLI zip、PowerShell 脚本和便携 JRE zip 已上传到 `C:\Temp\wentuyi`，package smoke 已通过。
- Windows：已新增 `platforms/windows/wentuyi-cli.ps1`、`wentuyi-insert.ps1`、`wentuyi-hotkey.ps1`、`install-hotkey.ps1`、`test-local.ps1`、`test-package.ps1`、`ui-smoke.ps1`、`ui-rich-smoke.ps1` 和 `ui-direct-insert-smoke.ps1`；支持系统 Java 17+ 或同目录 `jre-windows.zip`，RDP 交互桌面普通文本 UI smoke 已验证 Notepad 选中文本加密/解密，包自检覆盖直接 Unicode 插入 helper。RichTextBox 直接插入 UI smoke 已通过：`wentuyi-insert.ps1 -TargetHwnd` 可把 `WTY4:` 直接插入目标富文本位置，再直接替换回明文并保留前后格式。RichTextBox hotkey/clipboard UI smoke 也已真实运行，结论是 PowerShell clipboard hotkey 只能保留为普通文本兼容桥，富文本目标必须转 TSF/目标 HWND 直插。
- Apple：已新增 `platforms/apple` Swift Package，包含共享键盘模型、iOS `UIInputViewController` 外壳、macOS `IMKInputController` 外壳；当前 crypto backend 是注入接口，需在 macOS/Xcode 环境接入真实协议实现并编译测试。

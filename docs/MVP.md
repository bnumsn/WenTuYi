# 文图易 MVP 开发说明 (v0.6.0)

## 目标闭环

1. 用户启用文图易键盘。
2. 默认像普通键盘一样输入；如需转换为图片或加密内容，点击候选栏右侧的普通图片、加密文字、加密二维码入口。
3. 动作会读取当前输入框文字；如果有选中文本，则优先处理选中文本。
4. `文字` 直接提交普通文本。
5. `加密文字`：共享密钥路径输出 `WTY4:`；已验证联系人优先输出 `WTY5:` Double Ratchet，响应方首条前回退 `WTY4:` session-key 并提示暂无 PFS。加密成功后直接替换选中文本/当前输入框内容，不写入剪贴板。
6. `文字图片`：渲染普通 PNG；点击图标后优先 `commitContent` 到当前富文本位置，失败则直接打开分享面板。
7. `加密二维码`：把同一个 `WTY4:` / `WTY5:` payload 渲染为 ZXing QR (ECC=H)；超过 ~800 字节时按 `WTYP1|id|N|T|chunk` 包装拆多张 QR；点击图标后优先 `commitContent` 到当前富文本位置，失败则直接打开分享面板，中间失败不再抛异常。
8. 任何平台都不把“复制到剪贴板再让用户粘贴”作为默认发送流程；剪贴板只能是用户显式选择的兜底导出方式。
9. 接收方把加密文字或一组加密二维码分享到文图易，或在文图易里导入单张/多张图片；多 QR 自动按序号重组后再解密一次。
10. 解密结果显示为文字或图像，缺页时给出 `X/Y` 提示。
11. X25519 公钥交换：双方各自在 `KeyManagementActivity` 生成身份码 → 通过任意通道 (扫描相册 / 系统分享) 发给对方 → 文图易识别为身份码自动加为联系人 → 列表里显示 8 位 SAS 供口头核对一致 → 之后可使用会话密钥 (`KEY_MODE_SESSION_KEY`) 与对方加密通信。

## 后续优先级

1. **真机兼容性矩阵**：在微信、QQ、钉钉、飞书、Telegram、WhatsApp 上逐项测试
   - `commitText` (密文)：`已写入加密文字` 是否落入对方聊天框；
   - `commitContent` (密图)：能否直接插入；
   - 系统分享面板：单图 vs 多图；
   - QR 在对方 App 内被压缩后导出再扫，能否解码（特别是微信将 PNG 转 WebP / JPEG 后）。
   导出为表格放进 `docs/COMPATIBILITY.md`（待补）。
2. **实时摄像头扫描真机矩阵**：当前已用 `android.hardware.camera2` + ZXing 自实现实时扫码；该路径依赖真实摄像头，需补不同机型、光照、二维码尺寸的真机结果。
3. **身份私钥升级**：迁到 `KeyProperties.KEY_ALGORITHM_EC` (Curve25519 受限于 Keystore 支持，先用 P-256 也可) 让私钥不可导出。
4. **多设备同步**：允许导出/导入 X25519 身份的加密备份（口令 + Argon2id 包裹）。
5. **共享密钥淘汰路径**：当用户已添加至少一个联系人时，把 `KeyManagementActivity` 的"共享密钥"折叠到"高级 / 兼容旧版"区域。

## 安全设计要点速查

| 项 | 决策 |
|---|---|
| 对称加密 | AES-256-GCM (AEAD, 16B tag) |
| KDF (passphrase) | Argon2id (WTY4 默认 m=64 MiB, t=4, p=1, out=32B；参数写入 header 并 clamp) |
| KDF (session key) | HKDF-SHA256，salt=排序的双公钥 |
| AAD | WTY4 整 37B header (version‖type‖mode‖argon 参数‖salt‖IV) |
| 公钥协议 | X25519，BC `X25519Agreement` |
| 身份指纹 | SHA-256(publicKey)[..8] → Base32 |
| 口外校验 | HKDF-SHA256 派生 8 位数字 SAS |
| 长 payload | 文本层拆 `WTYP1` 分片，多 QR；加密只发生一次 |
| 错误向量 | AAD 校验失败 → 解密直接抛错（不返回部分明文） |
| Keystore 失败 | 硬失败，不退回明文 fallback |

## 已知非目标 / 限制

- 不实现 X3DH / prekey 服务器。WTY5 Double Ratchet 基于双方已核对的身份密钥，无服务器握手；棘轮首条消息和响应方首条前 fallback 不具完整 PFS。
- 不引入 CameraX / AndroidX 运行时依赖；实时扫码保持 AOSP camera2 路径。
- 不重新设计 Gboard 风格之外的视觉；UX 化繁为简放在后续 P1。

# 文图易 协议规范 (WTY)

跨平台实现者对照文档。权威实现为 `shared-protocol`（纯 JVM）与 Android `app`（两份保持一致）。所有线上格式为 **ASCII 文本**，可经任意 IM 文本框 / 二维码传输。

## 0. 通用原语

| 用途 | 算法 |
|---|---|
| AEAD | AES-256-GCM，128-bit tag，12 字节随机 IV |
| 口令 KDF | Argon2id（ARGON2_VERSION_13） |
| 旧口令 KDF | PBKDF2-HmacSHA256，120000 次（仅 v1/v2 解密） |
| HKDF | HKDF-SHA256 |
| 非对称 | X25519（RFC 7748），拒绝全零（低阶点）共享密钥 |
| 文本编码 | 载荷 = `前缀` + Base64(标准字母表, 无换行)；身份/备份用 RFC4648 Base32(无填充) |

所有密文载荷形如 `<PREFIX><base64>`，前缀含冒号：`WTY1:` `WTY2:` `WTY3:` `WTY4:`，棘轮为 `WTY5:`。

`type` 字节：`1`=文本 `2`=图像 `3`=分页图像(`WTYIPG1`magic+pageNo+pageTotal) `4`=图像分片(`WTYICH1`magic+chunkNo+chunkTotal+totalBytes)。
`key-mode` 字节：`0`=Argon2id 口令 `1`=HKDF 会话密钥（32 字节）。

## 1. WTY3 envelope（兼容解密）

```
WTY3: + Base64( header(31) || AES-GCM-ciphertext )
header: [0]=0x03 [1]=type [2]=keymode [3..18]=salt(16) [19..30]=iv(12)
```
整 header 作 GCM AAD。keymode=0 → key=Argon2id(passphrase, salt)（历史固定 m=32MiB/t=3/p=1）；keymode=1 → key=HKDF(sessionKey, salt, info="WTY3-session-v1")。

## 2. WTY4 envelope（当前默认）

在 v3 基础上把 Argon2 参数写入 header，便于平滑调参。

```
WTY4: + Base64( header(37) || AES-GCM-ciphertext )
header: [0]=0x04 [1]=type [2]=keymode
        [3..6]=argon_memKB(uint32 BE) [7]=argon_iter [8]=argon_par
        [9..24]=salt(16) [25..36]=iv(12)
```
整 header 作 GCM AAD。默认 **m=64MiB / t=4 / p=1**。keymode=1 时 argon 字段填 0（用 HKDF，同 v3）。

**解密必须 clamp 校验** header 中的 argon 参数，超范围直接拒绝（防恶意大 m 触发 OOM）：
`memKB ∈ [8192, 262144]`、`iter ∈ [1,10]`、`par ∈ [1,4]`。

## 3. WTY5 — Double Ratchet（前向保密）

Signal 式双棘轮，适配无服务器/无握手/异步可乱序通道。用于已验证联系人的加密文本与二维码。

```
WTY5: + Base64( header(40) || AES-GCM-ciphertext )
header: [0..31]=发送方当前棘轮公钥(X25519, 32) [32..35]=PN(uint32 BE) [36..39]=N(uint32 BE)
```
整 header 作 GCM AAD。**消息无身份字段**——接收方对每个联系人非破坏性试解（见下）。

**初始根密钥**（双方确定性算出同值，复用已 SAS 核对的身份密钥）：
`RK0 = HKDF(ECDH(身份A, 身份B), salt=0^32, info="WTY5-root-init")`

**角色**：公钥字典序小者 = 发起方(Alice)；大者 = 响应方(Bob)。Bob 的初始棘轮密钥对 = 其身份密钥对（无 prekey 服务器的替代）。只有 Alice 能开启会话；Bob 在收到首条 WTY5 前无发送链（实现可回退 WTY4，无 PFS，需对用户可见）。

**根链 KDF**：`(RK', CK) = HKDF(ikm=DH_out, salt=RK, info="WTY5-root", 64B)` → 前 32B=新根密钥，后 32B=链密钥。

**对称链 KDF**：`messageKey = HMAC-SHA256(CK, 0x01)`；`CK' = HMAC-SHA256(CK, 0x02)`。

**消息密钥派生 (key+iv)**：`HKDF(ikm=messageKey, salt=0^32, info="WTY5-msg", 44B)` → 前 32B=AES key，后 12B=IV。每条消息密钥一次性，故 IV 不复用。

**DH 棘轮步**（收到新棘轮公钥时）：PN=Ns；Ns=Nr=0；用旧/新自有棘轮私钥分别与对端公钥 ECDH，两次根链推进得到新收链、新发链；并生成新棘轮密钥对。

**乱序/丢失**：header 带 PN（上一发送链长度）与 N（本链消息号）。跳过的消息密钥进有界缓存：单步上限 `MAX_SKIP=1000`，全局上限 `MAX_SKIP_TOTAL=2000`（按插入序淘汰最旧）。

**实现要求（安全关键）**：
- **decrypt 必须事务化**：在状态副本上运行，AEAD 认证通过后才提交；伪造消息不得改动状态。
- **发送方状态必须同步持久化后**才可发出密文，否则崩溃后重派生同一 (key,iv) 会造成 GCM nonce 复用。
- 接收路由：对每个联系人加载状态、事务化试解，成功才提交该联系人状态。
- 换步/进链时清零被取代的旧根/链密钥。
- 解码前限制 payload 长度（参考 512KB）。

## 4. 身份码 WTYID1（二维码）

```
WTYID1|<name>|<base64url(publicKey 32B)>
```
name 去首尾空白、截断 40 字符、`|`→`/`、空则 "未命名"。

## 5. 备份码 WTYB1（用户手抄）

```
WTYB1- + Base32( publicKey(32) || privateKey(32) || CRC32(前64B)(4) ) 按 5 字符分组、'-' 连接
```
解码：剥离空白/NBSP/零宽/连字符 → Base32 解码 → 68 字节校验 CRC32 + 私钥导出公钥一致性；兼容 64 字节的 v0.4 旧备份（无 CRC）。

## 6. 多二维码分片 WTYP1

单张二维码 payload 上限约 800 字节；超出时按 800 字节切片，每片：
```
WTYP1|<id>|<N>|<T>|<chunk>
```
`id = Base32(SHA-256(完整payload)[..5]).take(8).lowercase()`。重组：按 N(1..T) 顺序拼接，校验 `id` 与重组结果一致，且重组结果 `isPayload()` 为真（含 WTY1-5）。可阻止跨消息分片拼接。

## 7. SAS（短认证串）

`SAS = ( (HKDF(ECDH(身份A,身份B), salt=排序拼接两公钥, info="WTY-SAS-v1", 4B) 的高位 31 bit) % 1e8 )` → **8 位**十进制，左补零。双方口外比对一致以防 MITM。

会话密钥（喂给 WTY3/WTY4 keymode=1）：`HKDF(ECDH, salt=排序拼接两公钥, info="WTY-session-v1", 32B)`。

## 8. 安全边界与限制

- **有 PFS**：已验证联系人的 WTY5 文本/二维码。
- **无 PFS**：共享密钥（WTY1-4 口令）、WTY4 会话密钥路径、WTY5 棘轮首条（Bob 首次回复前链含长期身份成分，回复后完整生效）。
- WTY5 不做头部加密：棘轮公钥+计数器可见；但密文不含身份，旁观者无法判定收发双方。
- 旧 WTY1/WTY2 的 `type` 字节未认证（无 AAD），仅类型混淆/DoS，非伪造；v3 起经 AAD 关闭。
- 无多设备同步。

## 版本兼容矩阵

| 格式 | 加密 | 解密 | KDF |
|---|---|---|---|
| WTY1/WTY2 | ✗（已弃用） | ✓ | PBKDF2 |
| WTY3 | ✗（被 v4 取代） | ✓ | Argon2id 固定参数 |
| WTY4 | ✓ 默认 | ✓ | Argon2id 参数入 header |
| WTY5 | ✓ 联系人 | ✓ | Double Ratchet |

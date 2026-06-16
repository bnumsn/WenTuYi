# protocol-fixtures

Canonical, cross-platform **decrypt** test vectors for the Wentuyi wire protocols
(`WTY3` / `WTY4`, both passphrase and X25519-session key modes, text + image + page + chunk).

These exist because the codec is currently implemented **twice** — `:app`
(`com.wentuyi.app.SecurePayloadCodec`) and `:shared-protocol`
(`com.wentuyi.protocol.SecurePayloadCodec`) — with no compile-time link between them. This
directory is the single contract both sides must satisfy, so the implementations cannot drift
silently (e.g. one side forgets to bind a new header field into the GCM AAD).

## Who consumes it

| Module | Test | Runs where |
|---|---|---|
| `:shared-protocol` | `VectorContractTest` | plain JVM (`./gradlew :shared-protocol:test`) |
| `:app` | `VectorContractTest` (androidTest) | device/emulator (`connectedDebugAndroidTest`) |

`vectors.txt` is put on the `:shared-protocol` test classpath via `resources.srcDir`, and shipped
into the `:app` test APK via `androidTest` `assets.srcDir`. There is exactly one copy of the file.

Each suite asserts:
1. **Positive** — every vector decrypts to the expected type, body bytes and page/chunk metadata.
2. **Negative** — flipping *any single header byte* makes decryption fail (proves the whole
   header is bound as GCM AAD, not just carried alongside).

## Format

Dependency-free, pipe-delimited text (no JSON library on any classpath). All variable fields are
base64 / hex / ascii, so none can contain the `|` separator.

```
# comment lines start with '#'
meta|<passphraseBase64>|<sessionKeyHex>
vec|name|prefix|keyMode|type|headerLen|page|total|totalBytes|bodyBase64|payload
```

- `keyMode` — `passphrase` or `session`; the key material is the global `meta` row.
- `type` — 1=text, 2=image, 3=image page, 4=image chunk (matches `SecurePayloadCodec.TYPE_*`).
- `headerLen` — bytes of header bound as AAD (31 for v3, 37 for v4); the negative test tampers `[0, headerLen)`.
- `page` / `total` / `totalBytes` — expected page/chunk metadata (0 when not applicable).
- `bodyBase64` — the expected decrypted body (text UTF-8 bytes, or the inner image bytes for image/page/chunk).
- `payload` — the frozen `WTYx:` ciphertext string; the authoritative artifact.

## Regenerating

```
./gradlew :desktop-cli:generateFixtures      # writes protocol-fixtures/vectors.txt
./gradlew :shared-protocol:test              # must stay green
./gradlew :app:connectedDebugAndroidTest     # on a device — must stay green
```

Salt and IV are random per encryption, so **every regeneration rewrites the payloads**. Only do
it when the protocol genuinely changes, and re-run both suites afterwards. The generator
(`desktop-cli/.../FixtureGenerator.kt`) builds vectors from the authoritative `:shared-protocol`
codec, hand-crafting the legacy `WTY3` envelopes the current encrypt path no longer emits.

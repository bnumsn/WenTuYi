#!/usr/bin/env bash
# End-to-end smoke test for the desktop CLI, driving two identities through the real
# binary. Unit tests cover the codecs in isolation; this covers the part that actually
# broke in the field — argument plumbing, the on-disk ratchet state file, and the
# out-of-sync recovery rules — by running the same commands a user would.
#
# Usage: ./scripts/cli-smoke.sh          (builds the CLI first)
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
CLI="$ROOT/desktop-cli/build/install/desktop-cli/bin/desktop-cli"

[ -x "$CLI" ] || ./gradlew :desktop-cli:installDist -q

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

pass=0
check() { # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then
    pass=$((pass + 1)); echo "  ok   $1"
  else
    echo "  FAIL $1"; echo "       expected: $2"; echo "       actual:   $3"; exit 1
  fi
}

echo "== 身份 =="
"$CLI" gen-identity --name A > a.txt
"$CLI" gen-identity --name B > b.txt
ABK=$(grep '^backup=' a.txt | cut -d= -f2-); APUB=$(grep '^publicKey=' a.txt | cut -d= -f2-)
BBK=$(grep '^backup=' b.txt | cut -d= -f2-); BPUB=$(grep '^publicKey=' b.txt | cut -d= -f2-)

# SAS must match in both directions or the whole MITM defence is theatre.
SAS_A=$(WENTUYI_BACKUP="$ABK" "$CLI" sas --peer-public "$BPUB")
SAS_B=$(WENTUYI_BACKUP="$BBK" "$CLI" sas --peer-public "$APUB")
check "SAS 双向一致" "$SAS_A" "$SAS_B"
check "SAS 为 8 位" "8" "${#SAS_A}"

echo "== WTY4 共享密钥 =="
P=$(WENTUYI_PASSPHRASE="correct horse 文图易" "$CLI" encrypt-text "中文 payload — ünïcode")
case "$P" in WTY4:*) ;; *) echo "  FAIL 期望 WTY4 前缀，实际 ${P:0:8}"; exit 1 ;; esac
OUT=$(WENTUYI_PASSPHRASE="correct horse 文图易" "$CLI" decrypt-text "$P")
check "WTY4 往返（含中文/变音符）" "中文 payload — ünïcode" "$OUT"
if WENTUYI_PASSPHRASE="wrong key" "$CLI" decrypt-text "$P" >/dev/null 2>&1; then
  echo "  FAIL 错误密钥竟然解开了"; exit 1
fi
pass=$((pass + 1)); echo "  ok   错误共享密钥被拒绝"

echo "== WTY4 会话密钥（X25519）=="
S=$(WENTUYI_BACKUP="$ABK" "$CLI" session-encrypt --peer-public "$BPUB" "会话密钥消息")
OUT=$(WENTUYI_BACKUP="$BBK" "$CLI" session-decrypt --peer-public "$APUB" "$S")
check "会话密钥往返" "会话密钥消息" "$OUT"

echo "== WTY5 双棘轮 =="
WENTUYI_BACKUP="$ABK" "$CLI" ratchet-init --peer-public "$BPUB" --state a.state > /dev/null
M1=$(WENTUYI_BACKUP="$ABK" "$CLI" ratchet-encrypt --state a.state "你好，我是 A")
case "$M1" in WTY5:*) ;; *) echo "  FAIL 期望 WTY5 前缀"; exit 1 ;; esac
# B holds no state: it must bootstrap from the epoch carried in the payload.
OUT=$(WENTUYI_BACKUP="$BBK" "$CLI" ratchet-decrypt --peer-public "$APUB" --state b.state "$M1")
check "接收方从 epoch 自举" "你好，我是 A" "$OUT"
R1=$(WENTUYI_BACKUP="$BBK" "$CLI" ratchet-encrypt --state b.state "收到，我是 B")
OUT=$(WENTUYI_BACKUP="$ABK" "$CLI" ratchet-decrypt --peer-public "$BPUB" --state a.state "$R1")
check "反向棘轮" "收到，我是 B" "$OUT"

# Out-of-order delivery: chat apps and QR scans do not preserve order.
X1=$(WENTUYI_BACKUP="$ABK" "$CLI" ratchet-encrypt --state a.state "乱序 1")
X2=$(WENTUYI_BACKUP="$ABK" "$CLI" ratchet-encrypt --state a.state "乱序 2")
OUT=$(WENTUYI_BACKUP="$BBK" "$CLI" ratchet-decrypt --peer-public "$APUB" --state b.state "$X2")
check "先收后发的那条" "乱序 2" "$OUT"
OUT=$(WENTUYI_BACKUP="$BBK" "$CLI" ratchet-decrypt --peer-public "$APUB" --state b.state "$X1")
check "再收被跳过的那条" "乱序 1" "$OUT"

echo "== 失步恢复 =="
STALE=$(WENTUYI_BACKUP="$ABK" "$CLI" ratchet-encrypt --state a.state "旧会话消息")
rm a.state   # A reinstalled / cleared data / restored from backup code
WENTUYI_BACKUP="$ABK" "$CLI" ratchet-init --peer-public "$BPUB" --state a.state > /dev/null
M2=$(WENTUYI_BACKUP="$ABK" "$CLI" ratchet-encrypt --state a.state "我重装了")
OUT=$(WENTUYI_BACKUP="$BBK" "$CLI" ratchet-decrypt --peer-public "$APUB" --state b.state "$M2")
check "更新的 epoch 被自动采纳" "我重装了" "$OUT"
R2=$(WENTUYI_BACKUP="$BBK" "$CLI" ratchet-encrypt --state b.state "欢迎回来")
OUT=$(WENTUYI_BACKUP="$ABK" "$CLI" ratchet-decrypt --peer-public "$BPUB" --state a.state "$R2")
check "恢复后仍是双向棘轮" "欢迎回来" "$OUT"
if WENTUYI_BACKUP="$BBK" "$CLI" ratchet-decrypt --peer-public "$APUB" --state b.state "$STALE" >/dev/null 2>&1; then
  echo "  FAIL 已退休 epoch 的密文被重放进了活会话"; exit 1
fi
pass=$((pass + 1)); echo "  ok   旧 epoch 重放被拒绝"

echo "== Profile（两台机器互通，桥接实际走的就是这条）=="
# The bridges call `send`/`receive`, which choose the protocol themselves. This section
# exists because those bridges used to call encrypt-text only — i.e. shared-key forever —
# so a desktop user could not read a WTY5 message from an Android contact at all.
export WENTUYI_HOME="$WORK/profile-a"
PA=("env" "WENTUYI_HOME=$WORK/profile-a" "$CLI")
PB=("env" "WENTUYI_HOME=$WORK/profile-b" "$CLI")
APUB2=$("${PA[@]}" init 2>/dev/null | grep '^publicKey=' | cut -d= -f2-)
BPUB2=$("${PB[@]}" init 2>/dev/null | grep '^publicKey=' | cut -d= -f2-)
SAS_PA=$("${PA[@]}" peer-add --name bob --peer-public "$BPUB2" 2>/dev/null | grep '^sas=' | cut -d= -f2)
SAS_PB=$("${PB[@]}" peer-add --name alice --peer-public "$APUB2" 2>/dev/null | grep '^sas=' | cut -d= -f2)
check "profile SAS 双向一致" "$SAS_PA" "$SAS_PB"

M=$("${PA[@]}" send --peer bob "今晚八点老地方" 2>/dev/null)
OUT=$("${PB[@]}" receive "$M" 2>/dev/null)
check "A 发 → B 收" "今晚八点老地方" "$OUT"
R=$("${PB[@]}" send --peer alice "收到" 2>/dev/null)
OUT=$("${PA[@]}" receive "$R" 2>/dev/null)
check "B 回 → A 收" "收到" "$OUT"

# Once both chains exist the protocol must upgrade itself — no flag, no user action.
M2=$("${PA[@]}" send --peer bob "第二条" 2>/dev/null)
case "$M2" in WTY5:*) ;; *) echo "  FAIL 棘轮建立后仍未升级到 WTY5（实际 ${M2:0:5}）"; exit 1 ;; esac
pass=$((pass + 1)); echo "  ok   棘轮建立后自动升级为 WTY5"
OUT=$("${PB[@]}" receive "$M2" 2>/dev/null)
check "升级后仍可解" "第二条" "$OUT"

# A loses its state and resets; B must adopt the new epoch with no action of its own.
PSTALE=$("${PA[@]}" send --peer bob "旧会话消息" 2>/dev/null)
rm -f "$WORK/profile-a/peers/bob.ratchet"
"${PA[@]}" peer-reset --peer bob >/dev/null 2>&1
M3=$("${PA[@]}" send --peer bob "我重装了" 2>/dev/null)
OUT=$("${PB[@]}" receive "$M3" 2>/dev/null)
check "A 重置后 B 自动采纳新会话" "我重装了" "$OUT"
if "${PB[@]}" receive "$PSTALE" >/dev/null 2>&1; then
  echo "  FAIL profile: 已退休 epoch 的密文被重放进了活会话"; exit 1
fi
pass=$((pass + 1)); echo "  ok   profile 旧 epoch 重放被拒绝"

# No --peer at all must still work through the legacy shared key.
"${PA[@]}" set-passphrase "shared 文图易" >/dev/null 2>&1
"${PB[@]}" set-passphrase "shared 文图易" >/dev/null 2>&1
SP=$("${PA[@]}" send "共享密钥消息" 2>/dev/null)
OUT=$("${PB[@]}" receive "$SP" 2>/dev/null)
check "无 --peer 时回落共享密钥" "共享密钥消息" "$OUT"
unset WENTUYI_HOME

echo "== QR 分片 =="
LONG=$(WENTUYI_PASSPHRASE="k" "$CLI" encrypt-text "$(head -c 1200 /dev/urandom | base64 | tr -d '\n')")
CHUNKS=$("$CLI" chunk "$LONG")
COUNT=$(printf '%s\n' "$CHUNKS" | wc -l | tr -d ' ')
[ "$COUNT" -ge 2 ] || { echo "  FAIL 长 payload 应拆成多片，实际 $COUNT"; exit 1; }
# shellcheck disable=SC2086 # chunks are base64-ish tokens, word splitting is intended
OUT=$("$CLI" assemble $CHUNKS)
check "WTYP1 拆分后重组" "$LONG" "$OUT"

echo
echo "全部通过：$pass 项"

#!/usr/bin/env python3
"""Generates app/src/main/assets/pinyin.txt — the IME's pinyin dictionary.

Run only when the table needs regenerating; the output is committed so neither the
build nor CI depends on Python or on pypinyin being installed.

    pip install pypinyin && python3 scripts/gen-pinyin-table.py

Sources (all permissive, and only their *data* is used — no code is vendored):
  - pypinyin (MIT) PINYIN_DICT  — character → readings, itself derived from
    Unicode's Unihan kMandarin plus the MIT-licensed pinyin-data project.
  - pypinyin (MIT) lazy_pinyin  — word → readings, context-aware for 多音字.
  - jieba (MIT) dict.txt        — the word list itself plus corpus frequencies.

Each entry carries a 0–15 log2 frequency bucket. Within one key the candidates are
already ordered, but the runtime also has to compare *across* keys — ranking prefix
matches for a half-typed syllable, and scoring competing segmentations of a whole
sentence — and the bucket is what makes that possible in one byte per entry.

The word list comes from jieba rather than pypinyin's PHRASES_DICT: the latter is
weighted towards idioms and proper nouns and is missing everyday vocabulary
(今天/明天/现在 were all absent), which is precisely what people type.

Ranking is what separates a usable IME from a homophone lottery: 'shi' has well
over a hundred readings and the user wants 是/时/事 in the first three, not 石/舍.
Character weight is therefore the summed corpus frequency of every word the
character appears in, plus a large bonus for its own frequency as a standalone
word — that bonus is what lifts function characters like 我/是/的, which are
enormously common alone but appear in comparatively few compounds. Words rank by
their own corpus frequency, with shorter words winning ties.
"""
import sys, unicodedata, collections, io

try:
    from pypinyin.constants import PINYIN_DICT, PHRASES_DICT
except ImportError:
    sys.exit("需要 pypinyin：pip install pypinyin")

try:
    import jieba, os
    JIEBA_DICT = os.path.join(os.path.dirname(jieba.__file__), 'dict.txt')
except ImportError:
    sys.exit("需要 jieba（仅取其词频表用于排序）：pip install jieba")

MAX_CHARS_PER_SYLLABLE = 24     # deeper than any user scrolls; keeps rare readings reachable
MAX_WORDS_PER_KEY = 12
MAX_WORD_LEN = 4                # 5+ char entries are idioms/names, not everyday typing
MIN_WORD_FREQ = 60              # drops the long tail of one-off corpus artifacts
CJK = lambda c: '一' <= c <= '鿿'


def toneless(p):
    """'lǜ' -> 'lv', 'zhāng' -> 'zhang'. ü must survive as v (how people type it)."""
    p = p.replace('ü', 'v').replace('ǖ', 'v').replace('ǘ', 'v').replace('ǚ', 'v').replace('ǜ', 'v')
    return ''.join(c for c in unicodedata.normalize('NFD', p)
                   if unicodedata.category(c) != 'Mn').lower()


def main():
    # ── Corpus frequencies ────────────────────────────────────────────────────
    word_freq = collections.Counter()
    with open(JIEBA_DICT, encoding='utf-8') as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2 and parts[1].isdigit():
                word_freq[parts[0]] = int(parts[1])

    # A character's weight: how much corpus mass flows through it, plus a heavy
    # bonus for standing alone as a word. Without that bonus 我/是/的 sink beneath
    # characters that merely appear in many compounds (石, 卧), which is exactly
    # the failure the old hand-written table had.
    weight = collections.Counter()
    for word, freq in word_freq.items():
        for ch in set(word):
            weight[ch] += freq
    for ch, freq in word_freq.items():
        if len(ch) == 1:
            weight[ch] += freq * 20

    # ── Single characters: pinyin -> chars, best first ────────────────────────
    singles = collections.defaultdict(list)
    # rank 0 = the character's primary reading. Unihan lists rare/dialectal readings
    # too (眼 as "wen", 我 as "yao"); without a penalty those pollute the head of a
    # syllable's candidate list, which is worse than omitting them entirely.
    for code, readings in PINYIN_DICT.items():
        ch = chr(code)
        if not CJK(ch) or weight[ch] == 0:
            continue
        for rank, reading in enumerate(readings.split(',')):
            key = toneless(reading)
            if key.isalpha():
                singles[key].append((ch, rank))

    SECONDARY_PENALTY = 64      # a non-primary reading must outrank by 64x to compete
    for key, entries in singles.items():
        best = {}
        for ch, rank in entries:
            best[ch] = min(best.get(ch, 99), rank)
        ordered = sorted(best, key=lambda c: (-(weight[c] // (SECONDARY_PENALTY ** best[c])), c))
        singles[key] = ordered[:MAX_CHARS_PER_SYLLABLE]

    # ── Words: concatenated toneless pinyin -> words ──────────────────────────
    from pypinyin import lazy_pinyin
    words = collections.defaultdict(list)
    for word, freq in word_freq.items():
        if freq < MIN_WORD_FREQ or not (2 <= len(word) <= MAX_WORD_LEN):
            continue
        if not all(CJK(c) for c in word):
            continue
        readings = lazy_pinyin(word)          # phrase-aware, so 银行 -> yinhang
        if len(readings) != len(word):
            continue
        key = ''.join(toneless(r) for r in readings)
        if key.isalpha():
            words[key].append(word)

    for key, ws in words.items():
        words[key] = sorted(
            set(ws), key=lambda w: (-word_freq[w], len(w), w))[:MAX_WORDS_PER_KEY]

    out = io.StringIO()
    out.write("# 文图易拼音词库 — 由 scripts/gen-pinyin-table.py 生成，请勿手工编辑\n")
    out.write("# 数据来源：pypinyin (MIT) / Unihan kMandarin / pinyin-data (MIT)\n")
    out.write("# 格式：S<TAB>拼音<TAB>频率桶<TAB>候选字串   /   W<TAB>拼音串<TAB>频率桶<TAB>词1 词2 ...\n")

    def bucket(n):
        """0-15 log2 bucket. One char on the wire, enough resolution to rank by."""
        b = 0
        while n > 1 and b < 15:
            n >>= 1
            b += 1
        return b

    for key in sorted(singles):
        top = singles[key][0]
        out.write("S\t%s\t%x\t%s\n" % (key, bucket(weight[top] // 20), ''.join(singles[key])))
    for key in sorted(words):
        out.write("W\t%s\t%x\t%s\n" % (key, bucket(word_freq[words[key][0]]), ' '.join(words[key])))

    data = out.getvalue()
    with open('app/src/main/assets/pinyin.txt', 'w', encoding='utf-8') as f:
        f.write(data)
    print("音节 %d 个，单字候选 %d 个" % (len(singles), sum(len(v) for v in singles.values())))
    print("词条 key %d 个，词 %d 个" % (len(words), sum(len(v) for v in words.values())))
    print("文件 %.1f KB" % (len(data.encode('utf-8')) / 1024))
    for probe in ('wo', 'shi', 'de', 'jin', 'bei', 'wen', 'ni', 'hao'):
        print("  %-5s -> %s" % (probe, ''.join(singles.get(probe, []))[:14]))
    for probe in ('nihao', 'beijing', 'jintian', 'mingtian', 'xianzai'):
        print("  %-9s -> %s" % (probe, ' '.join(words.get(probe, []))[:34]))


if __name__ == '__main__':
    main()

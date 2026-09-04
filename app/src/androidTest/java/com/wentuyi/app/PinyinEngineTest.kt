package com.wentuyi.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the pinyin dictionary, which replaced a 435-entry hand-written table that could
 * not type Chinese: `wojintianyaochuqu` produced 我进天要出去 and offered no second
 * candidate to fix it, because 今 simply wasn't in the table under `jin`.
 *
 * These assertions are deliberately about *user-visible quality*, not implementation —
 * they fail if the asset is stale, if ranking regresses, or if the segmentation cost is
 * retuned badly.
 */
@RunWith(AndroidJUnit4::class)
class PinyinEngineTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadTable() {
            PinyinEngine.load(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    @Test fun table_loads() {
        assertTrue("asset pinyin.txt must load", PinyinEngine.isReady)
    }

    @Test fun common_syllables_lead_with_the_common_character() {
        // The old table's fatal flaw: the right character wasn't first, or wasn't present.
        val expected = mapOf(
            "wo" to "我", "shi" to "是", "de" to "的", "ni" to "你", "hao" to "好",
            "ta" to "他", "yi" to "一", "bu" to "不", "zai" to "在", "you" to "有",
        )
        for ((syllable, want) in expected) {
            val got = PinyinEngine.candidatesFor(syllable)
            assertEquals("$syllable 的首选", want, got.firstOrNull())
        }
    }

    @Test fun syllables_offer_real_alternatives() {
        // 1–3 candidates per syllable was the old ceiling, which is why users could never
        // reach 今 or 北 at all. Depth is what makes picking possible.
        for (syllable in listOf("shi", "ji", "yi", "li", "xi", "zhi")) {
            val got = PinyinEngine.candidatesFor(syllable)
            assertTrue("$syllable 只有 ${got.size} 个候选，太浅", got.size >= 10)
        }
        assertTrue("jin 必须能选到「今」", PinyinEngine.candidatesFor("jin").contains("今"))
        assertTrue("bei 必须能选到「北」", PinyinEngine.candidatesFor("bei").contains("北"))
        assertTrue("wen 必须能选到「问」", PinyinEngine.candidatesFor("wen").contains("问"))
    }

    @Test fun everyday_words_resolve_whole() {
        for ((key, want) in mapOf(
            "nihao" to "你好", "jintian" to "今天", "mingtian" to "明天",
            "xianzai" to "现在", "beijing" to "北京", "shurufa" to "输入法",
        )) {
            assertEquals(want, PinyinEngine.candidatesFor(key).firstOrNull())
        }
    }

    @Test fun prefix_matches_appear_before_the_syllable_is_finished() {
        assertTrue("打到 beij 就该看到北京",
            PinyinEngine.candidatesFor("beij").contains("北京"))
    }

    @Test fun sentences_segment_sensibly() {
        // These exact inputs are the ones the old greedy segmenter got wrong.
        assertEquals("我今天要出去",
            PinyinEngine.firstCandidateOrRaw("wojintianyaochuqu"))
        assertEquals("我明天去北京",
            PinyinEngine.firstCandidateOrRaw("womingtianqubeijing"))
        assertEquals("我们一起吃饭",
            PinyinEngine.firstCandidateOrRaw("womenyiqichifan"))
    }

    @Test fun junk_input_degrades_quietly() {
        // Never crash, never return the raw buffer dressed up as a candidate.
        assertTrue(PinyinEngine.candidatesFor("").isEmpty())
        assertTrue(PinyinEngine.candidatesFor("!!!").isEmpty())
        PinyinEngine.candidatesFor("zzzzzzzzzzzzzzzzzzzzzzzz")   // must not throw
        assertEquals("uppercase 应被归一化",
            PinyinEngine.candidatesFor("ni"), PinyinEngine.candidatesFor("NI"))
    }
}

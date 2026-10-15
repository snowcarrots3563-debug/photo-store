package dev.local.artistgallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameRulesTest {
    @Test fun trimsOuterWhitespaceWithoutChangingText() {
        assertEquals("中文  A <>&/\\", NameRules.normalize(" \t中文  A <>&/\\\n"))
        assertEquals("", NameRules.normalize(" \t\n\u3000"))
    }

    @Test fun duplicateComparisonIsExactAfterTrimming() {
        assertTrue(NameRules.isDuplicate("  山间来信  ", listOf("山间来信")))
        assertFalse(NameRules.isDuplicate("a", listOf("A")))
        assertFalse(NameRules.isDuplicate("a b", listOf("a  b")))
    }

    @Test fun temporaryNamesUseSmallestAvailablePositiveNumber() {
        assertEquals("未命名作品集 1", NameRules.nextTemporaryName(emptyList()))
        assertEquals("未命名作品集 2", NameRules.nextTemporaryName(listOf("未命名作品集 1", "未命名作品集 3")))
        assertEquals("未命名作品集 1", NameRules.nextTemporaryName(listOf("未命名作品集 01", "未命名作品集 0")))
    }

    @Test fun blankRenameIncludesCurrentNameWhenChoosingNumber() {
        assertEquals("未命名作品集 3", NameRules.nextTemporaryName(listOf("未命名作品集 1", "未命名作品集 2")))
    }

    @Test fun defaultNameStartsWithTheExactChineseBase() {
        assertEquals("默认图集", NameRules.nextDefaultName(emptyList()))
        assertEquals("默认图集（2）", NameRules.nextDefaultName(listOf("默认图集")))
    }

    @Test fun defaultNameUsesTheFirstAvailableChineseSuffix() {
        assertEquals("默认图集（3）", NameRules.nextDefaultName(listOf("默认图集", "默认图集（2）", "默认图集（4）")))
        assertEquals("默认图集", NameRules.nextDefaultName(listOf("默认图集（2）")))
    }

    @Test fun defaultNameDoesNotTreatSimilarSpellingsAsCollisions() {
        assertEquals("默认图集", NameRules.nextDefaultName(listOf(" 默认图集 ", "默认图集 (2)", "默认图集（02）")))
        assertEquals("默认图集（2）", NameRules.nextDefaultName(listOf("默认图集", "默认图集 (2)", "默认图集（02）")))
    }
}

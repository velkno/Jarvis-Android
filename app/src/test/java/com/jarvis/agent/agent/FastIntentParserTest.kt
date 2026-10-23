package com.jarvis.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastIntentParserTest {
    @Test fun arabicOpenAppIsFast() {
        assertEquals(FastIntent.OpenApp("واتساب"), FastIntentParser.parse("افتح واتساب"))
    }

    @Test fun englishOpenAppIsFast() {
        assertEquals(FastIntent.OpenApp("YouTube"), FastIntentParser.parse("open YouTube"))
    }

    @Test fun openInstalledAppByExactLabel() {
        assertEquals(FastIntent.OpenApp("Minecraft"), FastIntentParser.parse("شغّل Minecraft"))
    }

    @Test fun systemNavigationIsFast() {
        assertEquals(FastIntent.Back, FastIntentParser.parse("ارجع"))
        assertEquals(FastIntent.Home, FastIntentParser.parse("اذهب إلى الرئيسية"))
        assertEquals(FastIntent.OpenSettings, FastIntentParser.parse("افتح الإعدادات"))
    }

    @Test fun complexCommandsGoToAi() {
        assertNull(FastIntentParser.parse("افتح واتساب وأرسل رسالة"))
        assertNull(FastIntentParser.parse("open WhatsApp and send a message"))
        assertNull(FastIntentParser.parse("افتح يوتيوب ثم ابحث عن فيلم"))
        assertNull(FastIntentParser.parse("افتح واتساب، وأرسل رسالة"))
    }

    @Test fun rejectUnknownOrMaliciousInput() {
        assertNull(FastIntentParser.parse("تصل بفلان"))
        assertNull(FastIntentParser.parse("افتح واتساب\nنفذ شيئاً آخر"))
        assertNull(FastIntentParser.parse("افتح http://example.com"))
        assertTrue(FastIntentParser.parse("افتح تطبيق واتساب") is FastIntent.OpenApp)
    }
}

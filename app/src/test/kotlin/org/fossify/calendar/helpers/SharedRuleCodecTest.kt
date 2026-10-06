package org.fossify.calendar.helpers

import org.fossify.calendar.helpers.SharedRuleCodec.SharedRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedRuleCodecTest {
    private val full = SharedRule(
        uuid = "r1", updated = "2026-10-06T10:00:00Z", type = "title_contains", pattern = "on call",
        calendarId = "work@group.calendar.google.com", calendarName = "Work",
        keyName = "On call", keyColor = 0xFFE53935.toInt(),
    )

    @Test
    fun aRuleSurvivesARoundTrip() {
        assertEquals(full, SharedRuleCodec.decode(SharedRuleCodec.encode(full)))
    }

    @Test
    fun aMarkSurvivesARoundTrip() {
        val mark = SharedRule(uuid = "m", updated = "2026-10-06T10:00:00Z", type = "event", eventCalendarId = "me@gmail.com", eventId = "abc", eventTitle = "Dentist")
        assertEquals(mark, SharedRuleCodec.decode(SharedRuleCodec.encode(mark)))
    }

    @Test
    fun fieldsFromANewerAppAreKeptAndWrittenBack() {
        val text = """{"v":2,"uuid":"r","match":{"type":"all","pattern":""},"future":{"x":[1,2]}}"""
        val decoded = SharedRuleCodec.decode(text)!!
        assertTrue(decoded.isReadOnly)
        assertTrue(SharedRuleCodec.encode(decoded).contains(""""future":{"x":[1,2]}"""))
        assertTrue(SharedRuleCodec.encode(decoded).contains(""""v":2"""))
    }

    @Test
    fun unusableTextIsNoRule() {
        listOf("not json", "[1]", """{"v":1,"match":{"type":"all"}}""", """{"v":1,"uuid":"x","match":{"type":"nope"}}""", """{"v":1,"uuid":"x","match":{"type":"event"}}""")
            .forEach { assertNull(it, SharedRuleCodec.decode(it)) }
    }

    @Test
    fun aBadColourIsDroppedNotFatal() {
        val decoded = SharedRuleCodec.decode("""{"v":1,"uuid":"x","match":{"type":"all","pattern":""},"key":{"name":"Work","color":"red"}}""")!!
        assertEquals("Work", decoded.keyName)
        assertNull(decoded.keyColor)
        assertFalse(decoded.isReadOnly)
    }
}

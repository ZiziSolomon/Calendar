package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.ContextualRuleBackupEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualRulesBackupTest {
    private val calendarNames = mapOf(3L to "Family", 4L to "Work")

    private val rules = listOf(
        ContextualRule(id = 1, matchType = MATCH_TITLE_CONTAINS, pattern = "on call"),
        ContextualRule(id = 2, matchType = MATCH_ALL, calendarId = 3, caldavCalendarId = 1),
        ContextualRule(id = 3, matchType = MATCH_TITLE_REGEX, pattern = "^(shift|rota)\\b", enabled = false),
        ContextualRule(id = 4, matchType = MATCH_DURATION_OVER, pattern = "1440", calendarId = 4),
        ContextualRule(id = 5, matchType = MATCH_EVENT_ID, eventId = 10, importId = "Caldav-1-1"),
        // a mark on a local-only event: nothing portable to key it by
        ContextualRule(id = 6, matchType = MATCH_EVENT_ID, eventId = 11, importId = null),
    )

    private fun roundTrip(rules: List<ContextualRule>) =
        ContextualRulesBackup.decode(ContextualRulesBackup.encode(rules) { calendarNames[it] })

    // a fresh install with the same calendars, but new local ids
    private fun planOnFreshInstall(entries: List<ContextualRuleBackupEntry>, existing: List<ContextualRule> = emptyList()) =
        ContextualRulesBackup.planImport(
            entries = entries,
            existing = existing,
            calendarIdForCaldavId = { mapOf(1 to 30L)[it] },
            calendarIdForName = { mapOf("Family" to 30L, "Work" to 40L)[it] },
            eventIdForImportId = { mapOf("Caldav-1-1" to 100L)[it] },
        )

    @Test
    fun portableRulesSurviveARoundTrip() {
        val entries = roundTrip(rules)
        assertEquals(5, entries.size)
        assertEquals("^(shift|rota)\\b", entries[2].pattern)
        assertFalse(entries[2].enabled)
        assertEquals("Family", entries[1].calendarName)
        assertEquals(1, entries[1].caldavCalendarId)
        assertEquals("Caldav-1-1", entries[4].importId)
    }

    @Test
    fun encodedRulesFitOnOneSettingsLine() {
        // the settings file is key=value per line; a newline would split the value
        val json = ContextualRulesBackup.encode(rules + ContextualRule(id = 7, matchType = MATCH_TITLE_CONTAINS, pattern = "a\nb")) { null }
        assertFalse(json.contains('\n'))
        assertEquals("a\nb", ContextualRulesBackup.decode(json).last().pattern)
    }

    @Test
    fun importRebindsCalendarsAndEventsToLocalIds() {
        val plan = planOnFreshInstall(roundTrip(rules))
        assertEquals(0, plan.skipped)
        val byType = plan.toInsert.associateBy { it.matchType }
        assertEquals(null, byType[MATCH_TITLE_CONTAINS]!!.calendarId)
        assertEquals(30L, byType[MATCH_ALL]!!.calendarId)          // via CalDAV id
        assertEquals(40L, byType[MATCH_DURATION_OVER]!!.calendarId) // via name
        assertEquals(100L, byType[MATCH_EVENT_ID]!!.eventId)
        assertEquals("Caldav-1-1", byType[MATCH_EVENT_ID]!!.importId)
        assertTrue(plan.toInsert.all { it.id == null })
    }

    @Test
    fun caldavIdWinsOverAName() {
        // same device after a reinstall: a renamed calendar still binds by its CalDAV id
        val entry = ContextualRuleBackupEntry(matchType = MATCH_ALL, calendarName = "Old name", caldavCalendarId = 1)
        assertEquals(30L, planOnFreshInstall(listOf(entry)).toInsert.single().calendarId)
    }

    @Test
    fun unbindableRulesAreSkippedNotWidened() {
        val entries = listOf(
            // calendar missing here: must not become an every-calendar rule
            ContextualRuleBackupEntry(matchType = MATCH_ALL, calendarName = "Gone", caldavCalendarId = 9),
            // event not synced (yet) here
            ContextualRuleBackupEntry(matchType = MATCH_EVENT_ID, importId = "Caldav-7-7"),
            ContextualRuleBackupEntry(matchType = MATCH_EVENT_ID, importId = ""),
        )
        val plan = planOnFreshInstall(entries)
        assertEquals(3, plan.skipped)
        assertTrue(plan.toInsert.isEmpty())
    }

    @Test
    fun importingTwiceAddsNothing() {
        val entries = roundTrip(rules)
        val first = planOnFreshInstall(entries).toInsert.mapIndexed { i, rule -> rule.copy(id = i + 1L) }
        val second = planOnFreshInstall(entries, existing = first)
        assertTrue(second.toInsert.isEmpty())
        assertEquals(0, second.skipped)
    }

    @Test
    fun duplicatesInsideOneFileCollapse() {
        val entry = ContextualRuleBackupEntry(matchType = MATCH_TITLE_CONTAINS, pattern = "gym")
        assertEquals(1, planOnFreshInstall(listOf(entry, entry)).toInsert.size)
    }

    @Test
    fun sameTitleRuleOnAnotherCalendarIsNotADuplicate() {
        val existing = listOf(ContextualRule(id = 1, matchType = MATCH_TITLE_CONTAINS, pattern = "gym"))
        val entry = ContextualRuleBackupEntry(matchType = MATCH_TITLE_CONTAINS, pattern = "gym", calendarName = "Work")
        assertEquals(1, planOnFreshInstall(listOf(entry), existing).toInsert.size)
    }

    @Test
    fun damagedOrForeignInputYieldsNothing() {
        assertTrue(ContextualRulesBackup.decode("").isEmpty())
        assertTrue(ContextualRulesBackup.decode("not json").isEmpty())
        assertTrue(ContextualRulesBackup.decode("{\"a\":1}").isEmpty())
        // unknown match types (a newer build's export) and nulls are dropped, not crashed on
        val decoded = ContextualRulesBackup.decode("[{\"matchType\":99},null,{\"matchType\":2}]")
        assertEquals(1, decoded.size)
        assertEquals("", decoded.single().pattern)
    }
}

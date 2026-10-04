package org.fossify.calendar.databases

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * A true migration test (MigrationTestHelper) needs a device, so this checks the cheap,
 * JVM-only half: the hand-written MIGRATION_11_12 SQL still matches what Room generated
 * for the entity, and v12 changed nothing else. Runs with the module dir (app/) as cwd.
 */
class EventsDatabaseMigrationTest {
    private val schemaDir = File("schemas/org.fossify.calendar.databases.EventsDatabase")

    private fun createSqlByTable(version: Int): Map<String, String> {
        val json = File(schemaDir, "$version.json").readText()
        // Room's exporter always writes tableName immediately followed by createSql
        val regex = Regex(""""tableName":\s*"([^"]+)",\s*"createSql":\s*"((?:[^"\\]|\\.)*)"""")
        return regex.findAll(json).associate { match ->
            val table = match.groupValues[1]
            table to match.groupValues[2].replace("\${TABLE_NAME}", table)
        }
    }

    @Test
    fun migration11to12CreatesTheTableRoomExpects() {
        val expected = createSqlByTable(12)["contextual_rules"]
        assertNotNull("contextual_rules missing from 12.json — rebuild to re-export", expected)
        assertEquals(expected, EventsDatabase.CREATE_CONTEXTUAL_RULES_SQL)
    }

    @Test
    fun version12OnlyAddsContextualRules() {
        val v11 = createSqlByTable(11)
        val v12 = createSqlByTable(12)
        assertEquals(v11, v12 - "contextual_rules")
    }

    @Test
    fun migration12to13GivesTheTableRoomExpects() {
        // ALTER TABLE ADD COLUMN appends, so the v12 table plus the new column must equal what
        // Room generated for v13; Room compares columns, but a drift here means a typo in the SQL
        val expected = createSqlByTable(13)["contextual_rules"]
        assertNotNull("contextual_rules missing from 13.json — rebuild to re-export", expected)
        assertEquals("ALTER TABLE contextual_rules ADD COLUMN caldav_calendar_id INTEGER", EventsDatabase.ADD_RULE_CALDAV_CALENDAR_ID_SQL)
        val afterAlter = EventsDatabase.CREATE_CONTEXTUAL_RULES_SQL.removeSuffix(")") + ", `caldav_calendar_id` INTEGER)"
        assertEquals(expected, afterAlter)
    }

    @Test
    fun version13OnlyChangesContextualRules() {
        val v12 = createSqlByTable(12)
        val v13 = createSqlByTable(13)
        assertEquals(v12 - "contextual_rules", v13 - "contextual_rules")
    }
}

package org.fossify.calendar.databases

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

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
    fun migration13to14GivesTheTableRoomExpects() {
        val expected = createSqlByTable(14)["contextual_rules"]
        assertNotNull("contextual_rules missing from 14.json, rebuild to re-export", expected)
        val v13 = createSqlByTable(13)["contextual_rules"]!!
        val afterAlter = v13.removeSuffix(")") + ", `key_name` TEXT, `key_color` INTEGER)"
        assertEquals(expected, afterAlter)
        assertEquals("ALTER TABLE contextual_rules ADD COLUMN key_name TEXT", EventsDatabase.ADD_RULE_KEY_NAME_SQL)
        assertEquals("ALTER TABLE contextual_rules ADD COLUMN key_color INTEGER", EventsDatabase.ADD_RULE_KEY_COLOR_SQL)
    }

    @Test
    fun version14OnlyChangesContextualRules() {
        assertEquals(createSqlByTable(13) - "contextual_rules", createSqlByTable(14) - "contextual_rules")
    }

    @Test
    fun version13OnlyChangesContextualRules() {
        val v12 = createSqlByTable(12)
        val v13 = createSqlByTable(13)
        assertEquals(v12 - "contextual_rules", v13 - "contextual_rules")
    }

    @Test
    fun migration14to15GivesTheTableRoomExpects() {
        val expected = createSqlByTable(15)["contextual_rules"]
        assertNotNull("contextual_rules missing from 15.json, rebuild to re-export", expected)
        val afterAlter = createSqlByTable(14)["contextual_rules"]!!.removeSuffix(")") +
            ", `uuid` TEXT NOT NULL DEFAULT '', `updated` INTEGER NOT NULL DEFAULT 0, `synced_updated` INTEGER" +
            ", `google_calendar_id` TEXT, `google_event_calendar_id` TEXT, `google_event_id` TEXT, `shared_json` TEXT)"
        assertEquals(expected, afterAlter)
    }

    @Test
    fun version15OnlyChangesContextualRules() {
        assertEquals(createSqlByTable(14) - "contextual_rules", createSqlByTable(15) - "contextual_rules")
    }

    // runs the real migration SQL on SQLite: existing rules survive, and each gets its own uuid
    @Test
    fun migration14to15KeepsEveryRuleAndGivesEachAUuid() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val statement = connection.createStatement()
            statement.execute(createSqlByTable(14)["contextual_rules"]!!)
            statement.execute(
                "INSERT INTO contextual_rules (id, calendar_id, match_type, pattern, event_id, import_id, enabled, caldav_calendar_id, key_name, key_color) VALUES " +
                    "(1, NULL, 2, 'on call', NULL, NULL, 1, NULL, 'On call', -1739917), " +
                    "(2, 5, 1, '', NULL, NULL, 0, 3, NULL, NULL), " +
                    "(3, NULL, 5, '', 42, 'Caldav-3-42', 1, NULL, NULL, NULL)"
            )

            EventsDatabase.MIGRATION_14_15_SQL.forEach { statement.execute(it) }

            val rows = ArrayList<Map<String, Any?>>()
            statement.executeQuery("SELECT * FROM contextual_rules ORDER BY id").use { result ->
                val columns = (1..result.metaData.columnCount).map { result.metaData.getColumnName(it) }
                while (result.next()) rows.add(columns.associateWith { result.getObject(it) })
            }

            assertEquals(3, rows.size)
            assertEquals(listOf("on call", "", ""), rows.map { it["pattern"] })
            assertEquals(listOf(2, 1, 5), rows.map { it["match_type"] })
            assertEquals(listOf(null, 5, null), rows.map { it["calendar_id"] })
            assertEquals(listOf(1, 0, 1), rows.map { it["enabled"] })
            assertEquals("On call", rows[0]["key_name"])
            assertEquals(-1739917, rows[0]["key_color"])
            assertEquals(3, rows[1]["caldav_calendar_id"])
            assertEquals("Caldav-3-42", rows[2]["import_id"])

            val uuids = rows.map { it["uuid"] as String }
            val v4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
            uuids.forEach { assertTrue(it, v4.matches(it)) }
            assertEquals(3, uuids.toSet().size)
            rows.forEach { row ->
                assertEquals(0, row["updated"])
                listOf("synced_updated", "google_calendar_id", "google_event_calendar_id", "google_event_id", "shared_json").forEach { assertNull(it, row[it]) }
            }

            // a rule added after the migration gets the column defaults Room declares
            statement.execute("INSERT INTO contextual_rules (match_type, pattern, enabled) VALUES (2, 'x', 1)")
            statement.executeQuery("SELECT uuid, updated FROM contextual_rules WHERE pattern = 'x'").use {
                it.next()
                assertEquals("", it.getString(1))
                assertEquals(0L, it.getLong(2))
            }
        }
    }
}

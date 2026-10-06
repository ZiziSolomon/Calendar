package org.fossify.calendar.helpers

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * A contextual rule as stored in the "Subtext rules" Google calendar, which the phone and the
 * laptop app (ZiziSolomon/subtext-web) share: one event per rule, this JSON in its description.
 * The web app's sharedRule.ts reads and writes the same format; the conformance fixtures hold
 * the two together. Pure (Gson's tree model, no Android types), so it's unit tested.
 *
 * Fields this version doesn't know are kept and written back unchanged, so an older app never
 * erases what a newer one added. A rule from a *newer* format version is still read, but is
 * read-only here.
 */
object SharedRuleCodec {
    const val FORMAT_VERSION = 1
    private val MATCH_TYPES = listOf("all", "title_contains", "title_regex", "duration_over", "event")
    private val KNOWN = setOf("v", "uuid", "updated", "enabled", "match", "calendar", "event", "key")
    private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

    data class SharedRule(
        val version: Int = FORMAT_VERSION,
        val uuid: String,
        /** ISO instant of the last edit. */
        val updated: String,
        val enabled: Boolean = true,
        val type: String,
        val pattern: String = "",
        /** Google calendar id (and its name as a hint) when scoped to one calendar. */
        val calendarId: String? = null,
        val calendarName: String? = null,
        /** For marks: Google calendar id, series (or single) event id, and the title as a hint. */
        val eventCalendarId: String? = null,
        val eventId: String? = null,
        val eventTitle: String? = null,
        val keyName: String? = null,
        /** ARGB, like every colour in the app; "#rrggbb" in storage. */
        val keyColor: Int? = null,
        /** Fields from a newer version of the format, kept as they were (JSON text by name). */
        val extra: Map<String, String> = emptyMap(),
    ) {
        val isReadOnly get() = version > FORMAT_VERSION
    }

    fun encode(rule: SharedRule): String {
        val out = JsonObject()
        out.addProperty("v", maxOf(rule.version, FORMAT_VERSION))
        out.addProperty("uuid", rule.uuid)
        out.addProperty("updated", rule.updated)
        out.addProperty("enabled", rule.enabled)
        out.add("match", JsonObject().apply {
            addProperty("type", rule.type)
            addProperty("pattern", rule.pattern)
        })
        rule.calendarId?.let { id ->
            out.add("calendar", JsonObject().apply {
                addProperty("id", id)
                rule.calendarName?.let { addProperty("name", it) }
            })
        }
        if (rule.eventCalendarId != null && rule.eventId != null) {
            out.add("event", JsonObject().apply {
                addProperty("calendarId", rule.eventCalendarId)
                addProperty("eventId", rule.eventId)
                rule.eventTitle?.let { addProperty("title", it) }
            })
        }
        if (rule.keyName != null || rule.keyColor != null) {
            out.add("key", JsonObject().apply {
                rule.keyName?.let { addProperty("name", it) }
                rule.keyColor?.let { addProperty("color", "#%06x".format(it and 0xFFFFFF)) }
            })
        }
        rule.extra.forEach { (name, json) -> if (!out.has(name)) out.add(name, JsonParser.parseString(json)) }
        return out.toString()
    }

    /** Null when the text isn't a usable rule (not JSON, unknown match type, no uuid). */
    fun decode(text: String): SharedRule? {
        val raw = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return null
        }
        if (!raw.isJsonObject) return null
        val obj = raw.asJsonObject

        val version = obj.int("v")?.takeIf { it >= 1 } ?: return null
        val uuid = obj.string("uuid")?.takeIf { it.isNotBlank() } ?: return null
        val match = obj.get("match")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val type = match.string("type")?.takeIf { it in MATCH_TYPES } ?: return null

        val calendar = obj.get("calendar")?.takeIf { it.isJsonObject }?.asJsonObject
        val calendarId = calendar?.string("id")
        val event = obj.get("event")?.takeIf { it.isJsonObject }?.asJsonObject
        val eventCalendarId = event?.string("calendarId")
        val eventId = event?.string("eventId")
        val hasEvent = eventCalendarId != null && eventId != null
        // a mark with nothing to point at can't match anything; drop it rather than keep a dead rule
        if (type == "event" && !hasEvent) return null

        val key = obj.get("key")?.takeIf { it.isJsonObject }?.asJsonObject
        val color = key?.string("color")?.takeIf { HEX_COLOR.matches(it) }?.let { (0xFF000000 or it.substring(1).toLong(16)).toInt() }

        return SharedRule(
            version = version,
            uuid = uuid,
            updated = obj.string("updated") ?: "1970-01-01T00:00:00Z",
            enabled = obj.get("enabled")?.let { !(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean) } ?: true,
            type = type,
            pattern = match.string("pattern") ?: "",
            calendarId = calendarId,
            calendarName = calendarId?.let { calendar.string("name") },
            eventCalendarId = if (hasEvent) eventCalendarId else null,
            eventId = if (hasEvent) eventId else null,
            eventTitle = if (hasEvent) event!!.string("title") else null,
            keyName = key?.string("name"),
            keyColor = color,
            extra = obj.entrySet().filter { it.key !in KNOWN }.associate { it.key to it.value.toString() },
        )
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.int(name: String): Int? {
        val element: JsonElement = get(name) ?: return null
        if (!element.isJsonPrimitive || !(element as JsonPrimitive).isNumber) return null
        val value = element.asDouble
        return if (value == Math.floor(value) && value <= Int.MAX_VALUE) value.toInt() else null
    }
}

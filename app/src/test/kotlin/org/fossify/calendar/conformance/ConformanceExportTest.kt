package org.fossify.calendar.conformance

import com.google.gson.GsonBuilder
import org.fossify.calendar.helpers.ContextualColors
import org.fossify.calendar.helpers.ContextualKey
import org.fossify.calendar.helpers.ContextualLanes
import org.fossify.calendar.helpers.ContextualMonthBars
import org.fossify.calendar.helpers.ContextualRuleEvaluator
import org.fossify.calendar.helpers.ContextualStripeBuilder
import org.fossify.calendar.helpers.FLAG_ALL_DAY
import org.fossify.calendar.helpers.MATCH_ALL
import org.fossify.calendar.helpers.MATCH_DURATION_OVER
import org.fossify.calendar.helpers.MATCH_TITLE_CONTAINS
import org.fossify.calendar.helpers.MATCH_TITLE_REGEX
import org.fossify.calendar.helpers.RegexPortability
import org.fossify.calendar.helpers.RegexRisk
import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.ContextualStripe
import org.fossify.calendar.models.Event
import org.joda.time.DateTimeZone
import org.joda.time.LocalDate
import org.joda.time.LocalDateTime
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes the phone's answers for a fixed set of scenarios to build/conformance/conformance.json.
 * The web app (ZiziSolomon/subtext-web) runs the same inputs through its TypeScript port and must
 * get the same outputs, so the laptop and phone can't drift apart (LAPTOP_PLAN.md, decision 3).
 *
 * Inputs are written in a neutral form both sides can read: timed events as local wall-clock
 * times in [ZONE], all-day events as an inclusive date range, calendars and events as string ids.
 * Each side converts that to its own model; here, Fossify's all-day convention of local midnight
 * to *noon* of the last day. All data is made up.
 *
 * Marks (MATCH_EVENT_ID) are left out: on the phone they key by local ids today, and the portable
 * Google-id form arrives with the shared rules (phase 19), with its own cases then.
 */
class ConformanceExportTest {
    private val zone = DateTimeZone.forID(ZONE)

    data class InEvent(
        val id: String,
        val title: String,
        val calendar: String,
        val color: Int,
        val allDay: Boolean,
        // timed: local wall-clock "yyyy-MM-ddTHH:mm"; all-day: "yyyy-MM-dd", end inclusive
        val start: String,
        val end: String,
    )

    data class InRule(
        val type: String,
        val pattern: String = "",
        val calendar: String? = null,
        val enabled: Boolean = true,
        val keyName: String? = null,
        val keyColor: Int? = null,
    )

    private val calendars = listOf("cal-family", "cal-work", "cal-school")
    private fun calendarId(name: String) = calendars.indexOf(name) + 1L

    private val events = listOf(
        InEvent("e-oncall", "On call", "cal-work", 0x1E88E5, false, "2026-10-05T09:00", "2026-10-09T17:00"),
        InEvent("e-early", "Early shift", "cal-work", 0x43A047, false, "2026-10-06T06:00", "2026-10-06T14:00"),
        InEvent("e-late", "Late shift", "cal-work", 0x43A047, false, "2026-10-07T14:00", "2026-10-07T22:00"),
        InEvent("e-night", "Night shift", "cal-work", 0x43A047, false, "2026-10-08T22:00", "2026-10-09T06:00"),
        InEvent("e-kids", "Kids weekend", "cal-family", 0xE53935, false, "2026-10-09T18:00", "2026-10-11T18:00"),
        InEvent("e-term", "School term", "cal-school", 0xFB8C00, true, "2026-09-01", "2026-10-23"),
        InEvent("e-halfterm", "Half term", "cal-school", 0xFB8C00, true, "2026-10-26", "2026-10-30"),
        InEvent("e-dentist", "Dentist", "cal-family", 0x8E24AA, false, "2026-10-07T10:00", "2026-10-07T10:30"),
        InEvent("e-standup", "Standup", "cal-work", 0x00ACC1, false, "2026-10-06T09:30", "2026-10-06T09:45"),
        InEvent("e-blip", "Reminder blip", "cal-work", 0x00ACC1, false, "2026-10-06T23:55", "2026-10-06T23:55"),
        InEvent("e-midnight", "Party", "cal-family", 0xD81B60, false, "2026-10-10T20:00", "2026-10-11T00:00"),
        InEvent("e-oneday", "Bank holiday", "cal-family", 0x7CB342, true, "2026-10-12", "2026-10-12"),
        // the clock change: Sunday 25 October 2026 has 25 hours in Europe/London
        InEvent("e-dst-allday", "Clocks go back", "cal-family", 0x5E35B1, true, "2026-10-25", "2026-10-25"),
        InEvent("e-dst-timed", "On call", "cal-work", 0x1E88E5, false, "2026-10-24T20:00", "2026-10-25T08:00"),
        InEvent("e-dst-short", "Gym", "cal-family", 0x7CB342, false, "2026-10-25T01:30", "2026-10-25T03:00"),
        InEvent("e-accent", "Café shift", "cal-work", 0x43A047, false, "2026-10-21T08:00", "2026-10-21T12:00"),
        InEvent("e-upper", "ON CALL backup", "cal-work", 0x1E88E5, false, "2026-10-20T00:00", "2026-10-21T00:00"),
        InEvent("e-trip", "Trip to Leeds", "cal-family", 0x8E24AA, false, "2026-10-19T07:00", "2026-10-20T19:00"),
        InEvent("e-code", "SHIFT-A12", "cal-work", 0x43A047, false, "2026-10-22T07:00", "2026-10-22T15:00"),
        InEvent("e-twoday", "Conference", "cal-work", 0x00ACC1, true, "2026-10-22", "2026-10-23"),
    )

    private val rules = listOf(
        InRule("all", calendar = "cal-school", keyName = "School", keyColor = 0xFFFB8C00.toInt()),
        InRule("title_contains", "on call", keyName = "On call"),
        InRule("title_contains", "shift", calendar = "cal-work", keyName = "Shifts", keyColor = 0xFF43A047.toInt()),
        InRule("title_regex", "^Kids\\b"),
        InRule("title_regex", "^SHIFT-[A-Z]\\d+$"),
        InRule("title_regex", "café"),
        InRule("duration_over", "2880", calendar = "cal-family"),
        InRule("title_contains", "dentist", enabled = false),
        // risky and malformed regexes must match nothing, never hang or crash
        InRule("title_regex", "(a+)+$"),
        InRule("title_regex", "([unclosed"),
        InRule("title_contains", "   "),
    )

    private fun toFossify(event: InEvent, index: Int): Event {
        val calendarId = calendarId(event.calendar)
        return if (event.allDay) {
            val first = LocalDate.parse(event.start).toDateTimeAtStartOfDay(zone)
            val lastNoon = LocalDate.parse(event.end).toLocalDateTime(org.joda.time.LocalTime(12, 0)).toDateTime(zone)
            Event(id = index + 1L, title = event.title, calendarId = calendarId, color = event.color,
                startTS = first.millis / 1000, endTS = lastNoon.millis / 1000, flags = FLAG_ALL_DAY)
        } else {
            Event(id = index + 1L, title = event.title, calendarId = calendarId, color = event.color,
                startTS = LocalDateTime.parse(event.start).toDateTime(zone).millis / 1000,
                endTS = LocalDateTime.parse(event.end).toDateTime(zone).millis / 1000)
        }
    }

    private fun toFossify(rule: InRule) = ContextualRule(
        id = null,
        calendarId = rule.calendar?.let { calendarId(it) },
        matchType = when (rule.type) {
            "all" -> MATCH_ALL
            "title_contains" -> MATCH_TITLE_CONTAINS
            "title_regex" -> MATCH_TITLE_REGEX
            "duration_over" -> MATCH_DURATION_OVER
            else -> error(rule.type)
        },
        pattern = rule.pattern,
        enabled = rule.enabled,
        keyName = rule.keyName,
        keyColor = rule.keyColor,
    )

    private fun eventRef(eventId: Long) = events[(eventId - 1).toInt()].id

    private fun stripeOut(stripe: ContextualStripe) = mapOf(
        "day" to stripe.dayIndex, "start" to stripe.startMinute, "end" to stripe.endMinute,
        "color" to stripe.color, "title" to stripe.title, "event" to eventRef(stripe.eventId),
        "lane" to stripe.lane, "laneCount" to stripe.laneCount,
    )

    @Test
    fun exportConformanceCases() {
        val fossifyEvents = events.mapIndexed { i, e -> toFossify(e, i) }
        val evaluator = ContextualRuleEvaluator(rules.map { toFossify(it) }, zone)

        // the same contextual flag and display EventsHelper sets before anything is drawn
        val evaluated = fossifyEvents.map { event ->
            val contextual = evaluator.isContextual(event)
            val display = if (contextual) evaluator.display(event) else null
            event.isContextual = contextual
            event.contextualKeyName = display?.keyName
            event.contextualKeyColor = display?.keyColor
            mapOf("event" to eventRef(event.id!!), "contextual" to contextual, "keyName" to display?.keyName, "keyColor" to display?.keyColor)
        }
        val contexts = fossifyEvents.filter { it.isContextual }

        val weeks = listOf("2026-10-05", "2026-10-19").map { first ->
            val stripes = ContextualLanes.forColumns(ContextualStripeBuilder.build(contexts, LocalDate.parse(first), 7, zone, fallbackColor = FALLBACK))
            mapOf(
                "firstDay" to first,
                "days" to 7,
                "stripes" to stripes.map { stripeOut(it) },
                "key" to ContextualKey.entries(stripes).map { mapOf("title" to it.title, "color" to it.color, "event" to eventRef(it.eventId)) },
            )
        }

        val monthFirst = "2026-09-28"
        val monthStripes = ContextualStripeBuilder.build(contexts, LocalDate.parse(monthFirst), 42, zone, fallbackColor = FALLBACK)
        val bars = ContextualMonthBars.layout(monthStripes)
        val month = mapOf(
            "firstDay" to monthFirst,
            "days" to 42,
            "bars" to bars.map { mapOf("row" to it.row, "start" to it.start, "end" to it.end, "lane" to it.lane, "color" to it.color, "title" to it.title, "event" to eventRef(it.eventId)) },
            // "now" = Wednesday 14 Oct (grid index 16) at 12:00
            "endedAtNow" to bars.map { ContextualMonthBars.hasEnded(it, nowDayIndex = 16, nowMinute = 720) },
        )

        val lanes = listOf(
            listOf(intArrayOf(0, 540, 1020)),
            listOf(intArrayOf(0, 0, 1440), intArrayOf(0, 360, 840)),
            listOf(intArrayOf(0, 360, 840), intArrayOf(0, 840, 1320)),
            listOf(intArrayOf(0, 0, 1440), intArrayOf(0, 360, 720), intArrayOf(0, 780, 1080)),
            listOf(intArrayOf(0, 0, 300), intArrayOf(0, 200, 500), intArrayOf(0, 400, 700)),
            listOf(intArrayOf(0, 360, 600), intArrayOf(0, 400, 500), intArrayOf(0, 1080, 1320), intArrayOf(1, 0, 1440)),
            listOf(intArrayOf(0, 0, 600), intArrayOf(0, 100, 500), intArrayOf(0, 200, 400)),
        ).map { set ->
            val placements = ContextualLanes.assign(set.map { ContextualLanes.Interval(it[0], it[1], it[2]) })
            mapOf("intervals" to set.map { it.toList() }, "placements" to placements.map { listOf(it.lane, it.laneCount) })
        }

        val colorSequences = listOf(
            listOf("On call", "Early shift", "on call ", "Kids weekend"),
            (1..12).map { "Context $it" },
        ).map { titles ->
            var slots = emptyMap<String, Int>()
            val assigned = titles.map { title ->
                val (slot, updated) = ContextualColors.assign(slots, title)
                slots = updated ?: slots
                slot
            }
            mapOf("titles" to titles, "slots" to assigned)
        }

        val riskPatterns = listOf(
            "(a+)+", "(a*)*", "(a|a)*", "(a|b)+", "(\\w+\\s?)*", "(?>a+)+", "(a+)", "a+b+", "^(on call|shift)\\b",
            "\\1", "(a)\\1", "\\k<n>", "[(+)]+", "(a{2,})+", "(a{2,3})+", "x{3,}", "(a?)+", "\\(a+\\)+",
        ).map { mapOf("pattern" to it, "risk" to RegexRisk.check(it)?.name) }

        val portabilityPatterns = listOf(
            "(?>a+)", "a*+", "a++", "a?+", "a{2}+", "(?i)shift", "(?-i)x", "(?:a)+", "(?=a)", "(?!a)", "(?<=a)b", "(?<n>a)",
            "[[:alpha:]]", "[a-z&&[^e]]", "[\\[]", "\\Qa.b\\E", "\\Ashift", "shift\\z", "\\p{L}", "\\h", "\\v",
            "\\d\\w\\s\\b", "\\x41\\u0041", "\\+\\+", "\\.", "a+b?c*", "[]a]", "[^]a]", "x{2,3}",
        ).map { mapOf("pattern" to it, "problem" to RegexPortability.check(it)?.name) }

        // the same pattern on both regex engines: Java (phone) vs the browser's RegExp (laptop)
        val regexTitles = listOf("On call", "on-call", "Café shift", "CAFÉ", "SHIFT-A12", "shift-a12", "Kids weekend", "Kidsweekend", "Ünïcode", "tab\there")
        val regexPatterns = listOf("^on.call$", "café", "\\bshift\\b", "^SHIFT-[A-Z]\\d+$", "^Kids\\b", "\\w+", "^\\w+$", "\\s", "[[:alpha:]]", "ü")
        val regexMatches = regexPatterns.map { pattern ->
            val rule = ContextualRule(id = null, matchType = MATCH_TITLE_REGEX, pattern = pattern)
            val single = ContextualRuleEvaluator(listOf(rule), zone)
            mapOf("pattern" to pattern, "matches" to regexTitles.map { title -> single.isContextual(Event(id = 1L, title = title)) })
        }

        val containsTitles = listOf("On call", "ON CALL", "Café", "CAFÉ", "Straße", "STRASSE", "İstanbul", "istanbul")
        val containsPatterns = listOf("on call", "café", "straße", "strasse", "istanbul")
        val containsMatches = containsPatterns.map { pattern ->
            val single = ContextualRuleEvaluator(listOf(ContextualRule(id = null, matchType = MATCH_TITLE_CONTAINS, pattern = pattern)), zone)
            mapOf("pattern" to pattern, "matches" to containsTitles.map { title -> single.isContextual(Event(id = 1L, title = title)) })
        }

        val document = mapOf(
            "comment" to "Generated by ConformanceExportTest in ZiziSolomon/Calendar (feature/contextual-events). Do not edit by hand.",
            "zone" to ZONE,
            "fallbackColor" to FALLBACK,
            "events" to events,
            "rules" to rules,
            "evaluated" to evaluated,
            "weeks" to weeks,
            "month" to month,
            "lanes" to lanes,
            "colorSlots" to colorSequences,
            "regexRisk" to riskPatterns,
            "regexPortability" to portabilityPatterns,
            "regexMatch" to mapOf("titles" to regexTitles, "cases" to regexMatches),
            "containsMatch" to mapOf("titles" to containsTitles, "cases" to containsMatches),
        )

        val out = File("build/conformance/conformance.json")
        out.parentFile.mkdirs()
        out.writeText(GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(document))
        assertTrue(out.length() > 0)
    }

    companion object {
        const val ZONE = "Europe/London"
        const val FALLBACK = 0x777777
    }
}

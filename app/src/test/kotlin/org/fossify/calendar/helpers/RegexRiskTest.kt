package org.fossify.calendar.helpers

import org.fossify.calendar.helpers.RegexRisk.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegexRiskTest {
    private fun risk(pattern: String) = RegexRisk.check(pattern)

    @Test
    fun classicNestedQuantifiersAreFlagged() {
        assertEquals(Risk.NESTED_QUANTIFIER, risk("(a+)+$"))
        assertEquals(Risk.NESTED_QUANTIFIER, risk("(a*)*"))
        assertEquals(Risk.NESTED_QUANTIFIER, risk("""^(\w+\s?)*$"""))
        assertEquals(Risk.NESTED_QUANTIFIER, risk("(x+x+)+y"))
        assertEquals(Risk.NESTED_QUANTIFIER, risk("(a+){2,}"))
    }

    @Test
    fun nestingDeeperThanOneGroupIsStillCaught() {
        assertEquals(Risk.NESTED_QUANTIFIER, risk("((a+)b)+"))
        assertEquals(Risk.NESTED_QUANTIFIER, risk("(?:(?:a*)c)*"))
    }

    @Test
    fun quantifiedAlternationIsFlagged() {
        assertEquals(Risk.QUANTIFIED_ALTERNATION, risk("(a|aa)+$"))
        assertEquals(Risk.QUANTIFIED_ALTERNATION, risk("(a|a)*"))
    }

    @Test
    fun backreferencesAreFlagged() {
        assertEquals(Risk.BACKREFERENCE, risk("""(a)\1"""))
        assertEquals(Risk.BACKREFERENCE, risk("""(?<x>a)\k<x>"""))
    }

    @Test
    fun ordinaryTitlePatternsPass() {
        listOf(
            """^(on call|shift)\b""",
            "^Ctx:",
            "shift",
            """\b(kids|children)\b""",
            "^[A-Z]{2,4}-\\d+",
            ".*week.*",
            "(?i)holiday",
            "(ab?)+",           // repeated group, but only a bounded ? inside
            "(a+)?",            // the outer quantifier is bounded
            "(?>a+)+",          // atomic groups can't backtrack into
            "[(+*]+",           // quantifier characters inside a class are literal
            """\(a+\)+""",      // escaped parens are not a group
            "[]a+]+",           // ']' first in a class is literal
            "",
        ).forEach { assertNull("wrongly flagged: $it", risk(it)) }
    }

    @Test
    fun escapedDigitInsideAClassIsNotABackreference() {
        assertNull(risk("""[\1]"""))
    }
}

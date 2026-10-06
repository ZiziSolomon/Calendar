package org.fossify.calendar.helpers

import org.fossify.calendar.helpers.RegexPortability.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegexPortabilityTest {
    @Test
    fun ordinaryPatternsArePortable() {
        listOf("^(on call|shift)\\b", "^SHIFT-[A-Z]\\d+$", "café", "(?:a|b)c", "(?=x)y", "(?<=a)b", "(?<n>a)", "\\+\\+", "[]a]", "x{2,3}")
            .forEach { assertNull(it, RegexPortability.check(it)) }
    }

    @Test
    fun javaOnlyGroupsAndFlagsAreRefused() {
        assertEquals(Problem.ATOMIC_GROUP, RegexPortability.check("(?>a+)b"))
        assertEquals(Problem.INLINE_FLAGS, RegexPortability.check("(?i)shift"))
        assertEquals(Problem.INLINE_FLAGS, RegexPortability.check("(?-i)x"))
    }

    @Test
    fun possessiveQuantifiersAreRefused() {
        listOf("a*+", "a++", "a?+", "a{2}+").forEach { assertEquals(it, Problem.POSSESSIVE_QUANTIFIER, RegexPortability.check(it)) }
    }

    @Test
    fun classUnionAndIntersectionAreRefused() {
        // Java reads [[:alpha:]] as a nested class; the browser reads a class followed by "]"
        assertEquals(Problem.CLASS_SET_OPERATION, RegexPortability.check("[[:alpha:]]"))
        assertEquals(Problem.CLASS_SET_OPERATION, RegexPortability.check("[a-z&&[^e]]"))
        assertNull(RegexPortability.check("[\\[]"))
    }

    @Test
    fun javaOnlyEscapesAreRefused() {
        listOf("\\Qa.b\\E", "\\Ashift", "shift\\z", "\\p{L}", "\\h", "\\v").forEach {
            assertEquals(it, Problem.JAVA_ONLY_ESCAPE, RegexPortability.check(it))
        }
    }

    @Test
    fun theEvaluatorNeverRunsANonPortablePattern() {
        // it would match on the phone; refusing it keeps phone and laptop in step
        val rule = org.fossify.calendar.models.ContextualRule(id = null, matchType = MATCH_TITLE_REGEX, pattern = "[[:alpha:]]")
        val event = org.fossify.calendar.models.Event(id = 1L, title = "alpha")
        assertEquals(false, ContextualRuleEvaluator(listOf(rule)).isContextual(event))
    }
}

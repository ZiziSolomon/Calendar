package org.fossify.calendar.helpers

import org.fossify.calendar.models.ContextualRule
import org.fossify.calendar.models.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedRunnerTest {
    @Test
    fun returnsTheResultOfAFastTask() {
        assertEquals(42, BoundedRunner(1000).run { 42 })
    }

    @Test
    fun catastrophicRegexIsAbandonedNotWaitedFor() {
        // JDK 9+ memoises simple cases like (a+)+$, but a backreference defeats that: this one
        // takes ~30s on 28 chars on the build JDK. On Android (ICU) many more patterns are this bad.
        val evil = ContextualRule(id = null, matchType = MATCH_TITLE_REGEX, pattern = "(a*)*\\1b")
        val events = listOf(Event(id = 1L, title = "a".repeat(28)))

        val started = System.nanoTime()
        val result = BoundedRunner(100).run { ContextualRulePreview.compute(evil, events) }
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        assertNull(result)
        assertTrue("waited ${tookMillis}ms", tookMillis < 2000)
    }

    @Test
    fun recoversWithAFreshWorkerAfterATimeout() {
        val runner = BoundedRunner(50)
        assertNull(runner.run { Thread.sleep(5_000) })
        // the stuck worker was replaced, so the next task isn't queued behind it
        assertEquals("ok", runner.run { "ok" })
    }

    @Test
    fun returnsNullOnceShutDown() {
        val runner = BoundedRunner(1000)
        runner.shutdown()
        assertNull(runner.run { 1 })
    }
}

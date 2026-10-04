package org.fossify.calendar.helpers

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
        // Run directly: the evaluator now refuses this shape (RegexRisk), but the runner must
        // still cope with anything RegexRisk doesn't recognise.
        val evil = Regex("(a*)*\\1b")
        val title = "a".repeat(28)

        val started = System.nanoTime()
        val result = BoundedRunner(100).run { evil.containsMatchIn(title) }
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

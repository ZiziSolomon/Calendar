package org.fossify.calendar.helpers

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs a task on a worker thread and gives up waiting after [budgetMillis].
 *
 * Exists because regex matching can't be time-limited from the inside on Android: its
 * java.util.regex hands the input string to native ICU, so neither interrupts nor tricks like a
 * deadline-checking CharSequence reach the matcher. A worker that overruns is abandoned (a
 * catastrophic match can't be stopped, only outlived) and replaced; it's a daemon at minimum
 * priority so it can't keep the process alive or starve the UI while it finishes.
 *
 * Blocks the caller, so call it from a background thread.
 */
class BoundedRunner(private val budgetMillis: Long) {
    private var worker = newWorker()

    /** The task's result, or null if it overran the budget. */
    @Synchronized
    fun <T> run(task: () -> T): T? {
        if (worker.isShutdown) {
            return null
        }

        val future = worker.submit(Callable { task() })
        return try {
            future.get(budgetMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            worker.shutdownNow()
            worker = newWorker()
            null
        }
    }

    @Synchronized
    fun shutdown() {
        worker.shutdownNow()
    }

    private fun newWorker(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "contextual-rule-preview").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
}

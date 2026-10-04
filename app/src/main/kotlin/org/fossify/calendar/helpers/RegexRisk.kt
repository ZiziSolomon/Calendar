package org.fossify.calendar.helpers

/**
 * Spots the regex shapes that can backtrack exponentially, so they can be refused before they
 * ever run. Android's regex engine (ICU) has no timeout and can't be interrupted, and a rule's
 * regex runs on every event fetch, so this is the first line of defence; the editor's
 * BoundedRunner is the backstop for anything this misses.
 *
 * Deliberately conservative: it also flags some harmless patterns like (a|b)+, which can be
 * rewritten as a character class ([ab]+). For a title filter on a phone that's a fair trade.
 */
object RegexRisk {
    enum class Risk { BACKREFERENCE, NESTED_QUANTIFIER, QUANTIFIED_ALTERNATION }

    private class Group(val atomic: Boolean) {
        var hasUnboundedQuantifier = false
        var hasAlternation = false
    }

    fun check(pattern: String): Risk? {
        val stack = ArrayDeque<Group>()
        var i = 0
        var inClass = false
        // what the previous token was, so a quantifier knows what it applies to
        var lastClosedGroup: Group? = null

        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(i + 1)
                    if (!inClass && next != null && (next in '1'..'9' || next == 'k')) {
                        return Risk.BACKREFERENCE
                    }
                    lastClosedGroup = null
                    i += 2
                    continue
                }

                inClass -> if (c == ']') inClass = false

                c == '[' -> {
                    inClass = true
                    lastClosedGroup = null
                    // a ']' right after '[' or '[^' is a literal, not the end of the class
                    if (pattern.getOrNull(i + 1) == '^') i++
                    if (pattern.getOrNull(i + 1) == ']') i++
                }

                c == '(' -> {
                    stack.addLast(Group(atomic = pattern.startsWith("(?>", i)))
                    lastClosedGroup = null
                }

                c == ')' -> {
                    val closed = stack.removeLastOrNull() ?: Group(atomic = false)
                    // what's inside a group is also inside its parent
                    stack.lastOrNull()?.let { parent ->
                        parent.hasUnboundedQuantifier = parent.hasUnboundedQuantifier || closed.hasUnboundedQuantifier
                    }
                    lastClosedGroup = closed
                }

                c == '|' -> {
                    stack.lastOrNull()?.hasAlternation = true
                    lastClosedGroup = null
                }

                isUnboundedQuantifier(pattern, i) -> {
                    val target = lastClosedGroup
                    if (target != null && !target.atomic) {
                        if (target.hasUnboundedQuantifier) return Risk.NESTED_QUANTIFIER
                        if (target.hasAlternation) return Risk.QUANTIFIED_ALTERNATION
                    }
                    stack.lastOrNull()?.hasUnboundedQuantifier = true
                    lastClosedGroup = null
                    i = endOfQuantifier(pattern, i)
                    continue
                }

                else -> lastClosedGroup = null
            }
            i++
        }
        return null
    }

    // + and * always; {n,} with no upper bound. ? and {n,m} are bounded, so they're fine.
    private fun isUnboundedQuantifier(pattern: String, i: Int): Boolean {
        val c = pattern[i]
        if (c == '+' || c == '*') {
            return true
        }

        if (c == '{') {
            val close = pattern.indexOf('}', i)
            if (close == -1) return false
            val body = pattern.substring(i + 1, close)
            return Regex("""\d+,""").matches(body)
        }
        return false
    }

    private fun endOfQuantifier(pattern: String, i: Int): Int {
        var end = if (pattern[i] == '{') pattern.indexOf('}', i) + 1 else i + 1
        // lazy (?) or possessive (+) suffix belongs to the same quantifier
        if (pattern.getOrNull(end) == '?' || pattern.getOrNull(end) == '+') end++
        return end
    }
}

package org.fossify.calendar.helpers

/**
 * A rule's regex runs on Java's engine here and on the browser's RegExp in the laptop app
 * (ZiziSolomon/subtext-web). Most syntax means the same on both, but some is Java-only or reads
 * differently, and a rule must never match one set of events on the phone and another on the
 * laptop. Patterns using those constructs are refused on **both** sides. The web app has the same
 * check (regexPortability.ts), and the conformance fixtures hold the two together.
 *
 * Known gap, documented rather than refused: on Android, \w, \b and \d also count accented
 * letters and non-ASCII digits, while the browser counts only ASCII.
 */
object RegexPortability {
    enum class Problem {
        ATOMIC_GROUP,           // (?>…): Java only
        POSSESSIVE_QUANTIFIER,  // a*+ a++ a?+ a{2}+: Java only
        INLINE_FLAGS,           // (?i) (?x)…: Java only
        CLASS_SET_OPERATION,    // [a[b]] or [a&&b]: Java union/intersection; the browser reads it literally
        JAVA_ONLY_ESCAPE,       // \Q \E \A \Z \z \G \h \H \R \X \p \P \e \a \v and other letter escapes
    }

    // escapes that mean the same thing on both engines
    private val SHARED_LETTER_ESCAPES = setOf('d', 'D', 'w', 'W', 's', 'S', 'b', 'B', 'n', 'r', 't', 'f', 'x', 'u', 'c')

    fun check(pattern: String): Problem? {
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]

            if (c == '\\') {
                val next = pattern.getOrNull(i + 1)
                if (next != null && next.isAsciiLetter() && next !in SHARED_LETTER_ESCAPES) return Problem.JAVA_ONLY_ESCAPE
                i += 2  // skip the escaped character
                continue
            }

            if (inClass) {
                if (c == '[') return Problem.CLASS_SET_OPERATION
                if (c == '&' && pattern.getOrNull(i + 1) == '&') return Problem.CLASS_SET_OPERATION
                if (c == ']') inClass = false
                i++
                continue
            }

            if (c == '[') {
                inClass = true
                // a ']' right after '[' or '[^' is a literal, not the end of the class
                if (pattern.getOrNull(i + 1) == '^') i++
                if (pattern.getOrNull(i + 1) == ']') i++
                i++
                continue
            }

            if (c == '(' && pattern.getOrNull(i + 1) == '?') {
                val kind = pattern.getOrNull(i + 2)
                if (kind == '>') return Problem.ATOMIC_GROUP
                if (kind != null && (kind.isAsciiLetter() || kind == '-')) return Problem.INLINE_FLAGS
                i++
                continue
            }

            // a quantifier followed by '+' is possessive; '(?' is a group opener, not a quantifier
            if ((c == '*' || c == '+' || c == '?' || c == '}') && pattern.getOrNull(i + 1) == '+') {
                if (!(c == '?' && pattern.getOrNull(i - 1) == '(')) return Problem.POSSESSIVE_QUANTIFIER
            }
            i++
        }
        return null
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
}

package app.minimalshell

import java.math.BigDecimal
import java.math.MathContext

/**
 * Tiny expression evaluator for the drawer search box.
 *
 * Grammar, loosest-binding first (each level calls the next one down):
 *   expr    = term   (('+' | '-') term)*
 *   term    = unary  (('*' | '/' | '%' | implicit) unary)*     implicit: "2(3)" means 2*3
 *   unary   = ('-' | '+') unary | power
 *   power   = postfix ('^' unary)?                              right-associative, so 2^3^2 = 2^9
 *   postfix = atom '%'?                                         "50%" = 0.5 when nothing follows it
 *   atom    = number | '(' expr ')'
 *
 * Because unary sits ABOVE power, "-2^2" is -(2^2) = -4, which is what people expect.
 */
object Calc {
    // Things made only of digits and dashes that are NOT sums, so no result row pops up for them.
    private val NotMath = listOf(
        Regex("""\d\s+[\d.]"""), // "2 3": two numbers with only a space between them
        Regex("""(^|[^\d.])0\d{3,}"""), // "0300...": a number with a leading zero is a phone number
        Regex("""^\d{3,4}-\d{4,}$"""), // "555-1234"
        Regex("""^\d{4}-\d{1,2}-\d{1,2}$|^\d{1,2}-\d{1,2}-\d{4}$"""), // dates like 12-05-2026
    )
    private val Huge = BigDecimal("1E+15")
    private val Tiny = BigDecimal("1E-9")

    fun eval(q: String): String? {
        val looksLikeMath = q.any { it.isDigit() } &&
            q.any { it in "+-*/^%" } &&
            q.all { it.isDigit() || it in "+-*/^%(). " }
        if (!looksLikeMath) return null
        if (NotMath.any { it.containsMatchIn(q.trim()) }) return null
        return try {
            val v = Parser(q.filter { !it.isWhitespace() }).parse()
            if (v.isNaN() || v.isInfinite()) null
            else format(v)
        } catch (_: IllegalArgumentException) {
            null // malformed input, including number-format errors, which are IllegalArgumentExceptions too
        } catch (_: StackOverflowError) {
            null // absurdly deep nesting like "((((((...": give up quietly
        }
    }

    private fun format(v: Double): String {
        val d = BigDecimal(v).round(MathContext(12)).stripTrailingZeros()
        val extreme = d.signum() != 0 && (d.abs() >= Huge || d.abs() < Tiny)
        // 2^1000 would be a 300-digit wall of text; show it as 1.07150860719e301 instead
        return if (extreme) d.toString().replace("E+", "e").replace("E", "e") else d.toPlainString()
    }

    private class Parser(val s: String) {
        var i = 0

        /** Current character, or NUL past the end, so we never index out of bounds. */
        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException()
            return v
        }

        fun expr(): Double {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = unary()
            while (true) {
                val c = peek()
                when {
                    c == '*' || c == '/' || c == '%' -> {
                        i++
                        val r = unary()
                        v = when (c) {
                            '*' -> v * r
                            '/' -> v / r
                            else -> v % r
                        }
                    }
                    c == '(' -> v *= unary() // implicit multiplication: 2(3), (1+2)(3)
                    else -> return v
                }
            }
        }

        fun unary(): Double {
            if (peek() == '-') { i++; return -unary() }
            if (peek() == '+') { i++; return unary() }
            return power()
        }

        fun power(): Double {
            val b = postfix()
            if (peek() == '^') {
                i++
                return Math.pow(b, unary())
            }
            return b
        }

        /** '%' right after a value is "percent" only when nothing that could be an operand follows it. */
        fun postfix(): Double {
            val v = atom()
            if (peek() == '%') {
                val next = if (i + 1 < s.length) s[i + 1] else '\u0000'
                if (next == '\u0000' || next in "+-*/^)") {
                    i++
                    return v / 100.0
                }
            }
            return v
        }

        fun atom(): Double {
            if (peek() == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException()
                i++
                return v
            }
            val start = i
            while (peek().isDigit() || peek() == '.') i++
            if (start == i) throw IllegalArgumentException()
            return s.substring(start, i).toDouble()
        }
    }
}

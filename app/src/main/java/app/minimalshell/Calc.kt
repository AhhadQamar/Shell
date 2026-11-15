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
    fun eval(q: String): String? {
        val looksLikeMath = q.any { it.isDigit() } &&
            q.any { it in "+-*/^%" } &&
            q.all { it.isDigit() || it in "+-*/^%(). " }
        if (!looksLikeMath) return null
        return try {
            val v = Parser(q.filter { !it.isWhitespace() }).parse()
            if (v.isNaN() || v.isInfinite()) null
            else BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
        } catch (_: IllegalArgumentException) {
            null // malformed input, including number-format errors, which are IllegalArgumentExceptions too
        } catch (_: StackOverflowError) {
            null // absurdly deep nesting like "((((((...": give up quietly
        }
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

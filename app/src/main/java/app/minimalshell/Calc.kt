package app.minimalshell

import java.math.BigDecimal
import java.math.MathContext

/** Tiny expression evaluator for the drawer search box: + - * / % ^ and parentheses. */
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
        } catch (_: Throwable) {
            null
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException()
            return v
        }

        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = pow()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]
                val r = pow()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }

        fun pow(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') {
                i++
                return Math.pow(b, pow())
            }
            return b
        }

        fun unary(): Double {
            if (s[i] == '-') { i++; return -unary() }
            if (s[i] == '+') { i++; return unary() }
            return atom()
        }

        fun atom(): Double {
            if (s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalArgumentException()
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException()
            return s.substring(start, i).toDouble()
        }
    }
}

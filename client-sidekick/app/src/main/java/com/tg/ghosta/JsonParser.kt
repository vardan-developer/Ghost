package com.tg.ghosta

/**
 * Minimal JSON parser for the newline-delimited command protocol.
 * Returns Map<String, Any?> / List<Any?> / String / Long / Double /
 * Boolean / null. Numbers without '.'/'e' come back as Long.
 */
object JsonParser {

    class JsonException(msg: String) : Exception(msg)

    fun parse(s: String): Any? {
        val p = Parser(s)
        p.ws()
        val v = p.value()
        p.ws()
        if (!p.eof()) throw JsonException("trailing characters at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun eof() = i >= s.length

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            if (eof()) throw JsonException("unexpected end of input")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("bad literal at $i")
            i += word.length
            return v
        }

        fun obj(): LinkedHashMap<String, Any?> {
            expect('{')
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                expect(':')
                m[k] = value()
                ws()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw JsonException("expected ',' or '}' at $i")
                }
            }
        }

        fun arr(): ArrayList<Any?> {
            expect('[')
            val a = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; return a }
            while (true) {
                a.add(value())
                ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return a }
                    else -> throw JsonException("expected ',' or ']' at $i")
                }
            }
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw JsonException("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (eof()) throw JsonException("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw JsonException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (peek() == '-') i++
            while (!eof() && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val tok = s.substring(start, i)
            if (tok.isEmpty()) throw JsonException("bad number at $start")
            return if (tok.any { it == '.' || it == 'e' || it == 'E' }) {
                tok.toDouble()
            } else {
                tok.toLong()
            }
        }

        fun peek(): Char {
            if (eof()) throw JsonException("unexpected end of input")
            return s[i]
        }

        fun expect(c: Char) {
            if (peek() != c) throw JsonException("expected '$c' at $i")
            i++
        }
    }
}

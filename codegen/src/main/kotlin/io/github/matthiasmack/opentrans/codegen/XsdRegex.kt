package io.github.matthiasmack.opentrans.codegen

/**
 * Translates an XSD pattern into a Java regex with the same meaning.
 *
 * The syntax is nearly identical, but some escapes differ:
 * - `\w` in XSD is "not punctuation, separator or other" (any letter, mark,
 *   number or symbol, incl. umlauts); in Java it is only `[a-zA-Z_0-9]`.
 * - `\d` in XSD is any Unicode decimal digit; in Java only `[0-9]`.
 * - `\i` / `\c` (XML name characters) have no Java equivalent; they do not
 *   occur in the openTRANS schema and are rejected so we notice if they do.
 * XSD patterns are implicitly anchored; callers use `Regex.matches`.
 */
object XsdRegex {
    private const val WORD = """\p{L}\p{M}\p{N}\p{S}"""

    fun toJava(xsd: String): String {
        val out = StringBuilder()
        var inClass = 0
        var i = 0
        while (i < xsd.length) {
            val c = xsd[i]
            when {
                c == '\\' && i + 1 < xsd.length -> {
                    when (val next = xsd[i + 1]) {
                        'w' -> out.append(if (inClass > 0) WORD else "[$WORD]")
                        'W' -> out.append(if (inClass > 0) error("\\W inside a character class: $xsd") else "[^$WORD]")
                        'd' -> out.append("""\p{Nd}""")
                        'D' -> out.append("""\P{Nd}""")
                        'i', 'I', 'c', 'C' -> error("XML name escape \\$next is not supported: $xsd")
                        else -> out.append(c).append(next)
                    }
                    i += 2
                    continue
                }
                c == '[' -> inClass++
                c == ']' && inClass > 0 -> inClass--
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}

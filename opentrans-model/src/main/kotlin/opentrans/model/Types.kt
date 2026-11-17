package opentrans.model

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/*
 * Hand-written value types used by the generated model.
 * Everything else in this package is generated from the XSD by :codegen.
 */

/**
 * bmecat:dtDATETIME.
 *
 * Kept as its lexical value because the XSD allows partial dates
 * ("2009", "2009-05", "2009-05-13", with or without time and offset),
 * which no single java.time type covers. Conversions are offered for
 * the common cases.
 */
@JvmInline
value class DateTime(val value: String) {
    init {
        require(PATTERN.matches(value)) { "Not a valid bmecat:dtDATETIME: '$value'" }
    }

    fun toOffsetDateTimeOrNull(): OffsetDateTime? = runCatching { OffsetDateTime.parse(value) }.getOrNull()
    fun toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()

    override fun toString() = value

    companion object {
        // Copied verbatim from bmecat_2005.xsd (simpleType dtDATETIME).
        // XSD patterns are implicitly anchored; Regex.matches checks the whole string too.
        private val PATTERN = Regex(
            """\d{4}(\-(0[1-9]|1[0-2])(\-(0[1-9]|1[0-9]|2[0-9]|3[0-1])(T(0[0-9]|1[0-9]|2[0-3])(:[0-5][0-9])""" +
                """(:[0-5][0-9](\.[0-9]{1,}){0,1}){0,1}(([+\-]([0-1][0-9]|2[0-3])(:[0-5][0-9]))|Z){0,1}){0,1}){0,1}){0,1}""",
        )

        private val WITH_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        fun of(dateTime: OffsetDateTime) = DateTime(dateTime.format(WITH_SECONDS))
        fun of(date: LocalDate) = DateTime(date.toString())
        fun of(yearMonth: YearMonth) = DateTime(yearMonth.toString())
    }
}

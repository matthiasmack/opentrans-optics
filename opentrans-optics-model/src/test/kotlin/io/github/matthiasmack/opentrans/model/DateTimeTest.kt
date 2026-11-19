package io.github.matthiasmack.opentrans.model

import java.time.OffsetDateTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DateTimeTest {

    @Test
    fun `accepts every form the XSD allows and rejects the rest`() {
        listOf("2026", "2026-11", "2026-11-17", "2026-11-17T09:30", "2026-11-17T09:30:00", "2026-11-17T09:30:00.123Z", "2026-11-17T09:30:00+01:00")
            .forEach { DateTime(it) }
        listOf("asdsdertfd", "", "26-11-17", "2026-13", "2026-11-17T24:00", "2026-11-17 09:30", "2026-11-17T09:30:00+1")
            .forEach { assertFailsWith<FacetViolationException>(it) { DateTime(it) } }
    }

    @Test
    fun `keeps partial dates and converts full ones`() {
        assertEquals(2026, DateTime("2026-11-17T09:30:00+01:00").toOffsetDateTimeOrNull()?.year)
        assertNull(DateTime("2028-05").toOffsetDateTimeOrNull())
        assertEquals(17, DateTime("2026-11-17T09:30").toLocalDateOrNull()?.dayOfMonth)
    }

    @Test
    fun `factories produce valid values`() {
        assertEquals("2026-11-17T09:30:00+01:00", DateTime.of(OffsetDateTime.parse("2026-11-17T09:30+01:00")).value)
        assertEquals("2026-11-17T09:30:00Z", DateTime.of(OffsetDateTime.parse("2026-11-17T09:30Z")).value)
        assertEquals("2028-05", DateTime.of(YearMonth.of(2028, 5)).value)
    }
}

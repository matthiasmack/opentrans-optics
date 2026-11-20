package io.github.matthiasmack.opentrans.xml

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

/** Every sample survives read -> write -> validate -> read, and writing is stable. */
class DocumentRoundTripTest {
    @ParameterizedTest
    @ValueSource(strings = ["order.xml", "invoice.xml", "dispatchnotification.xml", "invoicelist.xml", "order-spike.xml"])
    fun `sample survives a round trip`(sample: String) {
        val original = OpenTransXml.read(javaClass.getResource("/samples/$sample")!!.readText())

        val written = OpenTransXml.write(original) // validates the written XML against the XSD
        val reread = OpenTransXml.read(written)

        assertEquals(original, reread)
        assertEquals(written, OpenTransXml.write(reread), "writing must be stable")
    }
}

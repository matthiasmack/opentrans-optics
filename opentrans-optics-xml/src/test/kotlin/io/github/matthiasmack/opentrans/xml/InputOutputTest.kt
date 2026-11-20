package io.github.matthiasmack.opentrans.xml

import io.github.matthiasmack.opentrans.model.DocumentTypeException
import io.github.matthiasmack.opentrans.model.OpenTransValidationException
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.spikeOrder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Files, streams, encodings and concurrent use of [OpenTransXml]. */
class InputOutputTest {
    private fun sample(name: String) = javaClass.getResource("/samples/$name")!!.readText()

    /** The spike order with umlauts in a remark, declared with [encoding]. */
    private fun withUmlauts(encoding: String?): String {
        val xml = sample("order-spike.xml").replace("Bitte an Tor 2 liefern.", "Größe prüfen, Tür 2")
        val declaration = """<?xml version="1.0" encoding="UTF-8"?>"""
        check(xml.startsWith(declaration))
        return if (encoding ==
            null
        ) {
            xml.removePrefix(declaration).trimStart()
        } else {
            xml.replace(declaration, """<?xml version="1.0" encoding="$encoding"?>""")
        }
    }

    private val expected: Order by lazy { OpenTransXml.readAs(withUmlauts("UTF-8")) }

    private fun remark(order: Order) =
        order.orderHeader.orderInfo.remarks
            .first()
            .value

    // --- encodings ---

    @Test
    fun `bytes are decoded with the encoding the XML declares`() {
        for (encoding in listOf("ISO-8859-1", "windows-1252", "UTF-8")) {
            val bytes = withUmlauts(encoding).toByteArray(Charset.forName(encoding))
            val order = OpenTransXml.readAs<Order>(bytes)
            assertEquals("Größe prüfen, Tür 2", remark(order), encoding)
            assertEquals(expected, order, encoding)
        }
    }

    @Test
    fun `UTF-16 and byte order marks are recognised`() {
        assertEquals(expected, OpenTransXml.readAs(withUmlauts("UTF-16").toByteArray(Charsets.UTF_16))) // with BOM
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + withUmlauts("UTF-8").toByteArray()
        assertEquals(expected, OpenTransXml.readAs(utf8Bom))
    }

    @Test
    fun `bytes without an XML declaration are UTF-8`() {
        assertEquals(expected, OpenTransXml.readAs(withUmlauts(null).toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `the declaration is followed even when the bytes say otherwise`() {
        // Like the official invoice and dispatch notification samples: UTF-8 bytes declared as ISO-8859-1.
        // Every conforming XML parser reads them this way; the library does not guess.
        val mislabelled = withUmlauts("ISO-8859-1").toByteArray(Charsets.UTF_8)
        val garbled = String("Größe prüfen, Tür 2".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1) // "GrÃ¶Ã\u009Fe ..."
        assertEquals(garbled, remark(OpenTransXml.readAs(mislabelled)))
    }

    @Test
    fun `characters are taken as they are, whatever the declaration says`() {
        assertEquals(expected, OpenTransXml.readAs(withUmlauts("ISO-8859-1")))
        assertEquals(expected, OpenTransXml.readAs(StringReader(withUmlauts("ISO-8859-1"))))
    }

    @Test
    fun `unknown encodings and broken bytes are refused`() {
        val unknown = assertFailsWith<OpenTransValidationException> { OpenTransXml.read(withUmlauts("x-no-such-charset").toByteArray()) }
        assertContains(unknown.message!!, "x-no-such-charset")
        assertFailsWith<OpenTransValidationException> { OpenTransXml.read("not xml".toByteArray()) }
    }

    // --- files and streams ---

    @Test
    fun `files are read as bytes and written as UTF-8`() {
        val file = Files.createTempFile("order", ".xml")
        Files.write(file, withUmlauts("ISO-8859-1").toByteArray(Charsets.ISO_8859_1))
        assertEquals(expected, OpenTransXml.readAs(file))

        OpenTransXml.write(expected, file)
        assertEquals(OpenTransXml.write(expected), Files.readString(file, Charsets.UTF_8))
        assertEquals(expected, OpenTransXml.readAs(file))
    }

    @Test
    fun `streams are read and written without being closed`() {
        var closed = false
        val input =
            object : FilterInputStream(ByteArrayInputStream(withUmlauts("ISO-8859-1").toByteArray(Charsets.ISO_8859_1))) {
                override fun close() {
                    closed = true
                }
            }
        assertEquals(expected, OpenTransXml.readAs(input))
        assertFalse(closed)

        val bytes = ByteArrayOutputStream()
        OpenTransXml.write(expected, bytes)
        assertEquals(OpenTransXml.write(expected), bytes.toString(Charsets.UTF_8))

        val chars = StringWriter()
        OpenTransXml.write(expected, chars)
        assertEquals(OpenTransXml.write(expected), chars.toString())
    }

    @Test
    fun `readAs works for every input type`() {
        val bytes = OpenTransXml.write(spikeOrder).toByteArray()
        assertEquals(spikeOrder, OpenTransXml.readAs<Order>(ByteArrayInputStream(bytes)))
        assertFailsWith<DocumentTypeException> { OpenTransXml.readAs<io.github.matthiasmack.opentrans.model.Invoice>(bytes) }
    }

    // --- concurrency ---

    @Test
    fun `OpenTransXml can be used from many threads at once`() {
        val samples = listOf("order.xml", "invoice.xml", "dispatchnotification.xml", "invoicelist.xml", "order-spike.xml").map(::sample)
        val sequential = samples.map { OpenTransXml.write(OpenTransXml.read(it)) }

        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks =
                (1..200).map { i ->
                    Callable { i % samples.size to OpenTransXml.write(OpenTransXml.read(samples[i % samples.size])) }
                }
            for (result in pool.invokeAll(tasks)) {
                val (index, written) = result.get()
                assertEquals(sequential[index], written)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}

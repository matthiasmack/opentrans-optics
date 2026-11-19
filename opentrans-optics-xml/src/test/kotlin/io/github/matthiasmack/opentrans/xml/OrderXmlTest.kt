package io.github.matthiasmack.opentrans.xml

import io.github.matthiasmack.opentrans.model.OpenTransValidationException
import io.github.matthiasmack.opentrans.model.DocumentTypeException
import arrow.optics.dsl.every
import arrow.optics.dsl.notNull
import io.github.matthiasmack.opentrans.model.FacetViolationException
import io.github.matthiasmack.opentrans.model.Invoice
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.XmlContent
import io.github.matthiasmack.opentrans.model.XmlElement
import io.github.matthiasmack.opentrans.model.XmlText
import io.github.matthiasmack.opentrans.udx.Level
import io.github.matthiasmack.opentrans.udx.Priority
import io.github.matthiasmack.opentrans.udx.Project
import io.github.matthiasmack.opentrans.udx.ProjectUdx
import io.github.matthiasmack.opentrans.udx.get
import io.github.matthiasmack.opentrans.udx.udx
import io.github.matthiasmack.opentrans.udx.with
import io.github.matthiasmack.opentrans.model.headerUdx
import io.github.matthiasmack.opentrans.model.orderHeader
import io.github.matthiasmack.opentrans.model.orderId
import io.github.matthiasmack.opentrans.model.orderInfo
import io.github.matthiasmack.opentrans.model.orderItems
import io.github.matthiasmack.opentrans.model.priceAmount
import io.github.matthiasmack.opentrans.model.productPriceFix
import io.github.matthiasmack.opentrans.model.spikeOrder
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OrderXmlTest {

    private fun sample(name: String) = javaClass.getResource("/samples/$name")!!.readText()

    // --- reading and writing ---

    @Test
    fun `reading the spike sample yields the expected order`() {
        assertEquals(spikeOrder, OpenTransXml.readAs<Order>(sample("order-spike.xml")))
    }

    @Test
    fun `writing and reading back yields the same order`() {
        assertEquals(spikeOrder, OpenTransXml.read(OpenTransXml.write(spikeOrder)))
    }

    @Test
    fun `read, edit with optics, write, read again`() {
        val prices = Order.orderItems.every.productPriceFix.notNull.priceAmount
        val order = OpenTransXml.readAs<Order>(sample("order-spike.xml"))

        val edited = prices.modify(Order.orderHeader.orderInfo.orderId.set(order, "PO-1001-B")) { it * BigDecimal(2) }
        val reread = OpenTransXml.readAs<Order>(OpenTransXml.write(edited))

        assertEquals("PO-1001-B", reread.orderHeader.orderInfo.orderId)
        assertEquals(listOf(BigDecimal("0.30"), BigDecimal("24.80")), prices.getAll(reread))
    }

    @Test
    fun `a document wrapped in OPENTRANS is read as well`() {
        val wrapped = sample("order-spike.xml")
            .replaceFirst("<ORDER ", "<OPENTRANS xmlns=\"http://www.opentrans.org/XMLSchema/2.1\"><ORDER ")
            .replace("</ORDER>", "</ORDER></OPENTRANS>")
        assertEquals(spikeOrder, OpenTransXml.read(wrapped))
    }

    @Test
    fun `readAs refuses other document types`() {
        assertIs<Invoice>(OpenTransXml.read(sample("invoice.xml")))
        val error = assertFailsWith<DocumentTypeException> { OpenTransXml.readAs<Order>(sample("invoice.xml")) }
        assertEquals("Expected Order, found Invoice", error.message)
    }

    // --- UDX ---

    private fun withHeaderUdx(content: String) = sample("order-spike.xml").replace(
        Regex("<HEADER_UDX>.*?</HEADER_UDX>", RegexOption.DOT_MATCHES_ALL),
        "<HEADER_UDX>$content</HEADER_UDX>",
    )

    @Test
    fun `UDX content keeps namespaces declared further up`() {
        val udx = OpenTransXml.readAs<Order>(withHeaderUdx("<bmecat:NOTE>text &amp; more</bmecat:NOTE>")).orderHeader.orderInfo.headerUdx!!
        assertEquals(XmlContent(XmlElement("NOTE", BMECAT, "bmecat", children = listOf(XmlText("text & more")))), udx)
        // On its own the content declares the namespace it needs.
        assertEquals("""<bmecat:NOTE xmlns:bmecat="http://www.bmecat.org/bmecat/2005">text &amp; more</bmecat:NOTE>""", udx.toXml())
    }

    @Test
    fun `UDX may contain plain text`() {
        assertEquals(XmlContent.text("just text"), OpenTransXml.readAs<Order>(withHeaderUdx("  just text  ")).orderHeader.orderInfo.headerUdx)
    }

    // --- strictness ---

    @Test
    fun `invalid input is rejected with the XSD error`() {
        val xml = sample("order-spike.xml").replace("<ORDER_DATE>2026-11-17</ORDER_DATE>", "<ORDER_DATE>asdsdertfd</ORDER_DATE>")
        val error = assertFailsWith<OpenTransValidationException> { OpenTransXml.read(xml) }
        assertContains(error.message!!, "asdsdertfd")
    }

    @Test
    fun `invalid values fail where they are created, not when written`() {
        val error = assertFailsWith<FacetViolationException> { Order.orderHeader.orderInfo.orderId.set(spikeOrder, "x".repeat(251)) }
        assertEquals("ORDER_INFO/ORDER_ID: at most 250 characters, got 251", error.message)
    }

    @Test
    fun `XSD validation on write still catches what the facet checks cannot`() {
        // UDX content is checked laxly: elements the schema declares must be valid, here an openTRANS ORDER_DATE.
        val udx = XmlContent(XmlElement("ORDER_DATE", OT, children = listOf(XmlText("not a date"))))
        val broken = Order.orderHeader.orderInfo.headerUdx.set(spikeOrder, udx)
        val error = assertFailsWith<OpenTransValidationException> { OpenTransXml.write(broken) }
        assertContains(error.message!!, "not a date")
    }

    @Test
    fun `UDX elements without a namespace are written outside the openTRANS default namespace`() {
        val udx = XmlContent(XmlElement("UDX.PLAIN", children = listOf(XmlText("x"))))
        val order = Order.orderHeader.orderInfo.headerUdx.set(spikeOrder, udx)
        val xml = OpenTransXml.write(order)
        assertContains(xml, """<HEADER_UDX><UDX.PLAIN xmlns="">x</UDX.PLAIN></HEADER_UDX>""")
        assertEquals(udx, OpenTransXml.readAs<Order>(xml).orderHeader.orderInfo.headerUdx)
    }

    @Test
    fun `typed UDX survive writing and reading next to foreign UDX`() {
        val foreign = XmlElement("UDX.PARTNER", "urn:partner", "p", children = listOf(XmlText("untouched")))
        val udx = XmlContent(foreign).with(ProjectUdx, Project("project-9", Priority(Level.LOW)))
        val order = Order.orderHeader.orderInfo.headerUdx.set(spikeOrder, udx)

        val back = OpenTransXml.readAs<Order>(OpenTransXml.write(order))

        assertEquals(order, back)
        assertEquals(Project("project-9", Priority(Level.LOW)), back.orderHeader.orderInfo.headerUdx!![ProjectUdx])
        assertEquals(foreign, back.orderHeader.orderInfo.headerUdx!!.elements.first())
    }

    @Test
    fun `changing a typed UDX with an optic leaves the extensions of other parties untouched`() {
        // One HEADER_UDX block with extensions of three parties; only ProjectUdx is described here.
        val partner = """<p:UDX.PARTNER xmlns:p="urn:partner" p:ref="42" note="a &amp; b">""" +
            """<p:LINE no="1" xml:lang="de">first <p:B>bold</p:B> rest</p:LINE><p:LINE no="2"/></p:UDX.PARTNER>"""
        val own = """<UDX.CUSTOM xmlns="urn:example:udx"><PROJECT>project-7</PROJECT><PRIORITY level="high"/></UDX.CUSTOM>"""
        val other = """<UDX.OTHER xmlns="urn:other"><bmecat:NOTE>uses a prefix from the root</bmecat:NOTE></UDX.OTHER>"""
        val order = OpenTransXml.readAs<Order>(withHeaderUdx(partner + own + other))
        fun foreign(order: Order) = order.orderHeader.orderInfo.headerUdx!!.elements.filter { it.name != ProjectUdx.name }

        val project = Order.orderHeader.orderInfo.headerUdx.udx(ProjectUdx)
        val changed = project.modify(order) { it.copy(project = "project-8", priority = null) }
        val back = OpenTransXml.readAs<Order>(OpenTransXml.write(changed))

        assertEquals(Project("project-8", null), project.getOrNull(back))
        assertEquals(foreign(order), foreign(back))
        // Expected value parsed on its own, so it declares the bmecat prefix itself; equality ignores declarations.
        val otherStandalone = other.replace("""xmlns="urn:other">""", """xmlns="urn:other" xmlns:bmecat="$BMECAT">""")
        assertEquals(XmlContent.parse(partner + otherStandalone).elements, foreign(back))
        assertEquals(
            listOf("UDX.PARTNER", "UDX.CUSTOM", "UDX.OTHER"),
            back.orderHeader.orderInfo.headerUdx!!.elements.map { it.name.localPart },
        )
    }

    @Test
    fun `malformed UDX text is refused where it is parsed`() {
        assertFailsWith<OpenTransValidationException> { XmlContent.parse("<open>") }
        assertFailsWith<OpenTransValidationException> { XmlContent.parse("<a/></content><content>") }
        assertFailsWith<OpenTransValidationException> { XmlContent.parse("<p:a/>") }
    }

    // --- hardening ---

    @Test
    fun `external entities are not resolved`() {
        val secret = kotlin.io.path.createTempFile().apply { toFile().writeText("SECRET") }
        val xml = sample("order-spike.xml")
            .replaceFirst("<ORDER ", "<!DOCTYPE ORDER [<!ENTITY xxe SYSTEM \"${secret.toUri()}\">]>\n<ORDER ")
            .replace("<ORDER_ID>PO-1001</ORDER_ID>", "<ORDER_ID>&xxe;</ORDER_ID>")

        val error = assertFailsWith<OpenTransValidationException> { OpenTransXml.read(xml) }
        assertContains(error.message!!, "DOCTYPE")
    }

    @Test
    fun `entity expansion bombs are refused`() {
        val xml = sample("order-spike.xml").replaceFirst(
            "<ORDER ",
            "<!DOCTYPE ORDER [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\">]>\n<ORDER ",
        ).replace("<ORDER_ID>PO-1001</ORDER_ID>", "<ORDER_ID>&b;</ORDER_ID>")

        assertFailsWith<OpenTransValidationException> { OpenTransXml.read(xml) }
    }
}

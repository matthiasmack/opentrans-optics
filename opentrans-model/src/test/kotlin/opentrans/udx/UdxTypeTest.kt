package opentrans.udx

import arrow.optics.dsl.every
import opentrans.model.DateTime
import opentrans.model.Order
import opentrans.model.XmlContent
import opentrans.model.XmlElement
import opentrans.model.XmlText
import opentrans.model.headerUdx
import opentrans.model.itemUdx
import opentrans.model.orderHeader
import opentrans.model.orderInfo
import opentrans.model.orderItems
import opentrans.model.spikeOrder
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UdxTypeTest {

    private val shipping = Shipping(
        carrier = "ACME",
        weight = BigDecimal("12.50"),
        parcels = 2,
        fragile = true,
        pickup = LocalDate.of(2026, 11, 20),
        booked = DateTime("2026-11-17T10:00:00+01:00"),
        references = listOf("R-1", "R-2"),
        labels = listOf(Priority(Level.HIGH), Priority(Level.LOW)),
        version = 2,
    )

    private val shippingXml = """<UDX.ACME.SHIPPING version="2"><CARRIER>ACME</CARRIER><WEIGHT>12.50</WEIGHT>""" +
        """<PARCELS>2</PARCELS><FRAGILE>true</FRAGILE><PICKUP>2026-11-20</PICKUP><BOOKED>2026-11-17T10:00:00+01:00</BOOKED>""" +
        """<REFERENCE>R-1</REFERENCE><REFERENCE>R-2</REFERENCE><LABEL level="high"/><LABEL level="low"/></UDX.ACME.SHIPPING>"""

    private fun shippingElement(xml: String) = XmlContent.parse(xml).elements.single()

    // --- reading and writing ---

    @Test
    fun `reads the UDX of the spike order`() {
        assertEquals(Project("project-7", Priority(Level.HIGH)), spikeOrder.orderHeader.orderInfo.headerUdx!![ProjectUdx])
        assertEquals(CostCenter("cc-42"), spikeOrder.orderItems.first().itemUdx!![CostCenterUdx])
    }

    @Test
    fun `writes every kind of field in declaration order`() {
        assertEquals(shippingXml, ShippingUdx.write(shipping).toString())
        assertEquals(shipping, ShippingUdx.read(shippingElement(shippingXml)))
    }

    @Test
    fun `missing optional values and empty lists are left out`() {
        val minimal = shipping.copy(pickup = null, booked = null, references = emptyList(), labels = emptyList(), version = null)
        val xml = "<UDX.ACME.SHIPPING><CARRIER>ACME</CARRIER><WEIGHT>12.50</WEIGHT><PARCELS>2</PARCELS><FRAGILE>true</FRAGILE></UDX.ACME.SHIPPING>"
        assertEquals(xml, ShippingUdx.write(minimal).toString())
        assertEquals(minimal, ShippingUdx.read(shippingElement(xml)))
    }

    @Test
    fun `child elements may come in any order and booleans in any case`() {
        val xml = "<UDX.ACME.SHIPPING><FRAGILE>TRUE</FRAGILE><PARCELS>2</PARCELS><WEIGHT>12.50</WEIGHT><CARRIER>ACME</CARRIER></UDX.ACME.SHIPPING>"
        assertEquals(true, ShippingUdx.read(shippingElement(xml)).fragile)
    }

    @Test
    fun `children are written in the namespace of their parent`() {
        val element = ProjectUdx.write(Project("p", Priority(Level.LOW)))
        assertEquals("""<UDX.CUSTOM xmlns="urn:example:udx"><PROJECT>p</PROJECT><PRIORITY level="low"/></UDX.CUSTOM>""", element.toString())
        assertEquals(EXAMPLE_NS, element.child("PRIORITY")!!.name.namespaceURI)
    }

    // --- rules ---

    private fun violation(xml: String) = assertFailsWith<UdxViolationException> { ShippingUdx.read(shippingElement(xml)) }.message

    private fun withShipping(replace: String, by: String) = shippingXml.replace(replace, by).also { check(it != shippingXml) }

    @Test
    fun `reading names the path of every violation`() {
        assertEquals("UDX.ACME.SHIPPING/CARRIER is missing", violation(withShipping("<CARRIER>ACME</CARRIER>", "")))
        assertEquals("UDX.ACME.SHIPPING/CARRIER: at most 10 characters, got 11", violation(withShipping(">ACME<", ">ACMEACMEACM<")))
        assertEquals("UDX.ACME.SHIPPING/CARRIER: only once, got 2", violation(withShipping("<CARRIER>ACME</CARRIER>", "<CARRIER>A</CARRIER><CARRIER>B</CARRIER>")))
        assertEquals("UDX.ACME.SHIPPING/WEIGHT: 'heavy' is not a valid decimal", violation(withShipping("12.50", "heavy")))
        assertEquals("UDX.ACME.SHIPPING/WEIGHT: at least 0, got -1", violation(withShipping("12.50", "-1")))
        assertEquals("UDX.ACME.SHIPPING/PARCELS: at most 99, got 100", violation(withShipping("<PARCELS>2", "<PARCELS>100")))
        assertEquals("UDX.ACME.SHIPPING/FRAGILE: 'maybe' is not a valid boolean", violation(withShipping(">true<", ">maybe<")))
        assertEquals("UDX.ACME.SHIPPING/@version: at least 1, got 0", violation(withShipping("version=\"2\"", "version=\"0\"")))
        assertEquals("UDX.ACME.SHIPPING/REFERENCE: at most 3 times, got 4", violation(withShipping("<REFERENCE>R-1</REFERENCE>", "<REFERENCE>1</REFERENCE>".repeat(3))))
        assertEquals("UDX.ACME.SHIPPING/LABEL/@level: 'medium' is not a valid one of [high, low]", violation(withShipping("level=\"low\"", "level=\"medium\"")))
        assertEquals("UDX.ACME.SHIPPING/LABEL/@level is missing", violation(withShipping("<LABEL level=\"low\"/>", "<LABEL/>")))
    }

    @Test
    fun `reading refuses what the type does not describe`() {
        assertEquals("UDX.ACME.SHIPPING: unknown element COLOR", violation(withShipping("</UDX.ACME.SHIPPING>", "<COLOR>red</COLOR></UDX.ACME.SHIPPING>")))
        assertEquals("UDX.ACME.SHIPPING: unknown attribute id", violation(withShipping("version=\"2\"", "version=\"2\" id=\"x\"")))
        assertEquals("UDX.ACME.SHIPPING: text is not allowed here, got 'oops'", violation(withShipping("<CARRIER>", "oops<CARRIER>")))
        assertEquals("UDX.ACME.SHIPPING/CARRIER: only text is allowed here", violation(withShipping(">ACME<", "><B>ACME</B><")))
        assertEquals(
            "UDX.OTHER: expected UDX.ACME.SHIPPING in namespace ''",
            violation("<UDX.OTHER/>"),
        )
        // Same local name, other namespace: not this type.
        assertFailsWith<UdxViolationException> { ProjectUdx.read(shippingElement("<UDX.CUSTOM><PROJECT>p</PROJECT></UDX.CUSTOM>")) }
    }

    @Test
    fun `writing checks the same rules`() {
        val error = assertFailsWith<UdxViolationException> { ShippingUdx.write(shipping.copy(carrier = "far too long")) }
        assertEquals("UDX.ACME.SHIPPING/CARRIER: at most 10 characters, got 12", error.message)
        val tooMany = assertFailsWith<UdxViolationException> { ShippingUdx.write(shipping.copy(references = listOf("1", "2", "3", "4"))) }
        assertEquals("UDX.ACME.SHIPPING/REFERENCE: at most 3 times, got 4", tooMany.message)
        assertFailsWith<UdxViolationException> { CostCenterUdx.write(CostCenter("42")) }
    }

    // --- content access ---

    private val other = XmlElement("UDX.OTHER", "urn:partner", children = listOf(XmlText("kept")))

    @Test
    fun `with replaces the element in place and keeps everything else`() {
        val content = XmlContent(other, ProjectUdx.write(Project("old", null)), XmlText("tail"))
        val changed = content.with(ProjectUdx, Project("new", null))
        assertEquals(XmlContent(other, ProjectUdx.write(Project("new", null)), XmlText("tail")), changed)
        assertEquals(XmlContent(other, XmlText("tail")), content.with(ProjectUdx, null))
    }

    @Test
    fun `with appends when the element is not there yet`() {
        assertEquals(XmlContent(other, ProjectUdx.write(Project("p", null))), XmlContent(other).with(ProjectUdx, Project("p", null)))
    }

    @Test
    fun `get insists on a single element, getAll returns all`() {
        val twice = XmlContent(other).withAll(ProjectUdx, listOf(Project("a", null), Project("b", null)))
        assertFailsWith<IllegalStateException> { twice[ProjectUdx] }
        assertEquals(listOf("a", "b"), twice.getAll(ProjectUdx).map { it.project })
        assertNull(XmlContent(other)[ProjectUdx])
    }

    // --- optics ---

    private val project = Order.orderHeader.orderInfo.headerUdx.udx(ProjectUdx)

    @Test
    fun `optic reads and modifies a typed UDX inside a document`() {
        assertEquals(Project("project-7", Priority(Level.HIGH)), project.getOrNull(spikeOrder))
        val changed = project.modify(spikeOrder) { it.copy(priority = null) }
        assertEquals(
            """<UDX.CUSTOM xmlns="urn:example:udx"><PROJECT>project-7</PROJECT></UDX.CUSTOM>""",
            changed.orderHeader.orderInfo.headerUdx!!.toXml(),
        )
    }

    @Test
    fun `optic set creates the UDX content when there is none`() {
        val without = Order.orderHeader.orderInfo.headerUdx.set(spikeOrder, null)
        assertNull(project.getOrNull(without))
        assertEquals(without, project.modify(without) { it.copy(project = "unused") })
        assertEquals(Project("p", null), project.getOrNull(project.set(without, Project("p", null))))
    }

    @Test
    fun `traversal reaches the typed UDX of every item that has one`() {
        val costCenters = Order.orderItems.every.itemUdx.udx(CostCenterUdx)
        assertEquals(listOf(CostCenter("cc-42")), costCenters.getAll(spikeOrder))
        val changed = costCenters.modify(spikeOrder) { CostCenter("cc-7") }
        assertEquals(listOf(CostCenter("cc-7")), costCenters.getAll(changed))
        assertNull(changed.orderItems[1].itemUdx)
    }
}

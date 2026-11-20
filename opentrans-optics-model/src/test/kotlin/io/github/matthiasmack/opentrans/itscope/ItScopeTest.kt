package io.github.matthiasmack.opentrans.itscope

import io.github.matthiasmack.opentrans.model.FacetViolationException
import io.github.matthiasmack.opentrans.model.IncompleteDocumentException
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.PartyRole
import io.github.matthiasmack.opentrans.model.XmlContent
import io.github.matthiasmack.opentrans.model.spikeOrder
import io.github.matthiasmack.opentrans.udx.SimpleTypes
import io.github.matthiasmack.opentrans.udx.UdxType
import io.github.matthiasmack.opentrans.udx.UdxViolationException
import io.github.matthiasmack.opentrans.udx.get
import io.github.matthiasmack.opentrans.udx.with
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItScopeTest {

    // --- UDX with just a value ---

    @Test
    fun `a UDX element can hold just a value`() {
        val content = XmlContent.parse("<UDX.DROPSHIPMENT>TRUE</UDX.DROPSHIPMENT><UDX.OTHER>kept</UDX.OTHER>")
        assertEquals(true, content[ItScopeUdx.DROPSHIPMENT])
        assertEquals("<UDX.DROPSHIPMENT>false</UDX.DROPSHIPMENT><UDX.OTHER>kept</UDX.OTHER>", content.with(ItScopeUdx.DROPSHIPMENT, false).toXml())
    }

    @Test
    fun `value UDX are checked like every other UDX`() {
        val maybe = XmlContent.parse("<UDX.DROPSHIPMENT>maybe</UDX.DROPSHIPMENT>")
        assertEquals("UDX.DROPSHIPMENT: 'maybe' is not a valid boolean", assertFailsWith<UdxViolationException> { maybe[ItScopeUdx.DROPSHIPMENT] }.message)
        val empty = XmlContent.parse("<UDX.DROPSHIPMENT/>")
        assertEquals("UDX.DROPSHIPMENT: a value is missing", assertFailsWith<UdxViolationException> { empty[ItScopeUdx.DROPSHIPMENT] }.message)
        val nested = XmlContent.parse("<UDX.DROPSHIPMENT><X/></UDX.DROPSHIPMENT>")
        assertEquals("UDX.DROPSHIPMENT: unknown element X", assertFailsWith<UdxViolationException> { nested[ItScopeUdx.DROPSHIPMENT] }.message)
        val length = UdxType.value("UDX.CODE", SimpleTypes.string(maxLength = 3))
        assertFailsWith<UdxViolationException> { length.write("toolong") }
    }

    data class Weight(val value: java.math.BigDecimal, val unit: String)

    object WeightUdx : UdxType<Weight>("UDX.WEIGHT") {
        private val value = text(Weight::value, decimal())
        private val unit = attribute("unit", Weight::unit, string())
        override fun create(v: Values) = Weight(v[value], v[unit])
    }

    @Test
    fun `text and attributes can be combined, text and child elements cannot`() {
        val weight = Weight(java.math.BigDecimal("2.5"), "KGM")
        assertEquals("""<UDX.WEIGHT unit="KGM">2.5</UDX.WEIGHT>""", WeightUdx.write(weight).toString())
        assertEquals(weight, WeightUdx.read(WeightUdx.write(weight)))

        val error = assertFailsWith<IllegalArgumentException> {
            object : UdxType<String>("UDX.BROKEN") {
                private val a = text({ it }, string())
                private val b = required("B", { it }, string())
                override fun create(v: Values) = v[a]
            }
        }
        assertEquals("UDX.BROKEN: text and child elements cannot be combined", error.message)
    }

    @Test
    fun `typed access to ITscope's UDX`() {
        assertNull(spikeOrder.dropshipment)
        val udx = (spikeOrder.orderHeader.orderInfo.headerUdx ?: XmlContent.EMPTY).with(ItScopeUdx.DROPSHIPMENT, true)
        val order = spikeOrder.copy(orderHeader = spikeOrder.orderHeader.copy(orderInfo = spikeOrder.orderHeader.orderInfo.copy(headerUdx = udx)))
        assertEquals(true, order.dropshipment)
        val item = order.orderItems.first().copy(itemUdx = XmlContent.parse("<UDX.BUNDLEPARTSLISTVISIBLE>false</UDX.BUNDLEPARTSLISTVISIBLE>"))
        assertEquals(false, item.bundlePartsListVisible)
    }

    // --- rules ---

    private fun withOrderId(order: Order, id: String) =
        order.copy(orderHeader = order.orderHeader.copy(orderInfo = order.orderHeader.orderInfo.copy(orderId = id)))

    @Test
    fun `ORDER_ID is limited to 18 characters`() {
        val violation = ItScopeRules.violations(withOrderId(spikeOrder, "PO-12345678901234567")).single()
        assertIs<FacetViolationException>(violation)
        assertEquals("ORDER_INFO/ORDER_ID: at most 18 characters for ITscope, got 20", violation.message)
        assertEquals(emptyList(), ItScopeRules.violations(spikeOrder))
    }

    @Test
    fun `buyer and delivery addresses need name, street, ZIP code and country`() {
        val info = spikeOrder.orderHeader.orderInfo
        val noStreet = info.parties.map { party ->
            if (PartyRole.BUYER in party.partyRoles) party.copy(addresses = party.addresses.map { it.copy(streets = emptyList()) }) else party
        }
        val order = spikeOrder.copy(orderHeader = spikeOrder.orderHeader.copy(orderInfo = info.copy(parties = noStreet)))
        val violation = ItScopeRules.violations(order).single()
        assertIs<IncompleteDocumentException>(violation)
        assertEquals("PARTY/ADDRESS/STREET", violation.element)
        assertEquals("ITscope needs STREET in the address of the buyer party", violation.message)
    }

    @Test
    fun `every item needs a supplier article number`() {
        val items = spikeOrder.orderItems.mapIndexed { i, item -> if (i == 1) item.copy(productId = item.productId.copy(supplierPid = null)) else item }
        val violation = ItScopeRules.violations(spikeOrder.copy(orderItems = items)).single()
        assertEquals("ITscope needs a SUPPLIER_PID for item 2", violation.message)
    }

    @Test
    fun `violations are collected in document order`() {
        val items = spikeOrder.orderItems.map { it.copy(productId = it.productId.copy(supplierPid = null)) }
        val all = ItScopeRules.violations(withOrderId(spikeOrder, "X".repeat(19)).copy(orderItems = items))
        assertEquals(3, all.size)
        assertIs<FacetViolationException>(all.first())
        assertTrue(all.drop(1).all { it is IncompleteDocumentException })
    }
}

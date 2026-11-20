package io.github.matthiasmack.opentrans.xml

import io.github.matthiasmack.opentrans.model.Card
import io.github.matthiasmack.opentrans.model.DispatchNotification
import io.github.matthiasmack.opentrans.model.Invoice
import io.github.matthiasmack.opentrans.model.InvoiceList
import io.github.matthiasmack.opentrans.model.OpenTransDocument
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.OrderType
import io.github.matthiasmack.opentrans.model.PartyRole
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The reader generated from the XSD reads every official sample completely. */
class DocumentReadTest {
    private fun read(name: String): OpenTransDocument = OpenTransXml.read(javaClass.getResource("/samples/$name")!!.readText())

    @Test
    fun `official ORDER sample is read completely`() {
        val order = assertIs<Order>(read("order.xml"))
        val info = order.orderHeader.orderInfo

        assertEquals(OrderType.STANDARD, order.type)
        assertEquals("OID1", info.orderId)
        assertEquals("ReferencetoQuotationNr", order.orderHeader.sourcingInfo?.quotationId)
        assertEquals(listOf(PartyRole.BUYER), info.parties.single().partyRoles)
        assertEquals("VISA", assertIs<Card>(info.payment?.method).type)
        assertEquals(
            2,
            info.parties
                .single()
                .addresses
                .single()
                .contactDetails
                .single()
                .contactNames.size,
        )
        assertContains(info.headerUdx!!.text, "user defined extension on header-level")
        assertEquals(order.orderSummary.totalItemNum, order.orderItems.size)
    }

    @Test
    fun `official INVOICE sample is read`() {
        val invoice = assertIs<Invoice>(read("invoice.xml"))
        assert(invoice.invoiceItems.isNotEmpty())
    }

    @Test
    fun `official DISPATCHNOTIFICATION sample is read`() {
        val dispatch = assertIs<DispatchNotification>(read("dispatchnotification.xml"))
        assert(dispatch.dispatchNotificationItems.isNotEmpty())
    }

    @Test
    fun `official INVOICELIST sample is read`() {
        assertIs<InvoiceList>(read("invoicelist.xml"))
    }

    @Test
    fun `spike sample is read by the generated reader too`() {
        val order = assertIs<Order>(read("order-spike.xml"))
        assertEquals(listOf(BigDecimal("0.15"), BigDecimal("12.40")), order.orderItems.map { it.productPriceFix?.priceAmount })
    }
}

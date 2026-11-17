package opentrans.dsl

import opentrans.model.DispatchNotification
import opentrans.model.Invoice
import opentrans.model.OrderResponse
import opentrans.model.spikeOrder
import opentrans.xml.OpenTransXml
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Follow-up documents derived from an ORDER are valid openTRANS and carry the order's data. */
class DeriveTest {

    private inline fun <reified T : opentrans.model.OpenTransDocument> roundTrip(document: T): T =
        OpenTransXml.readAs<T>(OpenTransXml.write(document)).also { assertEquals(document, it) }

    @Test
    fun `order response confirms the order`() {
        val response = roundTrip(spikeOrder.toOrderResponse(date = LocalDate.of(2026, 11, 18)))
        val info = response.orderResponseHeader.orderResponseInfo

        assertEquals("PO-1001", info.orderId)
        assertEquals("2026-11-18", info.orderResponseDate.value)
        assertEquals(spikeOrder.orderHeader.orderInfo.parties, info.parties)
        assertEquals(spikeOrder.orderItems.map { it.quantity }, response.orderResponseItems.map { it.quantity })
        assertEquals(BigDecimal("46.00"), response.orderResponseSummary.totalAmount)
    }

    @Test
    fun `order response can be adjusted in the block`() {
        val response = spikeOrder.toOrderResponse(date = "2026-11-18") {
            orderResponseItems[1] = orderResponseItems[1].copy(quantity = BigDecimal("2"))
        }
        assertEquals(BigDecimal("2"), roundTrip(response).orderResponseItems[1].quantity)
    }

    @Test
    fun `dispatch notification ships everything by default`() {
        val dispatch = roundTrip(spikeOrder.toDispatchNotification(id = "DN-1", date = "2026-11-24"))
        val items = dispatch.dispatchNotificationItems

        assertEquals(listOf("1", "2"), items.map { it.orderReference.lineItemId })
        assertEquals(listOf("PO-1001", "PO-1001"), items.map { it.orderReference.orderId })
        // No delivery party in the order: the buyer receives the goods.
        assertEquals("BUYER-1", dispatch.dispatchNotificationHeader.dispatchNotificationInfo.shipmentPartiesReference.deliveryIdref.value)
    }

    @Test
    fun `dispatch notification for a partial delivery`() {
        val dispatch = roundTrip(spikeOrder.toDispatchNotification(id = "DN-2", date = "2026-11-24", quantities = mapOf("1" to 40, "2" to 0)))

        assertEquals(listOf("1"), dispatch.dispatchNotificationItems.map { it.lineItemId })
        assertEquals(BigDecimal.valueOf(40), dispatch.dispatchNotificationItems.single().quantity)
        assertEquals(1, dispatch.dispatchNotificationSummary.totalItemNum)
        assertFailsWith<IllegalArgumentException> { spikeOrder.toDispatchNotification("DN-3", "2026-11-24", mapOf("99" to 1)) }
    }

    @Test
    fun `invoice computes tax and totals`() {
        val invoice = roundTrip(spikeOrder.toInvoice(id = "INV-1", date = "2026-11-30", vatRate = "0.19"))
        val summary = invoice.invoiceSummary
        val info = invoice.invoiceHeader.invoiceInfo

        assertEquals(BigDecimal("46.00"), summary.netValueGoods)
        assertEquals(BigDecimal("8.74"), summary.taxDetailsFixes.single().taxAmount)
        assertEquals(BigDecimal("54.74"), summary.totalAmount)
        assertEquals("SUPPLIER-1", info.invoiceIssuerIdref.value)
        assertEquals("BUYER-1", info.invoiceRecipientIdref.value)
        assertEquals("EUR", info.currency)
        assertEquals("PO-1001", invoice.invoiceHeader.orderHistory?.orderId)
    }

    @Test
    fun `invoice needs prices and a currency`() {
        val noCurrency = spikeOrder.copy(orderHeader = spikeOrder.orderHeader.copy(orderInfo = spikeOrder.orderHeader.orderInfo.copy(currency = null)))
        assertEquals("The order has no CURRENCY; an invoice needs one", assertFailsWith<IllegalArgumentException> { noCurrency.toInvoice("I", "2026-11-30", "0.19") }.message)

        val noPrice = spikeOrder.copy(orderItems = spikeOrder.orderItems.map { it.copy(productPriceFix = null, priceLineAmount = null) })
        assertFailsWith<IllegalArgumentException> { noPrice.toInvoice("I", "2026-11-30", "0.19") }
    }

    @Test
    fun `all three follow-up documents are different document types`() {
        assertEquals(
            listOf(OrderResponse::class, DispatchNotification::class, Invoice::class),
            listOf(spikeOrder.toOrderResponse("2026-11-18"), spikeOrder.toDispatchNotification("DN", "2026-11-24"), spikeOrder.toInvoice("I", "2026-11-30", "0.19")).map { it::class },
        )
    }
}

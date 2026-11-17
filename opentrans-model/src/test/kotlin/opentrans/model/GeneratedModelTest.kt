package opentrans.model

import arrow.optics.dsl.every
import arrow.optics.dsl.notNull
import opentrans.model.Card
import opentrans.model.Days
import opentrans.model.Debit
import opentrans.model.Order
import opentrans.model.OrderHeader
import opentrans.model.OrderInfo
import opentrans.model.OrderItem
import opentrans.model.OrderPartiesReference
import opentrans.model.OrderSummary
import opentrans.model.OrderType
import opentrans.model.Party
import opentrans.model.PartyId
import opentrans.model.Payment
import opentrans.model.PaymentTerms
import opentrans.model.ProductId
import opentrans.model.ProductPriceFix
import opentrans.model.SupplierPid
import opentrans.model.TimeForPayment
import opentrans.model.card
import opentrans.model.cardNum
import opentrans.model.days
import opentrans.model.due
import opentrans.model.method
import opentrans.model.orderHeader
import opentrans.model.orderId
import opentrans.model.orderInfo
import opentrans.model.orderItems
import opentrans.model.payment
import opentrans.model.paymentTerms
import opentrans.model.priceAmount
import opentrans.model.productPriceFix
import opentrans.model.timesForPayment
import opentrans.model.value
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The model generated from the XSD can be built and navigated with Arrow optics. */
class GeneratedModelTest {

    private val order = Order(
        orderHeader = OrderHeader(
            orderInfo = OrderInfo(
                orderId = "PO-1",
                orderDate = DateTime("2026-11-17"),
                parties = listOf(Party(partyIds = listOf(PartyId("B-1")))),
                orderPartiesReference = OrderPartiesReference(PartyId("B-1"), PartyId("S-1")),
                payment = Payment(
                    method = Card(cardNum = "4111", cardExpirationDate = DateTime("2028-05"), cardHolderName = "card-holder", type = "VISA"),
                    paymentTerms = PaymentTerms(timesForPayment = listOf(TimeForPayment(due = Days(10)))),
                ),
            ),
        ),
        orderItems = listOf(
            OrderItem(lineItemId = "1", productId = ProductId(supplierPid = SupplierPid("SKU-A")), quantity = BigDecimal("2"), orderUnit = "C62", productPriceFix = ProductPriceFix(BigDecimal("1.50"))),
            OrderItem(lineItemId = "2", productId = ProductId(supplierPid = SupplierPid("SKU-B")), quantity = BigDecimal("1"), orderUnit = "C62", productPriceFix = ProductPriceFix(BigDecimal("4.00"))),
        ),
        orderSummary = OrderSummary(totalItemNum = 2),
        type = OrderType.STANDARD,
    )

    @Test
    fun `lens chain through generated classes`() {
        assertEquals("PO-2", Order.orderHeader.orderInfo.orderId.set(order, "PO-2").orderHeader.orderInfo.orderId)
    }

    @Test
    fun `traversal over generated lists`() {
        val prices = Order.orderItems.every.productPriceFix.notNull.priceAmount
        assertEquals(listOf(BigDecimal("3.00"), BigDecimal("8.00")), prices.getAll(prices.modify(order) { it * BigDecimal(2) }))
    }

    @Test
    fun `prisms into generated choices`() {
        val method = Order.orderHeader.orderInfo.payment.notNull.method
        assertEquals("4111", method.card.cardNum.getOrNull(order))
        assertNull(method.card.cardNum.getOrNull(method.set(order, Debit(true))))

        val days = Order.orderHeader.orderInfo.payment.notNull.paymentTerms.notNull.timesForPayment.every.due.days.value
        assertEquals(listOf(10), days.getAll(order))
    }

    @Test
    fun `required repeated elements are enforced`() {
        assertFailsWith<IllegalArgumentException> { order.copy(orderItems = emptyList()) }
    }
}

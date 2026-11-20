package io.github.matthiasmack.opentrans.model

import arrow.optics.dsl.every
import arrow.optics.dsl.notNull
import io.github.matthiasmack.opentrans.model.Card
import io.github.matthiasmack.opentrans.model.Days
import io.github.matthiasmack.opentrans.model.Debit
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.OrderHeader
import io.github.matthiasmack.opentrans.model.OrderInfo
import io.github.matthiasmack.opentrans.model.OrderItem
import io.github.matthiasmack.opentrans.model.OrderPartiesReference
import io.github.matthiasmack.opentrans.model.OrderSummary
import io.github.matthiasmack.opentrans.model.OrderType
import io.github.matthiasmack.opentrans.model.Party
import io.github.matthiasmack.opentrans.model.PartyId
import io.github.matthiasmack.opentrans.model.Payment
import io.github.matthiasmack.opentrans.model.PaymentTerms
import io.github.matthiasmack.opentrans.model.ProductId
import io.github.matthiasmack.opentrans.model.ProductPriceFix
import io.github.matthiasmack.opentrans.model.SupplierPid
import io.github.matthiasmack.opentrans.model.TimeForPayment
import io.github.matthiasmack.opentrans.model.card
import io.github.matthiasmack.opentrans.model.cardNum
import io.github.matthiasmack.opentrans.model.days
import io.github.matthiasmack.opentrans.model.due
import io.github.matthiasmack.opentrans.model.method
import io.github.matthiasmack.opentrans.model.orderHeader
import io.github.matthiasmack.opentrans.model.orderId
import io.github.matthiasmack.opentrans.model.orderInfo
import io.github.matthiasmack.opentrans.model.orderItems
import io.github.matthiasmack.opentrans.model.payment
import io.github.matthiasmack.opentrans.model.paymentTerms
import io.github.matthiasmack.opentrans.model.priceAmount
import io.github.matthiasmack.opentrans.model.productPriceFix
import io.github.matthiasmack.opentrans.model.timesForPayment
import io.github.matthiasmack.opentrans.model.value
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
        // ITscope makes the payment method optional, hence notNull.
        assertEquals("4111", method.notNull.card.cardNum.getOrNull(order))
        assertNull(method.notNull.card.cardNum.getOrNull(method.set(order, Debit(true))))

        val days = Order.orderHeader.orderInfo.payment.notNull.paymentTerms.notNull.timesForPayment.every.due.days.value
        assertEquals(listOf(10), days.getAll(order))
    }

    @Test
    fun `required repeated elements are enforced`() {
        val error = assertFailsWith<IncompleteDocumentException> { order.copy(orderItems = emptyList()) }
        assertEquals("ORDER/ORDER_ITEM", error.element)
    }
}

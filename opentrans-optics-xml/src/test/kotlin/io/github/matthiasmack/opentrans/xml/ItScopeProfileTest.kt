package io.github.matthiasmack.opentrans.xml

import io.github.matthiasmack.opentrans.model.ContractInformation
import io.github.matthiasmack.opentrans.model.MlString
import io.github.matthiasmack.opentrans.model.OpenTransValidationException
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.OrderReference
import io.github.matthiasmack.opentrans.model.OrderResponse
import io.github.matthiasmack.opentrans.model.OrderType
import io.github.matthiasmack.opentrans.model.Payment
import io.github.matthiasmack.opentrans.model.PaymentTerms
import io.github.matthiasmack.opentrans.model.ProductStockFix
import io.github.matthiasmack.opentrans.model.ProductType
import io.github.matthiasmack.opentrans.model.RecurringType
import io.github.matthiasmack.opentrans.model.SerialNumber
import io.github.matthiasmack.opentrans.model.TotalRecurringAmount
import io.github.matthiasmack.opentrans.model.spikeOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** ITscope's extensions survive reading and writing; the OPENTRANS profile refuses them. */
class ItScopeProfileTest {
    private fun sample(name: String) = javaClass.getResource("/samples/$name")!!.readText()

    /** The spike order with every ITscope extension of ORDER that the model offers. */
    private val itscopeOrder: Order =
        spikeOrder.let { order ->
            val info = order.orderHeader.orderInfo
            val buyer = info.parties.first()
            val longName = MlString("x".repeat(100)) // ITscope: 100 characters, openTRANS: 50
            val firstItem =
                order.orderItems.first().let { item ->
                    item.copy(
                        productId =
                            item.productId.copy(
                                productType = ProductType.ESD,
                                serialNumbers = listOf(SerialNumber("4711", type = "imei")),
                            ),
                        productStockFix = ProductStockFix(stockAmount = BigDecimal("13"), stockStatus = "OnStock"),
                        contractInformation =
                            ContractInformation(
                                subscriptionPeriod = 12,
                                subscriptionPeriodUnit = RecurringType.MON,
                                billingPeriod = 1,
                            ),
                    )
                }
            order.copy(
                type = OrderType.EXPRESS_SATURDAY_10,
                orderHeader =
                    order.orderHeader.copy(
                        orderInfo =
                            info.copy(
                                subscriptionType = "RENEWAL",
                                parties =
                                    listOf(buyer.copy(addresses = buyer.addresses.map { it.copy(names = listOf(longName)) })) +
                                        info.parties.drop(1),
                            ),
                    ),
                orderItems = listOf(firstItem) + order.orderItems.drop(1),
                orderSummary =
                    order.orderSummary.copy(
                        totalRecurringAmounts = listOf(TotalRecurringAmount(BigDecimal("49.99"), RecurringType.MON)),
                    ),
            )
        }

    @Test
    fun `ITscope extensions survive writing and reading`() {
        val xml = OpenTransXml.write(itscopeOrder)
        assertContains(xml, "<CONTRACT_INFORMATION>")
        assertContains(xml, """<SERIAL_NUMBER type="imei">4711</SERIAL_NUMBER>""")
        assertContains(xml, """type="expressSaturday10"""")
        assertEquals(itscopeOrder, OpenTransXml.readAs<Order>(xml))
    }

    @Test
    fun `the OPENTRANS profile refuses ITscope extensions on writing and reading`() {
        val writeError = assertFailsWith<OpenTransValidationException> { OpenTransXml.write(itscopeOrder, Profile.OPENTRANS) }
        assertContains(writeError.errors.joinToString(), "expressSaturday10")

        val xml = OpenTransXml.write(itscopeOrder)
        assertFailsWith<OpenTransValidationException> { OpenTransXml.read(xml, Profile.OPENTRANS) }
    }

    @Test
    fun `a payment without method is ITscope only`() {
        val termsOnly = Payment(paymentTerms = PaymentTerms(valueDate = spikeOrder.orderHeader.orderInfo.orderDate))
        val order =
            spikeOrder.copy(
                orderHeader = spikeOrder.orderHeader.copy(orderInfo = spikeOrder.orderHeader.orderInfo.copy(payment = termsOnly)),
            )
        assertEquals(order, OpenTransXml.readAs<Order>(OpenTransXml.write(order)))
        assertFailsWith<OpenTransValidationException> { OpenTransXml.write(order, Profile.OPENTRANS) }
    }

    @Test
    fun `order responses can reference the original order line`() {
        val response = OpenTransXml.readAs<OrderResponse>(OpenTransXml.write(responseFor(spikeOrder)))
        val referenced =
            response.copy(
                orderResponseItems =
                    response.orderResponseItems.map {
                        it.copy(orderReference = OrderReference(orderId = "PO-1001", lineItemId = it.lineItemId))
                    },
            )
        val xml = OpenTransXml.write(referenced)
        assertEquals(referenced, OpenTransXml.readAs<OrderResponse>(xml))
        assertFailsWith<OpenTransValidationException> { OpenTransXml.write(referenced, Profile.OPENTRANS) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["order.xml", "invoice.xml", "dispatchnotification.xml", "invoicelist.xml", "order-spike.xml"])
    fun `official documents are valid in both profiles`(name: String) {
        val document = OpenTransXml.read(sample(name), Profile.OPENTRANS)
        assertEquals(document, OpenTransXml.read(OpenTransXml.write(document, Profile.OPENTRANS), Profile.OPENTRANS))
        assertEquals(document, OpenTransXml.read(sample(name)))
    }

    /** A minimal ORDERRESPONSE for [order], built by hand (the xml module has no DSL). */
    private fun responseFor(order: Order): OrderResponse {
        val info = order.orderHeader.orderInfo
        return OrderResponse(
            orderResponseHeader =
                io.github.matthiasmack.opentrans.model.OrderResponseHeader(
                    controlInfo = order.orderHeader.controlInfo,
                    orderResponseInfo =
                        io.github.matthiasmack.opentrans.model.OrderResponseInfo(
                            orderId = info.orderId,
                            orderResponseDate = info.orderDate,
                            orderDate = info.orderDate,
                            parties = info.parties,
                            orderPartiesReference = info.orderPartiesReference,
                        ),
                ),
            orderResponseItems =
                order.orderItems.map {
                    io.github.matthiasmack.opentrans.model.OrderResponseItem(
                        lineItemId = it.lineItemId,
                        productId = it.productId,
                        quantity = it.quantity,
                        orderUnit = it.orderUnit,
                    )
                },
            orderResponseSummary =
                io.github.matthiasmack.opentrans.model.OrderResponseSummary(
                    totalItemNum = order.orderItems.size,
                ),
        )
    }
}

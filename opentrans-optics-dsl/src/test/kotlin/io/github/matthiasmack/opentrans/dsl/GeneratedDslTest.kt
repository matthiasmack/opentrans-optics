package io.github.matthiasmack.opentrans.dsl

import io.github.matthiasmack.opentrans.model.Card
import io.github.matthiasmack.opentrans.model.IncompleteDocumentException
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.OrderType
import io.github.matthiasmack.opentrans.model.PartyRole
import io.github.matthiasmack.opentrans.xml.OpenTransXml
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The builders generated for every class: `Order { ... }`, `OrderItem { ... }`, ... */
class GeneratedDslTest {
    private val generated =
        Order {
            type = OrderType.STANDARD
            orderHeader {
                orderInfo {
                    orderId = "PO-1"
                    orderDate("2026-11-17")
                    party {
                        partyId("B-1")
                        partyRole(PartyRole.BUYER)
                        address {
                            name("Example Ltd", lang = "eng")
                            city("Musterstadt")
                            countryCoded = "DE"
                        }
                    }
                    party {
                        partyId("S-1")
                        partyRole(PartyRole.SUPPLIER)
                    }
                    orderPartiesReference {
                        buyerIdref("B-1")
                        supplierIdref("S-1")
                    }
                    currency = "EUR"
                    payment {
                        card(
                            cardNum = "4111",
                            cardExpirationDate =
                                io.github.matthiasmack.opentrans.model
                                    .DateTime("2028-05"),
                            cardHolderName = "card-holder",
                            type = "VISA",
                        )
                    }
                }
            }
            orderItem {
                lineItemId = "1"
                productId { supplierPid("SKU-1") }
                quantity(2)
                orderUnit = "C62"
                productPriceFix { priceAmount("1.50") }
                priceLineAmount("3.00")
            }
            orderSummary {
                totalItemNum = 1
                totalAmount("3.00")
            }
        }

    @Test
    fun `generated builders produce the same order as the hand-written DSL`() {
        val handWritten =
            order(id = "PO-1", date = "2026-11-17") {
                buyer {
                    id("B-1")
                    address {
                        name("Example Ltd", lang = "eng")
                        city("Musterstadt")
                        country = "DE"
                    }
                }
                supplier { id("S-1") }
                currency = "EUR"
                payment { card(type = "VISA", number = "4111", expires = "2028-05", holder = "card-holder") }
                item(quantity = 2, unit = "C62") {
                    supplierPid("SKU-1")
                    price("1.50")
                }
            }
        assertEquals(handWritten, generated)
    }

    @Test
    fun `generated DSL output is valid openTRANS`() {
        val reread = OpenTransXml.readAs<Order>(OpenTransXml.write(generated))
        assertEquals(generated, reread)
        assertIs<Card>(
            reread.orderHeader.orderInfo.payment
                ?.method,
        )
        assertEquals(BigDecimal("3.00"), reread.orderSummary.totalAmount)
    }

    @Test
    fun `missing required values name the XML element`() {
        val error = assertFailsWith<IncompleteDocumentException> { Order { orderSummary { totalItemNum = 0 } } }
        assertEquals("ORDER needs ORDER_HEADER (orderHeader)", error.message)
        assertEquals("ORDER/ORDER_HEADER", error.element)
    }
}

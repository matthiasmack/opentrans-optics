package io.github.matthiasmack.opentrans.dsl

import io.github.matthiasmack.opentrans.model.DeliveryDateType
import io.github.matthiasmack.opentrans.model.FacetViolationException
import io.github.matthiasmack.opentrans.model.IncompleteDocumentException
import io.github.matthiasmack.opentrans.model.OrderType
import io.github.matthiasmack.opentrans.model.PartyRole
import io.github.matthiasmack.opentrans.model.spikeOrder
import io.github.matthiasmack.opentrans.xml.OpenTransXml
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OrderDslTest {
    @Test
    fun `the DSL builds exactly the spike order`() {
        val built =
            order(id = "PO-1001", date = LocalDate.of(2026, 11, 17)) {
                type = OrderType.EXPRESS
                generationDate = OffsetDateTime.parse("2026-11-17T09:30:00+01:00")
                delivery(from = "2026-11-24", to = "2026-11-28", type = DeliveryDateType.OPTIONAL)

                buyer {
                    id("BUYER-1", "buyer_specific")
                    id("4000000000001", "gln")
                    role(PartyRole.INVOICE_RECIPIENT)
                    address {
                        name("Beispiel GmbH", lang = "deu")
                        name("Example Ltd", lang = "eng")
                        street("Musterweg 1")
                        zip("10000")
                        city("Musterstadt")
                        country = "DE"
                        vatId = "DE000000000"
                    }
                    account(
                        holder = "Example Ltd",
                        iban = "DE00000000000000000000",
                        bic = "XXXXDEXXXXX",
                        bankName = "Example Bank",
                        bankCountry = "DE",
                    )
                }
                supplier {
                    id("SUPPLIER-1", "supplier_specific")
                }

                currency = "EUR"
                payment {
                    card(type = "VISA", number = "4111111111111111", expires = YearMonth.of(2028, 5), holder = "card-holder")
                    centralRegulation = false
                    terms {
                        withinDays(10, discount = "0.02")
                        dueOn(LocalDate.of(2026, 12, 31))
                    }
                }
                termsAndConditions = "general terms"
                partialShipmentAllowed = true
                remark("Bitte an Tor 2 liefern.", type = "general", lang = "deu")
                remark("fragile", type = "transport")
                udx("""<UDX.CUSTOM xmlns="urn:example:udx"><PROJECT>project-7</PROJECT><PRIORITY level="high"/></UDX.CUSTOM>""")

                item(quantity = 100, unit = "C62") {
                    supplierPid("SKU-A", "supplier_specific")
                    buyerPid("MAT-100", "buyer_specific")
                    description("Schraube M4", lang = "deu")
                    description("Screw M4", lang = "eng")
                    longDescription("Stainless steel screw, M4 x 20", lang = "eng")
                    price("0.15", per = 1)
                    udx("""<UDX.CUSTOM xmlns="urn:example:udx"><COST_CENTER>cc-42</COST_CENTER></UDX.CUSTOM>""")
                }
                item(quantity = 2.5, unit = "KGM") {
                    supplierPid("SKU-B")
                    description("Mutter M4")
                    price("12.40")
                    remark("deliver separately", type = "order")
                }
            }

        assertEquals(spikeOrder, built)
    }

    @Test
    fun `a minimal order is valid openTRANS`() {
        val minimal =
            order(id = "PO-1", date = "2026-11-17") {
                buyer { id("B-1") }
                supplier { id("S-1") }
                item(quantity = 1, unit = "C62") { supplierPid("SKU-1") }
            }

        val xml = OpenTransXml.write(minimal) // validates against the XSD
        assertEquals(minimal, OpenTransXml.read(xml))
    }

    @Test
    fun `the DSL fills in references, roles, line ids and totals`() {
        val order =
            order(id = "PO-2", date = "2026-11-17") {
                buyer { id("B-1", "buyer_specific") }
                supplier {
                    id("S-1")
                    id("S-1-GLN", "gln")
                }
                party(PartyRole.DELIVERY) { id("D-1") }
                item(quantity = 3, unit = "C62") { price("1.99") }
                item(quantity = "0.5", unit = "KGM", lineItemId = "A-7") { price(10, per = 2) }
            }
        val info = order.orderHeader.orderInfo

        assertEquals("B-1", info.orderPartiesReference.buyerIdref.value)
        assertEquals("S-1", info.orderPartiesReference.supplierIdref.value)
        assertEquals(
            listOf(listOf(PartyRole.BUYER), listOf(PartyRole.SUPPLIER), listOf(PartyRole.DELIVERY)),
            info.parties.map { it.partyRoles },
        )
        assertEquals(listOf("1", "A-7"), order.orderItems.map { it.lineItemId })
        // 0.5 x 10 / 2 = 2.5; an integer unit price must not round this to 3.
        assertEquals(listOf(BigDecimal("5.97"), BigDecimal("2.50")), order.orderItems.map { it.priceLineAmount })
        assertEquals(2, order.orderSummary.totalItemNum)
        assertEquals(BigDecimal("8.47"), order.orderSummary.totalAmount)
    }

    @Test
    fun `without prices there is no total`() {
        val order =
            order(id = "PO-3", date = "2026-11-17") {
                buyer { id("B-1") }
                supplier { id("S-1") }
                item(quantity = 1, unit = "C62") { price("2.00") }
                item(quantity = 1, unit = "C62") { }
            }
        assertNull(order.orderSummary.totalAmount)
    }

    @Test
    fun `explicit line amount wins over the computed one`() {
        val order =
            order(id = "PO-4", date = "2026-11-17") {
                buyer { id("B-1") }
                supplier { id("S-1") }
                item(quantity = 3, unit = "C62") {
                    price("1.00")
                    lineAmount = BigDecimal("2.50")
                }
            }
        assertEquals(BigDecimal("2.50"), order.orderSummary.totalAmount)
    }

    @Test
    fun `doubles keep their decimal value`() {
        val order =
            order(id = "PO-5", date = "2026-11-17") {
                buyer { id("B-1") }
                supplier { id("S-1") }
                item(quantity = 0.1, unit = "KGM") { price(0.3) }
            }
        assertEquals(BigDecimal("0.1"), order.orderItems.single().quantity)
        assertEquals(
            BigDecimal("0.3"),
            order.orderItems
                .single()
                .productPriceFix
                ?.priceAmount,
        )
    }

    @Test
    fun `missing or contradicting parts fail with a clear message`() {
        val noSupplier =
            assertFailsWith<IncompleteDocumentException> {
                order(id = "PO", date = "2026-11-17") {
                    buyer { id("B-1") }
                    item(quantity = 1, unit = "C62") { }
                }
            }
        assertEquals("an order needs a supplier { }", noSupplier.message)
        assertEquals("ORDER_INFO/PARTIES/PARTY", noSupplier.element)
        val noId = assertFailsWith<IncompleteDocumentException> { order(id = "PO", date = "2026-11-17") { buyer { } } }
        assertEquals("A party needs at least one id(...) (buyer)", noId.message)
        assertEquals("PARTY/PARTY_ID", noId.element)
        // Using the DSL wrongly is a programming error, not missing data.
        assertEquals(
            "PAYMENT allows only one payment method, already set: Debit",
            assertFailsWith<IllegalStateException> {
                order(id = "PO", date = "2026-11-17") {
                    payment {
                        debit()
                        cash()
                    }
                }
            }.message,
        )
        assertFailsWith<FacetViolationException> { order(id = "PO", date = "17.11.2026") { } }
    }
}

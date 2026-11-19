package io.github.matthiasmack.opentrans.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The XSD facets are checked when model objects are created. That every
 * valid document still passes is shown by the xml and dsl tests, which read,
 * derive and write documents through the same checks.
 */
class FacetTest {

    private fun violation(block: () -> Unit) = assertFailsWith<FacetViolationException>(block = block).message

    @Test
    fun `length of a string element`() {
        assertEquals(
            "CARD/CARD_NUM: at most 16 characters, got 17",
            violation { Card(cardNum = "1".repeat(17), cardExpirationDate = DateTime("2028-05"), cardHolderName = "x", type = "VISA") },
        )
        assertEquals(
            "CARD/CARD_HOLDER_NAME: at least 1 characters, got 0",
            violation { Card(cardNum = "4111", cardExpirationDate = DateTime("2028-05"), cardHolderName = "", type = "VISA") },
        )
    }

    @Test
    fun `length of a shared class is checked in the containing class`() {
        // MlString itself has no limit; bmecat:NAME inside ADDRESS allows 50 characters.
        MlString("x".repeat(51))
        assertEquals("ADDRESS/NAME: at most 50 characters, got 51", violation { Address(names = listOf(MlString("x".repeat(51)))) })
    }

    @Test
    fun `code lists kept as strings are checked`() {
        Language("deu")
        assertEquals("LANGUAGE/value: 'klingon' is not an allowed value", violation { Language("klingon") })
        assertEquals("ORDER_INFO/CURRENCY: 'EURO' is not an allowed value", violation {
            OrderInfo(
                orderId = "PO-1",
                orderDate = DateTime("2026-11-17"),
                parties = listOf(Party(partyIds = listOf(PartyId("B-1")))),
                orderPartiesReference = OrderPartiesReference(PartyId("B-1"), PartyId("S-1")),
                currency = "EURO",
            )
        })
    }

    @Test
    fun `open type attributes accept any word, including umlauts, but not punctuation`() {
        PartyId("B-1", "duns")
        // XSD \w allows letters of any script; Java's \w would reject the umlaut.
        PartyId("B-1", "kundenspezifischä")
        // For XSD, '_' is punctuation, so it is only valid inside the listed values (buyer_specific, ...).
        // xmllint rejects this value as well.
        assertEquals("typePARTY_ID/@type: 'kundenspezifisch_ä' does not match the XSD pattern", violation { PartyId("B-1", "kundenspezifisch_ä") })
        assertEquals("typePARTY_ID/@type: 'not allowed!' does not match the XSD pattern", violation { PartyId("B-1", "not allowed!") })
    }
}

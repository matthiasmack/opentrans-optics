package io.github.matthiasmack.opentrans.dsl

import io.github.matthiasmack.opentrans.model.DocumentTypeException
import io.github.matthiasmack.opentrans.model.FacetViolationException
import io.github.matthiasmack.opentrans.model.IncompleteDocumentException
import io.github.matthiasmack.opentrans.model.Invoice
import io.github.matthiasmack.opentrans.model.OpenTransException
import io.github.matthiasmack.opentrans.model.OpenTransValidationException
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.orderHeader
import io.github.matthiasmack.opentrans.model.orderId
import io.github.matthiasmack.opentrans.model.orderInfo
import io.github.matthiasmack.opentrans.model.spikeOrder
import io.github.matthiasmack.opentrans.udx.CostCenter
import io.github.matthiasmack.opentrans.udx.CostCenterUdx
import io.github.matthiasmack.opentrans.udx.UdxViolationException
import io.github.matthiasmack.opentrans.xml.OpenTransXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Every problem with a document or its data can be caught as one [OpenTransException]. */
class ExceptionsTest {
    private fun caught(block: () -> Unit): OpenTransException = assertFailsWith<OpenTransException> { block() }

    @Test
    fun `one catch covers every data problem`() {
        assertIs<OpenTransValidationException>(caught { OpenTransXml.read("<ORDER/>") })
        assertIs<FacetViolationException>(
            caught {
                Order.orderHeader.orderInfo.orderId
                    .set(spikeOrder, "x".repeat(251))
            },
        )
        assertIs<IncompleteDocumentException>(caught { order(id = "PO", date = "2026-11-17") { } })
        assertIs<UdxViolationException>(caught { udx { add(CostCenterUdx, CostCenter("42")) } })
        val wrongType = caught { OpenTransXml.readAs<Invoice>(OpenTransXml.write(spikeOrder)) }
        assertIs<DocumentTypeException>(wrongType)
        assertEquals("Expected Invoice, found Order", wrongType.message)
    }

    @Test
    fun `wrong use of the API is not a data problem`() {
        val error =
            assertFailsWith<IllegalStateException> {
                order(id = "PO", date = "2026-11-17") {
                    buyer { id("B") }
                    buyer { id("C") }
                }
            }
        assertEquals("buyer { } may only be given once", error.message)
    }
}

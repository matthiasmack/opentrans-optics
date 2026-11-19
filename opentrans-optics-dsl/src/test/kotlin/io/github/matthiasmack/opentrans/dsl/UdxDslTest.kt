package io.github.matthiasmack.opentrans.dsl

import io.github.matthiasmack.opentrans.model.OrderItem
import io.github.matthiasmack.opentrans.model.XmlContent
import io.github.matthiasmack.opentrans.model.XmlElement
import io.github.matthiasmack.opentrans.model.XmlText
import io.github.matthiasmack.opentrans.model.spikeOrder
import io.github.matthiasmack.opentrans.udx.CostCenter
import io.github.matthiasmack.opentrans.udx.CostCenterUdx
import io.github.matthiasmack.opentrans.udx.Level
import io.github.matthiasmack.opentrans.udx.Priority
import io.github.matthiasmack.opentrans.udx.Project
import io.github.matthiasmack.opentrans.udx.ProjectUdx
import io.github.matthiasmack.opentrans.udx.UdxViolationException
import io.github.matthiasmack.opentrans.udx.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UdxDslTest {

    @Test
    fun `typed UDX in the DSL gives the same content as the XML text`() {
        assertEquals(spikeOrder.orderHeader.orderInfo.headerUdx, udx { add(ProjectUdx, Project("project-7", Priority(Level.HIGH))) })
    }

    @Test
    fun `typed UDX, XML text and plain elements keep their order`() {
        val content = udx {
            xml("""<UDX.OTHER xmlns="urn:partner">kept</UDX.OTHER>""")
            add(CostCenterUdx, CostCenter("cc-1"))
            element(XmlElement("UDX.PLAIN"))
            text("tail")
        }
        assertEquals(
            listOf("UDX.OTHER", "UDX.CUSTOM", "UDX.PLAIN"),
            content.elements.map { it.name.localPart },
        )
        assertEquals(XmlText("tail"), content.nodes.last())
        assertEquals(CostCenter("cc-1"), content[CostCenterUdx])
    }

    @Test
    fun `generated builders offer a UDX block for every UDX property`() {
        val item = OrderItem {
            lineItemId = "1"
            productId { supplierPid("SKU-1") }
            quantity(2)
            orderUnit = "C62"
            itemUdx { add(CostCenterUdx, CostCenter("cc-42")) }
        }
        assertEquals(XmlContent.parse("""<UDX.CUSTOM xmlns="urn:example:udx"><COST_CENTER>cc-42</COST_CENTER></UDX.CUSTOM>"""), item.itemUdx)
    }

    @Test
    fun `invalid typed UDX fails where it is added`() {
        val error = assertFailsWith<UdxViolationException> { udx { add(CostCenterUdx, CostCenter("42")) } }
        assertEquals("UDX.CUSTOM/COST_CENTER: '42' does not match cc-\\d+", error.message)
    }
}

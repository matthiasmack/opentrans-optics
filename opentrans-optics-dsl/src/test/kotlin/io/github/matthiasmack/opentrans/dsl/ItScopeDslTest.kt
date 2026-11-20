package io.github.matthiasmack.opentrans.dsl

import io.github.matthiasmack.opentrans.itscope.ItScopeRules
import io.github.matthiasmack.opentrans.itscope.bundlePartsListVisible
import io.github.matthiasmack.opentrans.itscope.dropshipment
import io.github.matthiasmack.opentrans.model.IncompleteDocumentException
import io.github.matthiasmack.opentrans.model.ContractInformation
import io.github.matthiasmack.opentrans.model.OpenTransValidationException
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.OrderReference
import io.github.matthiasmack.opentrans.model.OrderResponse
import io.github.matthiasmack.opentrans.model.ProductType
import io.github.matthiasmack.opentrans.model.RecurringType
import io.github.matthiasmack.opentrans.model.TotalRecurringAmount
import io.github.matthiasmack.opentrans.model.spikeOrder
import io.github.matthiasmack.opentrans.udx.CostCenter
import io.github.matthiasmack.opentrans.udx.CostCenterUdx
import io.github.matthiasmack.opentrans.udx.Project
import io.github.matthiasmack.opentrans.udx.ProjectUdx
import io.github.matthiasmack.opentrans.udx.get
import io.github.matthiasmack.opentrans.xml.OpenTransXml
import io.github.matthiasmack.opentrans.xml.Profile
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ItScopeDslTest {

    /** Like ITscope's example "Laufzeitprodukt": a subscription next to a one-off item. */
    private val subscriptionOrder = order(id = "PO-1", date = LocalDate.of(2026, 11, 20)) {
        buyer {
            id("12345", "supplier_specific")
            address { name("Example Ltd"); street("Musterweg 1"); zip("10000"); city("Musterstadt"); country = "DE" }
        }
        supplier { id("10000735", "buyer_specific") }
        currency = "EUR"
        subscriptionType = "SALES"
        dropshipment()
        udx { add(ProjectUdx, Project("project-7", null)) }

        item(quantity = 1, unit = "MON") {
            supplierPid("123002", "supplier_specific")
            description("iPad Pro, rented")
            price("49.99")
            contract(period = 12, unit = RecurringType.MON, billing = 1)
            stock(amount = 13, status = "OnStock")
        }
        item(quantity = 1, unit = "C62") {
            supplierPid("123003", "supplier_specific")
            description("Microsoft 365, ESD")
            productType = ProductType.ESD
            price("1158.00")
            udx { add(CostCenterUdx, CostCenter("cc-42")) }
        }
    }

    @Test
    fun `the order DSL builds ITscope's extensions`() {
        val (rented, esd) = subscriptionOrder.orderItems
        assertEquals(ContractInformation(subscriptionPeriod = 12, subscriptionPeriodUnit = RecurringType.MON, billingPeriod = 1), rented.contractInformation)
        assertEquals(BigDecimal("13"), rented.productStockFix?.stockAmount)
        assertEquals(ProductType.ESD, esd.productId.productType)
        assertEquals("SALES", subscriptionOrder.orderHeader.orderInfo.subscriptionType)
    }

    @Test
    fun `recurring items have their own total, as ITscope expects`() {
        val summary = subscriptionOrder.orderSummary
        assertEquals(BigDecimal("1158.00"), summary.totalAmount)
        assertEquals(listOf(TotalRecurringAmount(BigDecimal("49.99"), RecurringType.MON)), summary.totalRecurringAmounts)
        assertEquals(2, summary.totalItemNum)
    }

    @Test
    fun `UDX from several calls add up`() {
        assertEquals(true, subscriptionOrder.dropshipment)
        assertEquals(Project("project-7", null), subscriptionOrder.orderHeader.orderInfo.headerUdx!![ProjectUdx])
        val xml = OpenTransXml.write(subscriptionOrder)
        assertContains(xml, "<UDX.DROPSHIPMENT xmlns=\"\">true</UDX.DROPSHIPMENT>")
    }

    @Test
    fun `the order is valid for ITscope, but not plain openTRANS`() {
        assertEquals(subscriptionOrder, OpenTransXml.readAs<Order>(OpenTransXml.write(subscriptionOrder)))
        assertFailsWith<OpenTransValidationException> { OpenTransXml.write(subscriptionOrder, Profile.OPENTRANS) }
        assertEquals(emptyList(), ItScopeRules.violations(subscriptionOrder))
    }

    @Test
    fun `order responses refer back to the order lines`() {
        val response = spikeOrder.toOrderResponse(date = "2026-11-18")
        assertEquals(
            listOf(OrderReference(orderId = "PO-1001", lineItemId = "1"), OrderReference(orderId = "PO-1001", lineItemId = "2")),
            response.orderResponseItems.map { it.orderReference },
        )
        assertEquals(response, OpenTransXml.readAs<OrderResponse>(OpenTransXml.write(response)))

        val plain = spikeOrder.toOrderResponse(date = "2026-11-18", referenceLines = false)
        assertNull(plain.orderResponseItems.first().orderReference)
        OpenTransXml.write(plain, Profile.OPENTRANS)
    }

    // --- bundles ---

    /** ITscope's bundle example: a phone with screen protector and case as one item. */
    private val bundleOrder = order(id = "PO-2", date = LocalDate.of(2026, 11, 20)) {
        buyer { id("12345", "supplier_specific") }
        supplier { id("10000735", "buyer_specific") }
        item(quantity = 1, unit = "C62") {
            supplierPid("SAMPLE_SUPPLIER_ID", "supplier_specific")
            internationalPid("1000001209000", "itscope")
            description("Apple iPhone SE 2022 128GB rot - Bundle")
            price("510.00")
            bundle(partsListVisible = true) {
                component(quantity = 1, unit = "C62") {
                    supplierPid("13113721000", "supplier_specific")
                    internationalPid("0194253014645", "ean")
                    longDescription("Apple iPhone SE - (PRODUCT) RED - 5G Smartphone, 128 GB")
                }
                component(quantity = 1, unit = "C62") {
                    supplierPid("1000001211000", "supplier_specific")
                    longDescription("Panzerglasfolie für Apple iPhone SE 2022")
                }
            }
        }
    }

    @Test
    fun `bundles carry their parts, product types and parts list flag`() {
        val bundle = bundleOrder.orderItems.single()
        assertEquals(ProductType.BUNDLE, bundle.productId.productType)
        assertEquals(listOf("13113721000", "1000001211000"), bundle.productComponents.map { it.productId.supplierPid?.value })
        assertEquals(listOf(ProductType.COMPONENT, ProductType.COMPONENT), bundle.productComponents.map { it.productId.productType })
        assertEquals(true, bundle.bundlePartsListVisible)
        assertEquals(BigDecimal("510.00"), bundleOrder.orderSummary.totalAmount)
    }

    @Test
    fun `bundles are plain openTRANS, so both profiles accept them`() {
        assertEquals(bundleOrder, OpenTransXml.readAs<Order>(OpenTransXml.write(bundleOrder)))
        assertEquals(bundleOrder, OpenTransXml.readAs<Order>(OpenTransXml.write(bundleOrder, Profile.OPENTRANS), Profile.OPENTRANS))
    }

    @Test
    fun `a bundle needs parts and is given once`() {
        val empty = assertFailsWith<IncompleteDocumentException> {
            order(id = "PO", date = "2026-11-20") {
                buyer { id("B") }; supplier { id("S") }
                item(quantity = 1, unit = "C62") { bundle { } }
            }
        }
        assertEquals("PRODUCT_COMPONENTS/PRODUCT_COMPONENT", empty.element)
        val twice = assertFailsWith<IllegalStateException> {
            order(id = "PO", date = "2026-11-20") {
                buyer { id("B") }; supplier { id("S") }
                item(quantity = 1, unit = "C62") {
                    bundle { component(1, "C62") { supplierPid("A") } }
                    bundle { component(1, "C62") { supplierPid("B") } }
                }
            }
        }
        assertEquals("bundle { } may only be given once per item", twice.message)
    }
}

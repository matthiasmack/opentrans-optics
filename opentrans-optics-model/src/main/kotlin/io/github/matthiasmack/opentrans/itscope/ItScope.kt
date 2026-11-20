package io.github.matthiasmack.opentrans.itscope

import io.github.matthiasmack.opentrans.model.Address
import io.github.matthiasmack.opentrans.model.FacetViolationException
import io.github.matthiasmack.opentrans.model.IncompleteDocumentException
import io.github.matthiasmack.opentrans.model.OpenTransException
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.OrderItem
import io.github.matthiasmack.opentrans.model.PartyRole
import io.github.matthiasmack.opentrans.udx.SimpleTypes
import io.github.matthiasmack.opentrans.udx.UdxType
import io.github.matthiasmack.opentrans.udx.get

/*
 * What ITscope adds on top of its schema: the UDX elements it documents and
 * the rules its order API checks beyond the XSD. Everything else ITscope
 * extends (subscriptions, stock, ESD, ...) is part of the generated model.
 */

/** UDX elements that ITscope documents and processes. */
object ItScopeUdx {
    /** HEADER_UDX: ship with the buyer's own delivery note (`true`) or not (`false`). */
    val DROPSHIPMENT: UdxType<Boolean> = UdxType.value("UDX.DROPSHIPMENT", SimpleTypes.boolean())

    /** ITEM_UDX of a bundle: whether the bundle's parts list is shown on the ITscope platform. */
    val BUNDLE_PARTS_LIST_VISIBLE: UdxType<Boolean> = UdxType.value("UDX.BUNDLEPARTSLISTVISIBLE", SimpleTypes.boolean())
}

/** Whether the order is shipped with the buyer's own delivery note (UDX.DROPSHIPMENT); null if not stated. */
val Order.dropshipment: Boolean? get() = orderHeader.orderInfo.headerUdx?.get(ItScopeUdx.DROPSHIPMENT)

/** Whether the parts list of this bundle item is shown on the ITscope platform (UDX.BUNDLEPARTSLISTVISIBLE); null if not stated. */
val OrderItem.bundlePartsListVisible: Boolean? get() = itemUdx?.get(ItScopeUdx.BUNDLE_PARTS_LIST_VISIBLE)

/**
 * Rules ITscope's order API checks beyond its schema, so an order can be
 * checked before it is sent:
 *
 * - ORDER_ID has at most 18 characters.
 * - The addresses of the buyer and the delivery party have a name, street,
 *   ZIP code and country.
 * - Every item has a SUPPLIER_PID, the distributor's article number.
 *
 * ```
 * val problems = ItScopeRules.violations(order)   // empty if ITscope would accept the order
 * ```
 */
object ItScopeRules {
    const val MAX_ORDER_ID_LENGTH = 18

    /**
     * Every rule the order breaks, in document order; empty if ITscope would accept it.
     * A value that is too long is a [FacetViolationException], a missing value an
     * [IncompleteDocumentException] with the XML path in `element`.
     */
    fun violations(order: Order): List<OpenTransException> = buildList {
        val info = order.orderHeader.orderInfo
        val idLength = info.orderId.codePointCount(0, info.orderId.length)
        if (idLength > MAX_ORDER_ID_LENGTH) {
            add(FacetViolationException("ORDER_INFO/ORDER_ID: at most $MAX_ORDER_ID_LENGTH characters for ITscope, got $idLength"))
        }
        for (party in info.parties) {
            val role = listOf(PartyRole.BUYER, PartyRole.DELIVERY).firstOrNull { it in party.partyRoles } ?: continue
            val address = party.addresses.firstOrNull()
            for ((element, present) in addressFields(address)) {
                if (!present) add(IncompleteDocumentException("PARTY/ADDRESS/$element", "ITscope needs $element in the address of the ${role.xml} party"))
            }
        }
        for (item in order.orderItems) {
            if (item.productId.supplierPid == null) {
                add(IncompleteDocumentException("ORDER_ITEM/PRODUCT_ID/SUPPLIER_PID", "ITscope needs a SUPPLIER_PID for item ${item.lineItemId}"))
            }
        }
    }

    private fun addressFields(address: Address?): List<Pair<String, Boolean>> = listOf(
        "NAME" to (address?.names?.isNotEmpty() == true),
        "STREET" to (address?.streets?.isNotEmpty() == true),
        "ZIP" to (address?.zips?.isNotEmpty() == true),
        "COUNTRY_CODED" to (address?.countryCoded != null || address?.countries?.isNotEmpty() == true),
    )
}

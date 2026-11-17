package opentrans.dsl

import opentrans.model.DispatchNotification
import opentrans.model.Invoice
import opentrans.model.Order
import opentrans.model.OrderItem
import opentrans.model.OrderResponse
import opentrans.model.ShipmentPartiesReference
import opentrans.model.TaxDetailsFix
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Follow-up documents derived from an ORDER.
 *
 * Each function copies what the follow-up document shares with the order
 * (parties, references, items, prices) and then runs [block] on the
 * generated builder, so anything can still be set or overridden. For deep
 * changes after the fact, use the optics on the result.
 */

/**
 * ORDERRESPONSE confirming the order as it is: same items, quantities and prices.
 *
 * ```
 * val response = order.toOrderResponse(date = LocalDate.now()) {
 *     orderResponseHeader { ... }   // optional overrides
 * }
 * ```
 */
fun Order.toOrderResponse(date: Any, block: OrderResponseBuilder.() -> Unit = {}): OrderResponse {
    val info = orderHeader.orderInfo
    val items = orderItems
    return OrderResponse {
        orderResponseHeader {
            orderResponseInfo {
                orderId = info.orderId
                orderResponseDate(date)
                orderDate = info.orderDate
                deliveryDate = info.deliveryDate
                parties += info.parties
                orderPartiesReference = info.orderPartiesReference
                currency = info.currency
            }
        }
        for (item in items) {
            orderResponseItem {
                lineItemId = item.lineItemId
                productId = item.productId
                quantity = item.quantity
                orderUnit = item.orderUnit
                productPriceFix = item.productPriceFix
                priceLineAmount = item.priceLineAmount
            }
        }
        orderResponseSummary {
            totalItemNum = items.size
            totalAmount = orderSummary.totalAmount
        }
        block()
    }
}

/**
 * DISPATCHNOTIFICATION for the order.
 *
 * Ships every item in full unless [quantities] says otherwise (line item id ->
 * shipped quantity); items with quantity 0 are left out. The delivery party
 * is the order's delivery party, or the buyer if the order names none.
 */
fun Order.toDispatchNotification(
    id: String,
    date: Any,
    quantities: Map<String, Number> = emptyMap(),
    block: DispatchNotificationBuilder.() -> Unit = {},
): DispatchNotification {
    val info = orderHeader.orderInfo
    val references = info.orderPartiesReference
    val shipment = references.shipmentPartiesReference ?: ShipmentPartiesReference(deliveryIdref = references.buyerIdref)
    val shipped = orderItems
        .map { it to (quantities[it.lineItemId]?.toDecimal() ?: it.quantity) }
        .filter { (_, quantity) -> quantity.signum() > 0 }
    require(shipped.isNotEmpty()) { "A dispatch notification needs at least one item with a quantity above 0" }
    val unknown = quantities.keys - orderItems.map { it.lineItemId }.toSet()
    require(unknown.isEmpty()) { "Unknown line item ids: $unknown" }

    return DispatchNotification {
        dispatchNotificationHeader {
            dispatchNotificationInfo {
                dispatchNotificationId = id
                dispatchNotificationDate(date)
                deliveryDate = info.deliveryDate
                parties += info.parties
                supplierIdref = references.supplierIdref
                buyerIdref = references.buyerIdref
                shipmentPartiesReference = shipment
            }
        }
        for ((item, quantity) in shipped) {
            dispatchNotificationItem {
                lineItemId = item.lineItemId
                productId = item.productId
                this.quantity = quantity
                orderUnit = item.orderUnit
                orderReference {
                    orderId = info.orderId
                    lineItemId = item.lineItemId
                    orderDate = info.orderDate
                }
                shipmentPartiesReference = shipment
            }
        }
        dispatchNotificationSummary(totalItemNum = shipped.size)
        block()
    }
}

/**
 * INVOICE for the order with one VAT rate, e.g. `vatRate = "0.19"`.
 *
 * Every item needs a price. Line amounts come from the order or are computed
 * as quantity x price / price quantity; tax is rounded half-up to cents.
 * The issuer is the supplier, the recipient the order's invoice recipient or
 * else the buyer.
 */
fun Order.toInvoice(
    id: String,
    date: Any,
    vatRate: String,
    block: InvoiceBuilder.() -> Unit = {},
): Invoice {
    val info = orderHeader.orderInfo
    val references = info.orderPartiesReference
    val rate = BigDecimal(vatRate)
    val currency = requireNotNull(info.currency) { "The order has no CURRENCY; an invoice needs one" }
    val lines = orderItems.map { it to lineAmount(it) }
    val net = lines.map { it.second }.reduce(BigDecimal::add)
    val tax = net.multiply(rate).setScale(2, RoundingMode.HALF_UP)
    val vat = TaxDetailsFix(taxType = "vat", taxCategory = "standard_rate", tax = rate)

    return Invoice {
        invoiceHeader {
            invoiceInfo {
                invoiceId = id
                invoiceDate(date)
                deliveryDate = info.deliveryDate
                parties += info.parties
                invoiceIssuerIdref = references.supplierIdref
                invoiceRecipientIdref = references.invoiceRecipientIdref ?: references.buyerIdref
                buyerIdref = references.buyerIdref
                supplierIdref = references.supplierIdref
                this.currency = currency
            }
            orderHistory {
                orderId = info.orderId
                orderDate = info.orderDate
            }
        }
        for ((item, amount) in lines) {
            invoiceItem {
                lineItemId = item.lineItemId
                productId = item.productId
                quantity = item.quantity
                orderUnit = item.orderUnit
                productPriceFix = item.productPriceFix!!.let { price ->
                    if (price.taxDetailsFixes.isEmpty()) price.copy(taxDetailsFixes = listOf(vat)) else price
                }
                priceLineAmount = amount
                orderReference {
                    orderId = info.orderId
                    lineItemId = item.lineItemId
                }
            }
        }
        invoiceSummary {
            totalItemNum = lines.size
            netValueGoods = net
            totalAmount = net + tax
            taxDetailsFixes += vat.copy(taxAmount = tax, taxBase = net)
        }
        block()
    }
}

private fun lineAmount(item: OrderItem): BigDecimal {
    item.priceLineAmount?.let { return it }
    val price = requireNotNull(item.productPriceFix) { "Item ${item.lineItemId} has no price; an invoice needs one for every item" }
    val perUnit = price.priceQuantity ?: BigDecimal.ONE
    return item.quantity.multiply(price.priceAmount).divide(perUnit, maxOf(price.priceAmount.scale(), 2), RoundingMode.HALF_UP)
}

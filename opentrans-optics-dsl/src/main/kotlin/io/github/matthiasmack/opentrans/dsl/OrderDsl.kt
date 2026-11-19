package io.github.matthiasmack.opentrans.dsl

import io.github.matthiasmack.opentrans.model.IncompleteDocumentException
import io.github.matthiasmack.opentrans.model.BuyerPid
import io.github.matthiasmack.opentrans.model.ControlInfo
import io.github.matthiasmack.opentrans.model.DeliveryDate
import io.github.matthiasmack.opentrans.model.DeliveryDateType
import io.github.matthiasmack.opentrans.model.MlString
import io.github.matthiasmack.opentrans.model.Order
import io.github.matthiasmack.opentrans.model.OrderHeader
import io.github.matthiasmack.opentrans.model.OrderInfo
import io.github.matthiasmack.opentrans.model.OrderItem
import io.github.matthiasmack.opentrans.model.OrderPartiesReference
import io.github.matthiasmack.opentrans.model.OrderSummary
import io.github.matthiasmack.opentrans.model.OrderType
import io.github.matthiasmack.opentrans.model.Party
import io.github.matthiasmack.opentrans.model.PartyRole
import io.github.matthiasmack.opentrans.model.Payment
import io.github.matthiasmack.opentrans.model.ProductId
import io.github.matthiasmack.opentrans.model.ProductPriceFix
import io.github.matthiasmack.opentrans.model.XmlContent
import io.github.matthiasmack.opentrans.model.SupplierPid
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Creates a new ORDER.
 *
 * What the DSL fills in for you:
 * - ORDER_PARTIES_REFERENCE from the first id of `buyer { }` and `supplier { }`
 * - the roles "buyer" and "supplier" on those parties
 * - LINE_ITEM_ID as 1, 2, 3, ... unless given
 * - PRICE_LINE_AMOUNT as quantity x price / price quantity, unless given
 * - ORDER_SUMMARY: item count, and the total if every item has a line amount
 *
 * ```
 * val order = order(id = "PO-1", date = LocalDate.now()) {
 *     buyer { id("BUYER-1", "buyer_specific") }
 *     supplier { id("SUPPLIER-1", "supplier_specific") }
 *     currency = "EUR"
 *     item(quantity = 10, unit = "C62") {
 *         supplierPid("SKU-A")
 *         description("Screw M4", lang = "eng")
 *         price("0.15")
 *     }
 * }
 * ```
 */
fun order(id: String, date: Any, block: NewOrderBuilder.() -> Unit): Order =
    NewOrderBuilder(id, date.toDateTime()).apply(block).build()

@OpenTransDsl
class NewOrderBuilder internal constructor(private val id: String, private val date: io.github.matthiasmack.opentrans.model.DateTime) {
    var type: OrderType = OrderType.STANDARD

    /** CONTROL_INFO/GENERATION_DATE; anything date-like. */
    var generationDate: Any? = null

    /** ISO 4217 currency code, e.g. "EUR". */
    var currency: String? = null
    var termsAndConditions: String? = null
    var partialShipmentAllowed: Boolean? = null

    private var delivery: DeliveryDate? = null
    private var buyer: Party? = null
    private var supplier: Party? = null
    private val otherParties = mutableListOf<Party>()
    private var payment: Payment? = null
    private val remarks = NewRemarksBuilder()
    private var udx: XmlContent? = null
    private val items = mutableListOf<NewOrderItemBuilder>()

    fun delivery(from: Any, to: Any = from, type: DeliveryDateType? = null) {
        delivery = DeliveryDate(deliveryStartDate = from.toDateTime(), deliveryEndDate = to.toDateTime(), type = type)
    }

    fun buyer(block: NewPartyBuilder.() -> Unit) {
        check(buyer == null) { "buyer { } may only be given once" }
        buyer = NewPartyBuilder(PartyRole.BUYER).apply(block).build()
    }

    fun supplier(block: NewPartyBuilder.() -> Unit) {
        check(supplier == null) { "supplier { } may only be given once" }
        supplier = NewPartyBuilder(PartyRole.SUPPLIER).apply(block).build()
    }

    /** Any further party, e.g. delivery address or invoice recipient. */
    fun party(vararg roles: PartyRole, block: NewPartyBuilder.() -> Unit) {
        otherParties += NewPartyBuilder(null).apply { role(*roles) }.apply(block).build()
    }

    fun payment(block: NewPaymentBuilder.() -> Unit) {
        payment = NewPaymentBuilder().apply(block).build()
    }

    fun remark(text: String, type: String? = null, lang: String? = null) = remarks.remark(text, type, lang)

    /** HEADER_UDX content as XML text. */
    fun udx(xml: String) {
        udx = io.github.matthiasmack.opentrans.dsl.udx(xml)
    }

    /** HEADER_UDX content, built from typed UDX and XML: `udx { add(ProjectInfoUdx, info) }`. */
    fun udx(block: XmlContentBuilder.() -> Unit) {
        udx = io.github.matthiasmack.opentrans.dsl.udx(block)
    }

    fun item(quantity: Number, unit: String, lineItemId: String? = null, block: NewOrderItemBuilder.() -> Unit) {
        items += NewOrderItemBuilder(lineItemId, quantity.toDecimal(), unit).apply(block)
    }

    fun item(quantity: String, unit: String, lineItemId: String? = null, block: NewOrderItemBuilder.() -> Unit) =
        item(quantity.toDecimal(), unit, lineItemId, block)

    internal fun build(): Order {
        val buyer = buyer ?: throw IncompleteDocumentException("ORDER_INFO/PARTIES/PARTY", "an order needs a buyer { }")
        val supplier = supplier ?: throw IncompleteDocumentException("ORDER_INFO/PARTIES/PARTY", "an order needs a supplier { }")
        if (items.isEmpty()) throw IncompleteDocumentException("ORDER_ITEM_LIST/ORDER_ITEM", "an order needs at least one item(...) { }")

        val builtItems = items.mapIndexed { index, item -> item.build(defaultLineItemId = (index + 1).toString()) }
        val lineAmounts = builtItems.map { it.priceLineAmount }

        return Order(
            type = type,
            orderHeader = OrderHeader(
                controlInfo = generationDate?.let { ControlInfo(generationDate = it.toDateTime()) },
                orderInfo = OrderInfo(
                    orderId = id,
                    orderDate = date,
                    deliveryDate = delivery,
                    parties = listOf(buyer, supplier) + otherParties,
                    orderPartiesReference = OrderPartiesReference(buyerIdref = buyer.partyIds.first(), supplierIdref = supplier.partyIds.first()),
                    currency = currency,
                    payment = payment,
                    termsAndConditions = termsAndConditions,
                    partialShipmentAllowed = partialShipmentAllowed,
                    remarks = remarks.remarks.toList(),
                    headerUdx = udx,
                ),
            ),
            orderItems = builtItems,
            orderSummary = OrderSummary(
                totalItemNum = builtItems.size,
                totalAmount = if (lineAmounts.all { it != null }) lineAmounts.filterNotNull().reduce(BigDecimal::add) else null,
            ),
        )
    }
}

/** ORDER_ITEM */
@OpenTransDsl
class NewOrderItemBuilder internal constructor(
    private val lineItemId: String?,
    private val quantity: BigDecimal,
    private val unit: String,
) {
    private var supplierPid: SupplierPid? = null
    private val buyerPids = mutableListOf<BuyerPid>()
    private val descriptionsShort = mutableListOf<MlString>()
    private val descriptionsLong = mutableListOf<MlString>()
    private var priceFix: ProductPriceFix? = null
    private val remarks = NewRemarksBuilder()
    private var udx: XmlContent? = null

    /** PRICE_LINE_AMOUNT; computed from quantity and price when not set. */
    var lineAmount: BigDecimal? = null

    fun supplierPid(value: String, type: String? = null) {
        supplierPid = SupplierPid(value, type)
    }

    fun buyerPid(value: String, type: String? = null) {
        buyerPids += BuyerPid(value, type)
    }

    /** DESCRIPTION_SHORT, once per language. */
    fun description(text: String, lang: String? = null) {
        descriptionsShort += MlString(text, lang)
    }

    /** DESCRIPTION_LONG, once per language. */
    fun longDescription(text: String, lang: String? = null) {
        descriptionsLong += MlString(text, lang)
    }

    /** Unit price; [per] is the price quantity (e.g. price per 100 pieces). */
    fun price(amount: String, per: Number? = null) {
        priceFix = ProductPriceFix(priceAmount = amount.toDecimal(), priceQuantity = per?.toDecimal())
    }

    fun price(amount: Number, per: Number? = null) {
        priceFix = ProductPriceFix(priceAmount = amount.toDecimal(), priceQuantity = per?.toDecimal())
    }

    fun remark(text: String, type: String? = null, lang: String? = null) = remarks.remark(text, type, lang)

    /** ITEM_UDX content as XML text. */
    fun udx(xml: String) {
        udx = io.github.matthiasmack.opentrans.dsl.udx(xml)
    }

    /** ITEM_UDX content, built from typed UDX and XML: `udx { add(ProjectInfoUdx, info) }`. */
    fun udx(block: XmlContentBuilder.() -> Unit) {
        udx = io.github.matthiasmack.opentrans.dsl.udx(block)
    }

    internal fun build(defaultLineItemId: String) = OrderItem(
        lineItemId = lineItemId ?: defaultLineItemId,
        productId = ProductId(
            supplierPid = supplierPid,
            buyerPids = buyerPids.toList(),
            shortDescriptions = descriptionsShort.toList(),
            longDescriptions = descriptionsLong.toList(),
        ),
        quantity = quantity,
        orderUnit = unit,
        productPriceFix = priceFix,
        priceLineAmount = lineAmount ?: priceFix?.let(::computeLineAmount),
        remarks = remarks.remarks.toList(),
        itemUdx = udx,
    )

    /**
     * quantity x amount / price quantity, rounded half-up to the precision of
     * the unit price, but to at least two decimals (cents).
     * A unit price of "10" for half a unit therefore gives 5.00, not 5.
     */
    private fun computeLineAmount(price: ProductPriceFix): BigDecimal {
        val perUnit = price.priceQuantity ?: BigDecimal.ONE
        val scale = maxOf(price.priceAmount.scale(), 2)
        return quantity.multiply(price.priceAmount).divide(perUnit, scale, RoundingMode.HALF_UP)
    }
}

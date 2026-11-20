package io.github.matthiasmack.opentrans.dsl

import io.github.matthiasmack.opentrans.itscope.ItScopeUdx
import io.github.matthiasmack.opentrans.model.BuyerPid
import io.github.matthiasmack.opentrans.model.ContractInformation
import io.github.matthiasmack.opentrans.model.ControlInfo
import io.github.matthiasmack.opentrans.model.DeliveryDate
import io.github.matthiasmack.opentrans.model.DeliveryDateType
import io.github.matthiasmack.opentrans.model.IncompleteDocumentException
import io.github.matthiasmack.opentrans.model.InternationalPid
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
import io.github.matthiasmack.opentrans.model.ProductComponent
import io.github.matthiasmack.opentrans.model.ProductId
import io.github.matthiasmack.opentrans.model.ProductPriceFix
import io.github.matthiasmack.opentrans.model.ProductStockFix
import io.github.matthiasmack.opentrans.model.ProductType
import io.github.matthiasmack.opentrans.model.RecurringType
import io.github.matthiasmack.opentrans.model.SupplierPid
import io.github.matthiasmack.opentrans.model.TotalRecurringAmount
import io.github.matthiasmack.opentrans.model.XmlContent
import io.github.matthiasmack.opentrans.udx.with
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
 * - ORDER_SUMMARY: item count, the total of all one-off items if each has a
 *   line amount, and per billing unit (MON, ANN, DAY) the total of the
 *   recurring items, i.e. items with `contract(...)` (ITscope)
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
fun order(
    id: String,
    date: Any,
    block: NewOrderBuilder.() -> Unit,
): Order = NewOrderBuilder(id, date.toDateTime()).apply(block).build()

@OpenTransDsl
class NewOrderBuilder internal constructor(
    private val id: String,
    private val date: io.github.matthiasmack.opentrans.model.DateTime,
) {
    var type: OrderType = OrderType.STANDARD

    /** CONTROL_INFO/GENERATION_DATE; anything date-like. */
    var generationDate: Any? = null

    /** ISO 4217 currency code, e.g. "EUR". */
    var currency: String? = null
    var termsAndConditions: String? = null
    var partialShipmentAllowed: Boolean? = null

    /** ITscope: SUBSCRIPTION_TYPE of a subscription order, one of SALES, CHANGE, RENEWAL, CANCELLATION. */
    var subscriptionType: String? = null

    private var delivery: DeliveryDate? = null
    private var buyer: Party? = null
    private var supplier: Party? = null
    private val otherParties = mutableListOf<Party>()
    private var payment: Payment? = null
    private val remarks = NewRemarksBuilder()
    private var udx: XmlContent? = null
    private val items = mutableListOf<NewOrderItemBuilder>()

    fun delivery(
        from: Any,
        to: Any = from,
        type: DeliveryDateType? = null,
    ) {
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
    fun party(
        vararg roles: PartyRole,
        block: NewPartyBuilder.() -> Unit,
    ) {
        otherParties += NewPartyBuilder(null).apply { role(*roles) }.apply(block).build()
    }

    fun payment(block: NewPaymentBuilder.() -> Unit) {
        payment = NewPaymentBuilder().apply(block).build()
    }

    fun remark(
        text: String,
        type: String? = null,
        lang: String? = null,
    ) = remarks.remark(text, type, lang)

    /** Adds XML text to HEADER_UDX; several calls add up. */
    fun udx(xml: String) {
        udx =
            udx.plus(
                io.github.matthiasmack.opentrans.dsl
                    .udx(xml),
            )
    }

    /** Adds typed UDX and XML to HEADER_UDX: `udx { add(ProjectInfoUdx, info) }`; several calls add up. */
    fun udx(block: XmlContentBuilder.() -> Unit) {
        udx =
            udx.plus(
                io.github.matthiasmack.opentrans.dsl
                    .udx(block),
            )
    }

    /** ITscope: ship with the buyer's own delivery note (UDX.DROPSHIPMENT). Agree on this with the distributor first. */
    fun dropshipment(ownDeliveryNote: Boolean = true) {
        udx = (udx ?: XmlContent.EMPTY).with(ItScopeUdx.DROPSHIPMENT, ownDeliveryNote)
    }

    fun item(
        quantity: Number,
        unit: String,
        lineItemId: String? = null,
        block: NewOrderItemBuilder.() -> Unit,
    ) {
        items += NewOrderItemBuilder(lineItemId, quantity.toDecimal(), unit).apply(block)
    }

    fun item(
        quantity: String,
        unit: String,
        lineItemId: String? = null,
        block: NewOrderItemBuilder.() -> Unit,
    ) = item(quantity.toDecimal(), unit, lineItemId, block)

    internal fun build(): Order {
        val buyer = buyer ?: throw IncompleteDocumentException("ORDER_INFO/PARTIES/PARTY", "an order needs a buyer { }")
        val supplier = supplier ?: throw IncompleteDocumentException("ORDER_INFO/PARTIES/PARTY", "an order needs a supplier { }")
        if (items.isEmpty()) throw IncompleteDocumentException("ORDER_ITEM_LIST/ORDER_ITEM", "an order needs at least one item(...) { }")

        val builtItems = items.mapIndexed { index, item -> item.build(defaultLineItemId = (index + 1).toString()) }
        val (recurring, oneOff) = builtItems.partition { it.contractInformation != null }
        val lineAmounts = oneOff.map { it.priceLineAmount }

        return Order(
            type = type,
            orderHeader =
                OrderHeader(
                    controlInfo = generationDate?.let { ControlInfo(generationDate = it.toDateTime()) },
                    orderInfo =
                        OrderInfo(
                            orderId = id,
                            orderDate = date,
                            deliveryDate = delivery,
                            parties = listOf(buyer, supplier) + otherParties,
                            orderPartiesReference =
                                OrderPartiesReference(
                                    buyerIdref = buyer.partyIds.first(),
                                    supplierIdref = supplier.partyIds.first(),
                                ),
                            currency = currency,
                            payment = payment,
                            termsAndConditions = termsAndConditions,
                            partialShipmentAllowed = partialShipmentAllowed,
                            subscriptionType = subscriptionType,
                            remarks = remarks.remarks.toList(),
                            headerUdx = udx,
                        ),
                ),
            orderItems = builtItems,
            orderSummary =
                OrderSummary(
                    totalItemNum = builtItems.size,
                    totalAmount =
                        if (lineAmounts.isNotEmpty() &&
                            lineAmounts.all { it != null }
                        ) {
                            lineAmounts.filterNotNull().reduce(BigDecimal::add)
                        } else {
                            null
                        },
                    totalRecurringAmounts = recurringTotals(recurring),
                ),
        )
    }

    /** ITscope: per billing unit (the ORDER_UNIT MON, ANN or DAY) the sum of the recurring line amounts. */
    private fun recurringTotals(recurring: List<OrderItem>): List<TotalRecurringAmount> =
        recurring
            .groupBy { item -> RecurringType.entries.firstOrNull { it.xml == item.orderUnit } }
            .mapNotNull { (unit, group) ->
                val amounts = group.map { it.priceLineAmount }
                if (unit == null || amounts.any { it == null }) {
                    null
                } else {
                    TotalRecurringAmount(amounts.filterNotNull().reduce(BigDecimal::add), unit)
                }
            }
}

private fun XmlContent?.plus(more: XmlContent): XmlContent = XmlContent((this?.nodes ?: emptyList()) + more.nodes)

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

    /** PRODUCT_TYPE, e.g. ESD, LICENSE or SERVICE for items that need customer data (ITscope). */
    var productType: ProductType? = null

    private var contract: ContractInformation? = null
    private var stock: ProductStockFix? = null
    private val internationalPids = mutableListOf<InternationalPid>()
    private var components: List<ProductComponent>? = null

    /** INTERNATIONAL_PID, e.g. `internationalPid("6676919000", "itscope")` or an EAN with type "ean". */
    fun internationalPid(
        value: String,
        type: String? = null,
    ) {
        internationalPids += InternationalPid(value, type)
    }

    /**
     * Makes this item a bundle: the parts listed in [block] (PRODUCT_COMPONENTS),
     * PRODUCT_TYPE "bundle" on the item and "component" on every part.
     * [partsListVisible] sets ITscope's UDX.BUNDLEPARTSLISTVISIBLE, i.e. whether
     * the parts list is shown on the platform. Bundles inside bundles are not
     * supported, as with ITscope.
     */
    fun bundle(
        partsListVisible: Boolean? = null,
        block: NewBundleBuilder.() -> Unit,
    ) {
        check(components == null) { "bundle { } may only be given once per item" }
        val parts = NewBundleBuilder().apply(block).components.toList()
        if (parts.isEmpty()) {
            throw IncompleteDocumentException(
                "PRODUCT_COMPONENTS/PRODUCT_COMPONENT",
                "bundle { } needs at least one component(...) { }",
            )
        }
        components = parts
        partsListVisible?.let { udx = (udx ?: XmlContent.EMPTY).with(ItScopeUdx.BUNDLE_PARTS_LIST_VISIBLE, it) }
    }

    /**
     * ITscope: a subscription or rental item (CONTRACT_INFORMATION). The item's
     * unit is the billing unit, e.g. `item(quantity = 1, unit = "MON")` with
     * `contract(period = 12, unit = RecurringType.MON, billing = 1)` for twelve
     * months, billed monthly.
     */
    fun contract(
        period: Int? = null,
        unit: RecurringType? = null,
        billing: Int? = null,
        reference: String? = null,
    ) {
        contract =
            ContractInformation(
                subscriptionPeriod = period,
                subscriptionPeriodUnit = unit,
                billingPeriod = billing,
                subscriptionReference = reference,
            )
    }

    /** ITscope: stock at the time of ordering (PRODUCT_STOCK_FIX). */
    fun stock(
        amount: Number,
        status: String? = null,
    ) {
        stock = ProductStockFix(stockAmount = amount.toDecimal(), stockStatus = status)
    }

    fun supplierPid(
        value: String,
        type: String? = null,
    ) {
        supplierPid = SupplierPid(value, type)
    }

    fun buyerPid(
        value: String,
        type: String? = null,
    ) {
        buyerPids += BuyerPid(value, type)
    }

    /** DESCRIPTION_SHORT, once per language. */
    fun description(
        text: String,
        lang: String? = null,
    ) {
        descriptionsShort += MlString(text, lang)
    }

    /** DESCRIPTION_LONG, once per language. */
    fun longDescription(
        text: String,
        lang: String? = null,
    ) {
        descriptionsLong += MlString(text, lang)
    }

    /** Unit price; [per] is the price quantity (e.g. price per 100 pieces). */
    fun price(
        amount: String,
        per: Number? = null,
    ) {
        priceFix = ProductPriceFix(priceAmount = amount.toDecimal(), priceQuantity = per?.toDecimal())
    }

    fun price(
        amount: Number,
        per: Number? = null,
    ) {
        priceFix = ProductPriceFix(priceAmount = amount.toDecimal(), priceQuantity = per?.toDecimal())
    }

    fun remark(
        text: String,
        type: String? = null,
        lang: String? = null,
    ) = remarks.remark(text, type, lang)

    /** Adds XML text to ITEM_UDX; several calls add up. */
    fun udx(xml: String) {
        udx =
            udx.plus(
                io.github.matthiasmack.opentrans.dsl
                    .udx(xml),
            )
    }

    /** Adds typed UDX and XML to ITEM_UDX: `udx { add(ProjectInfoUdx, info) }`; several calls add up. */
    fun udx(block: XmlContentBuilder.() -> Unit) {
        udx =
            udx.plus(
                io.github.matthiasmack.opentrans.dsl
                    .udx(block),
            )
    }

    internal fun build(defaultLineItemId: String) =
        OrderItem(
            lineItemId = lineItemId ?: defaultLineItemId,
            productId =
                ProductId(
                    supplierPid = supplierPid,
                    buyerPids = buyerPids.toList(),
                    shortDescriptions = descriptionsShort.toList(),
                    longDescriptions = descriptionsLong.toList(),
                    internationalPids = internationalPids.toList(),
                    productType = if (components != null) ProductType.BUNDLE else productType,
                ),
            productComponents = components ?: emptyList(),
            quantity = quantity,
            orderUnit = unit,
            productPriceFix = priceFix,
            productStockFix = stock,
            priceLineAmount = lineAmount ?: priceFix?.let(::computeLineAmount),
            contractInformation = contract,
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

/** PRODUCT_COMPONENTS of a bundle item: `bundle { component(1, "C62") { ... } }`. */
@OpenTransDsl
class NewBundleBuilder internal constructor() {
    internal val components = mutableListOf<ProductComponent>()

    fun component(
        quantity: Number,
        unit: String,
        block: NewComponentBuilder.() -> Unit,
    ) {
        components += NewComponentBuilder(quantity.toDecimal(), unit).apply(block).build()
    }

    fun component(
        quantity: String,
        unit: String,
        block: NewComponentBuilder.() -> Unit,
    ) = component(quantity.toDecimal(), unit, block)
}

/** PRODUCT_COMPONENT: one part of a bundle, with PRODUCT_TYPE "component". */
@OpenTransDsl
class NewComponentBuilder internal constructor(
    private val quantity: BigDecimal,
    private val unit: String,
) {
    private var supplierPid: SupplierPid? = null
    private val buyerPids = mutableListOf<BuyerPid>()
    private val internationalPids = mutableListOf<InternationalPid>()
    private val descriptionsShort = mutableListOf<MlString>()
    private val descriptionsLong = mutableListOf<MlString>()
    private var priceFix: ProductPriceFix? = null

    fun supplierPid(
        value: String,
        type: String? = null,
    ) {
        supplierPid = SupplierPid(value, type)
    }

    fun buyerPid(
        value: String,
        type: String? = null,
    ) {
        buyerPids += BuyerPid(value, type)
    }

    /** INTERNATIONAL_PID, e.g. an EAN with type "ean" or the ITscope product id with type "itscope". */
    fun internationalPid(
        value: String,
        type: String? = null,
    ) {
        internationalPids += InternationalPid(value, type)
    }

    /** DESCRIPTION_SHORT, once per language. */
    fun description(
        text: String,
        lang: String? = null,
    ) {
        descriptionsShort += MlString(text, lang)
    }

    /** DESCRIPTION_LONG, once per language. */
    fun longDescription(
        text: String,
        lang: String? = null,
    ) {
        descriptionsLong += MlString(text, lang)
    }

    /** Unit price of the part, if the bundle lists one; [per] is the price quantity. */
    fun price(
        amount: String,
        per: Number? = null,
    ) {
        priceFix = ProductPriceFix(priceAmount = amount.toDecimal(), priceQuantity = per?.toDecimal())
    }

    fun price(
        amount: Number,
        per: Number? = null,
    ) {
        priceFix = ProductPriceFix(priceAmount = amount.toDecimal(), priceQuantity = per?.toDecimal())
    }

    internal fun build() =
        ProductComponent(
            productId =
                ProductId(
                    supplierPid = supplierPid,
                    buyerPids = buyerPids.toList(),
                    internationalPids = internationalPids.toList(),
                    shortDescriptions = descriptionsShort.toList(),
                    longDescriptions = descriptionsLong.toList(),
                    productType = ProductType.COMPONENT,
                ),
            quantity = quantity,
            orderUnit = unit,
            productPriceFix = priceFix,
        )
}

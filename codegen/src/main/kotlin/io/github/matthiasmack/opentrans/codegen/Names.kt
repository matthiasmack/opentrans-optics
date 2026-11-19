package io.github.matthiasmack.opentrans.codegen

/*
 * Naming rules and the override table for names the rules get wrong.
 * Keys are XSD names: an element or named type, "PARENT#n" for the n-th
 * choice inside PARENT (counted from 1), "PARENT#n/FIRST..." for a sequence
 * alternative of that choice, and "ELEMENT+" for a repeated choice
 * alternative. Every override must be used; the generator fails otherwise,
 * so the table cannot silently go stale when the rules change.
 */

object Names {
    /** Class / enum / choice names. */
    private val classOverrides = mapOf(
        "dtMLSTRING" to "MlString",
        "typePARTY_ID" to "PartyId",
        "REMARKS" to "Remark",
        "PAYMENT#1" to "PaymentMethod",
        "TIME_FOR_PAYMENT#1" to "PaymentDue",
        "TIME_FOR_PAYMENT#2" to "PaymentDiscount",
        // bmecat:DATETIME (DATE, TIME, TIMEZONE + type) would clash with the hand-written DateTime.
        "DATETIME" to "DatetimeElement",
        // FEATURE: (FNAME+ | FT_IDREF | FTEMPLATE), (FVALUE+ | VALUE_IDREF+)
        "FEATURE#1" to "FeatureName",
        "FEATURE#2" to "FeatureValue",
        // The type gets the descriptive name, so the FTEMPLATE choice wrapper keeps the element name.
        "typeFTEMPLATE" to "FeatureTemplate",
        // PRODUCT_PRICE: (PRICE_AMOUNT | PRICE_FORMULA), (TAX | TAX_DETAILS+), (TERRITORY+ | AREA_REFS)
        "PRODUCT_PRICE#1" to "ProductPriceValue",
        "PRODUCT_PRICE#2" to "ProductPriceTax",
        "PRODUCT_PRICE#3" to "ProductPriceTerritory",
        "CUSTOMS_TARIFF_NUMBER#1" to "CustomsTariffTerritory",
        // Items: ((PARTIAL_SHIPMENT_ALLOWED?, DELIVERY_DATE?) | DELIVERY_DATE | PARTIAL_DELIVERY_LIST)
        "ORDER_ITEM#1" to "OrderItemDelivery",
        "ORDER_ITEM#1/PARTIAL_SHIPMENT_ALLOWED..." to "SingleDelivery",
        "ORDERRESPONSE_ITEM#1" to "OrderResponseItemDelivery",
        "QUOTATION_ITEM#1" to "QuotationItemDelivery",
        // bmecat:PRODUCT_PRICE_DETAILS: (DATETIME+ | (VALID_START_DATE?, VALID_END_DATE?))
        "PRODUCT_PRICE_DETAILS#1" to "PriceValidity",
        "PRODUCT_PRICE_DETAILS#1/VALID_START_DATE..." to "ValidityPeriod",
        // MIME: ((MIME_SOURCE+, FILE_HASH_VALUE*) | MIME_EMBEDDED+)
        "MIME#1" to "MimeContent",
        "MIME#1/MIME_SOURCE..." to "MimeSources",
        "MIME_EMBEDDED+" to "MimeEmbeddedList",
    )

    /** Hand-written types in the model package; generated names must never collide with them. */
    val reserved = setOf("DateTime", "XmlContent", "XmlNode", "XmlElement", "XmlText")

    /** Property names, keyed by "OwnerClass.defaultName". */
    private val propertyOverrides = mapOf(
        "Payment.paymentMethod" to "method",
        "TimeForPayment.paymentDue" to "due",
        "TimeForPayment.paymentDiscount" to "discount",
        // BMEcat abbreviates the feature fields (FNAME, FUNIT, ...); inside Feature the prefix says nothing.
        "Feature.featureName" to "name",
        "Feature.featureValue" to "value",
        "Feature.funit" to "unit",
        "Feature.forder" to "order",
        "Feature.fdescrs" to "descriptions",
        "Feature.fvalueDetails" to "valueDetails",
        "Feature.fvalueType" to "valueType",
        "ProductPrice.productPriceValue" to "value",
        "ProductPrice.productPriceTax" to "tax",
        "ProductPrice.productPriceTerritory" to "territory",
        "CustomsTariffNumber.customsTariffTerritory" to "territory",
        "OrderItem.orderItemDelivery" to "delivery",
        "OrderResponseItem.orderResponseItemDelivery" to "delivery",
        "QuotationItem.quotationItemDelivery" to "delivery",
        "BmecatProductPriceDetails.priceValidity" to "validity",
        "Mime.mimeContent" to "content",
    )

    /** Property names fixed in every class (plurals the simple rules get wrong). */
    private val globalPropertyOverrides = mapOf(
        "descriptionShorts" to "shortDescriptions",
        "descriptionLongs" to "longDescriptions",
        "timeForPayments" to "timesForPayment",
        "countryOfOrigins" to "countriesOfOrigin",
        "meansOfTransports" to "meansOfTransport",
        "signatureAndVerifications" to "signaturesAndVerifications",
    )

    /**
     * XSD names that run words together, split for class and property names
     * (DISPATCHNOTIFICATION_ID -> dispatchNotificationId). XML names are unaffected.
     */
    private val compoundWords = mapOf(
        "DISPATCHNOTIFICATION" to "DISPATCH_NOTIFICATION",
        "ORDERRESPONSE" to "ORDER_RESPONSE",
        "ORDERCHANGE" to "ORDER_CHANGE",
        "INVOICELIST" to "INVOICE_LIST",
        "RECEIPTACKNOWLEDGEMENT" to "RECEIPT_ACKNOWLEDGEMENT",
        "REMITTANCEADVICE" to "REMITTANCE_ADVICE",
        "DOCEXCHANGE" to "DOC_EXCHANGE",
        "DELIVERYNOTE" to "DELIVERY_NOTE",
        "STARTVALUE" to "START_VALUE",
        "ENDVALUE" to "END_VALUE",
        "INTERVALTYPE" to "INTERVAL_TYPE",
        "INTERVALVALUE" to "INTERVAL_VALUE",
        "CONTENTTYPE" to "CONTENT_TYPE",
        "XMLREPORT" to "XML_REPORT",
        "BASE64BINARY" to "BASE64_BINARY",
        "SHORTNAME" to "SHORT_NAME",
        "SYMBOLREF" to "SYMBOL_REF",
        "BOXNO" to "BOX_NO",
        "ZIPBOX" to "ZIP_BOX",
        "DESCR" to "DESCRIPTION",
    )

    /** Named simple types with large code lists stay strings. */
    val codeLists = setOf("dtPUNIT", "dtCURRENCIES", "dtLANG", "dtCOUNTRIES")

    private val used = mutableSetOf<String>()

    /** Override for a class / enum / choice name, see the key format above. */
    fun classOverride(key: String): String? = classOverrides[key]?.also { used += "class $key" }

    /** Final property name: per-class override, then global override, then the default. */
    fun property(owner: String, defaultName: String): String {
        propertyOverrides["$owner.$defaultName"]?.let { used += "property $owner.$defaultName"; return it }
        globalPropertyOverrides[defaultName]?.let { used += "global $defaultName"; return it }
        return defaultName
    }

    /** Overrides that matched nothing; the generator fails if there are any. */
    fun unusedOverrides(): List<String> =
        classOverrides.keys.map { "class $it" }.filter { it !in used } +
            propertyOverrides.keys.map { "property $it" }.filter { it !in used } +
            globalPropertyOverrides.keys.map { "global $it" }.filter { it !in used }

    /** "ORDER_ITEM" -> "OrderItem", "typePARTY_ID" -> "PartyId", "DISPATCHNOTIFICATION" -> "DispatchNotification". */
    fun className(xsdName: String): String {
        val stripped = xsdName.removePrefix("type").removePrefix("dt").removePrefix("udx").ifEmpty { xsdName }
        return words(stripped).joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }
    }

    /** "ORDER_ID" -> "orderId". */
    fun propertyName(xsdName: String): String = className(xsdName).replaceFirstChar(Char::lowercase)

    /**
     * "remark" -> "remarks", "party" -> "parties", "remarks" -> "remarks", "class" -> "classes",
     * "box" -> "boxes", "name2" -> "names2".
     */
    fun plural(name: String): String {
        val digits = name.takeLastWhile(Char::isDigit)
        if (digits.isNotEmpty() && digits.length < name.length) return plural(name.dropLast(digits.length)) + digits
        return when {
            name.endsWith("ss") -> name + "es"
            name.endsWith("s") -> name
            name.endsWith("y") && name.length > 1 && name[name.length - 2] !in "aeiou" -> name.dropLast(1) + "ies"
            name.endsWith("x") || name.endsWith("ch") || name.endsWith("sh") -> name + "es"
            else -> name + "s"
        }
    }

    /** "standard" -> "STANDARD", "invoice-list" -> "INVOICE_LIST", "04" -> "V_04". */
    fun enumConstant(value: String): String {
        val cleaned = value.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').ifEmpty { "EMPTY" }
        return if (cleaned.first().isDigit()) "V_$cleaned" else cleaned
    }

    private fun words(name: String): List<String> =
        name.split('_', '-', '.').filter { it.isNotEmpty() }
            .flatMap { word -> compoundWords[word.uppercase()]?.split('_') ?: listOf(word) }
}

/**
 * Hands out unique names; a clash gets a number. Comparison ignores case,
 * because `Datetime.class` and `DateTime.class` are the same file on
 * case-insensitive file systems (macOS, Windows).
 */
class NameRegistry(reserved: Set<String> = emptySet()) {
    private val used = reserved.map { it.lowercase() }.toMutableSet()

    fun claim(preferred: String): String {
        if (used.add(preferred.lowercase())) return preferred
        var i = 2
        while (!used.add("$preferred$i".lowercase())) i++
        return "$preferred$i"
    }
}

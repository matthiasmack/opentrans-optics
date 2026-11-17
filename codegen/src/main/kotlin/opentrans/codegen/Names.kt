package opentrans.codegen

/*
 * Naming rules and the override table for names the rules get wrong.
 * Keys are XSD names (element, named type, or "PARENT#n" for the n-th choice
 * inside PARENT, counted from 1).
 */

object Names {
    /** Class / enum / choice names. */
    val classOverrides = mapOf(
        "dtMLSTRING" to "MlString",
        "typePARTY_ID" to "PartyId",
        "REMARKS" to "Remark",
        "PAYMENT#1" to "PaymentMethod",
        "TIME_FOR_PAYMENT#1" to "PaymentDue",
        "TIME_FOR_PAYMENT#2" to "PaymentDiscount",
        // bmecat:DATETIME (DATE, TIME, TIMEZONE + type) would clash with the hand-written DateTime.
        "DATETIME" to "DatetimeElement",
    )

    /** Hand-written types in the model package; generated names must never collide with them. */
    val reserved = setOf("DateTime", "RawXml")

    /** Property names, keyed by "OwnerClass.defaultName". */
    val propertyOverrides = mapOf(
        "Payment.paymentMethod" to "method",
        "TimeForPayment.paymentDue" to "due",
        "TimeForPayment.paymentDiscount" to "discount",
    )

    /** Property names fixed in every class (plurals the simple rules get wrong). */
    val globalPropertyOverrides = mapOf(
        "descriptionShorts" to "shortDescriptions",
        "descriptionLongs" to "longDescriptions",
        "timeForPayments" to "timesForPayment",
        "countryOfOrigins" to "countriesOfOrigin",
        "meansOfTransports" to "meansOfTransport",
        "signatureAndVerifications" to "signaturesAndVerifications",
    )

    /** Named simple types with large code lists stay strings. */
    val codeLists = setOf("dtPUNIT", "dtCURRENCIES", "dtLANG", "dtCOUNTRIES")

    /** "ORDER_ITEM" -> "OrderItem", "typePARTY_ID" -> "PartyId", "dtMLSTRING" -> "Mlstring". */
    fun className(xsdName: String): String {
        val stripped = xsdName.removePrefix("type").removePrefix("dt").removePrefix("udx").ifEmpty { xsdName }
        return words(stripped).joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }
    }

    /** "ORDER_ID" -> "orderId". */
    fun propertyName(xsdName: String): String = className(xsdName).replaceFirstChar(Char::lowercase)

    /** "remark" -> "remarks", "party" -> "parties", "remarks" -> "remarks", "class" -> "classes", "box" -> "boxes". */
    fun plural(name: String): String = when {
        name.endsWith("ss") -> name + "es"
        name.endsWith("s") -> name
        name.endsWith("y") && name.length > 1 && name[name.length - 2] !in "aeiou" -> name.dropLast(1) + "ies"
        name.endsWith("x") || name.endsWith("ch") || name.endsWith("sh") -> name + "es"
        else -> name + "s"
    }

    /** "standard" -> "STANDARD", "invoice-list" -> "INVOICE_LIST", "04" -> "V_04". */
    fun enumConstant(value: String): String {
        val cleaned = value.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_').ifEmpty { "EMPTY" }
        return if (cleaned.first().isDigit()) "V_$cleaned" else cleaned
    }

    private fun words(name: String): List<String> = name.split('_', '-', '.').filter { it.isNotEmpty() }
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

# opentrans-optics

A Kotlin library to read, edit, create and write [openTRANS®](https://www.opentrans.org) 2.1
documents: ORDER, ORDERRESPONSE, ORDERCHANGE, DISPATCHNOTIFICATION, RECEIPTACKNOWLEDGEMENT,
INVOICE, INVOICELIST, REMITTANCEADVICE, RFQ and QUOTATION.

Documents are immutable Kotlin data classes generated from the official XSD. You edit them with
[Arrow optics](https://arrow-kt.io/learn/immutable-data/intro/) and create them with a Kotlin DSL.
XML is validated against the XSD on the way in and on the way out.

> **Status: early and unpublished.** Version `0.1.0-SNAPSHOT`. The API may still change, and
> nothing is on Maven Central yet. For now you build it from source (see [Building](#building)).

## Features

- **All ten document types**, generated from the openTRANS 2.1 / BMEcat 2005 XSD: 191 classes,
  26 enums and 20 sealed choices.
- **Strict in both directions.** Input is validated against the XSD before it is mapped. Output is
  validated before it is returned. XSD facets (lengths, patterns, code lists, ranges) are also
  checked when an object is created, so an invalid value fails where it is made, with the XML path
  in the message.
- **Optics for every class.** Deep, immutable updates such as "all item prices of an order" in
  one line.
- **Builder DSL** for every class, plus a short `order(id, date) { }` DSL that derives
  references, line ids and totals.
- **Follow-up documents** derived from an ORDER: ORDERRESPONSE, DISPATCHNOTIFICATION (full or
  partial) and INVOICE.
- **Typed UDX.** You define your own user-defined extensions in Kotlin. No XSD is needed, and UDX
  you have not defined is kept and written back unchanged.
- **Hardened XML parsing:** no DTDs, no external entities, no entity expansion.

## Modules

| Module            | Contents                                                                    |
|-------------------|-----------------------------------------------------------------------------|
| `opentrans-model` | data classes, Arrow optics, facet checks, `XmlContent`, the UDX API         |
| `opentrans-xml`   | `OpenTransXml`: read, write and validate; bundled XSDs                       |
| `opentrans-dsl`   | generated builders, `order { }`, deriving follow-up documents               |
| `codegen`         | XSD → Kotlin generator, runs at build time and is not a runtime dependency  |

`opentrans-xml` and `opentrans-dsl` both depend on `opentrans-model`.

## Usage

### Read and write

```kotlin
val document = OpenTransXml.read(xml)              // any of the ten types, also inside <OPENTRANS>
when (document) {
    is Order -> println(document.orderHeader.orderInfo.orderId)
    is Invoice -> ...
    else -> ...
}

val order: Order = OpenTransXml.readAs(Path.of("order.xml"))   // fails on another document type
val out: String = OpenTransXml.write(order)                    // validated before it is returned
```

Invalid input or output throws `OpenTransValidationException`, which carries the XSD errors.

### Edit with optics

Every class has a `companion object` with generated optics:

```kotlin
val prices = Order.orderItems.every.productPriceFix.notNull.priceAmount
val discounted = prices.modify(order) { it * BigDecimal("0.9") }

val renumbered = Order.orderHeader.orderInfo.orderId.set(order, "PO-2001")
```

### Create with the DSL

```kotlin
val order = order(id = "PO-1001", date = LocalDate.of(2026, 11, 17)) {
    buyer {
        id("BUYER-1", "buyer_specific")
        address { name("Example Ltd"); city("Musterstadt"); country = "DE" }
    }
    supplier { id("SUPPLIER-1", "supplier_specific") }
    currency = "EUR"

    item(quantity = 100, unit = "C62") {
        supplierPid("SKU-A")
        description("Screw M4", lang = "eng")
        price("0.15")
    }
}
```

Line item ids, party references and amounts are derived. Every generated class also has a full
builder, e.g. `OrderItem { lineItemId = "1"; productId { supplierPid("SKU-1") }; ... }`. A missing
required value fails in `build()` with the XML element name.

### Derive follow-up documents

```kotlin
val response = order.toOrderResponse(date = LocalDate.now())
val dispatch = order.toDispatchNotification(id = "DN-1", date = "2026-11-24", quantities = mapOf("1" to 40))
val invoice  = order.toInvoice(id = "INV-1", date = "2026-11-30", vatRate = "0.19")
```

### Typed UDX

openTRANS leaves `HEADER_UDX` and `ITEM_UDX` open for your own fields. The library ships no UDX
dialect. You describe yours once:

```kotlin
data class Shipping(val carrier: String, val pickup: LocalDate?, val references: List<String>)

object ShippingUdx : UdxType<Shipping>("UDX.ACME.SHIPPING", "urn:acme:udx") {
    private val carrier    = required("CARRIER", Shipping::carrier, string(maxLength = 10))
    private val pickup     = optional("PICKUP", Shipping::pickup, date())
    private val references = list("REFERENCE", Shipping::references, string(), max = 3)
    override fun create(v: Values) = Shipping(v[carrier], v[pickup], v[references])
}
```

Reading, writing, validation and optics follow from that description:

```kotlin
val shipping: Shipping? = order.orderHeader.orderInfo.headerUdx?.get(ShippingUdx)

val udx = Order.orderHeader.orderInfo.headerUdx.udx(ShippingUdx)
val changed = udx.modify(order) { it.copy(carrier = "OTHER") }   // other UDX stay untouched

order(id = "PO-1", date = "2026-11-17") {
    udx { add(ShippingUdx, Shipping("ACME", null, listOf("R-1"))) }
    // ...
}
```

Fields can be `required`, `optional`, `list`, `attribute` or `optionalAttribute`. Simple types are
`string`, `int`, `decimal`, `boolean`, `date`, `dateTime` and `enum`, or your own `SimpleType`;
other `UdxType`s can be nested. A violation throws `UdxViolationException` with the path, e.g.
`UDX.ACME.SHIPPING/CARRIER: at most 10 characters, got 11`. UDX content without a type is an
immutable `XmlContent` tree that you can read and edit directly.

## Building

Requires JDK 25. The Gradle wrapper does the rest.

```bash
./gradlew build
```

The model, the XML reader/writer and the DSL builders are generated from the XSD on every build,
into `build/generated/opentrans/kotlin` of each module. Generated files are never committed or
edited by hand. To change them, change the generator in `codegen` (naming rules and overrides are
in `Names.kt`).

[PLAN.md](PLAN.md) records the design decisions, schema findings and generator rules.

## Scope

- openTRANS 2.1 only.
- No XML signatures. `XML_SIGNATURE` content is kept as an XML tree, not verified.
- No business validation beyond the XSD (e.g. sums, unique line ids).
- Customer-specific dialects that are not valid openTRANS belong in a converter outside this
  library.

## License

[MIT](LICENSE).

The bundled XSDs in `opentrans-xml/src/main/resources/opentrans/xsd` are not covered by the MIT
license. They are redistributed unchanged, with the copyright notices in their file headers:

- `opentrans_2_1.xsd`: © 2000–2009 Fraunhofer IAO and Universität Duisburg-Essen BLI
- `bmecat_2005.xsd`: © 2005 BME e.V. (BMEcat® 2005)
- `xmldsig-core-schema.xsd`, `xmlmime.xsd`: © W3C, under the W3C Software License

openTRANS® is a registered trademark of the Fraunhofer-Gesellschaft e.V., BMEcat® of BME e.V.
This project is not affiliated with or endorsed by either of them.

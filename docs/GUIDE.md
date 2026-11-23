# Guide

Everything the [README](../README.md) leaves out: how the modules fit together, receiving and sending documents, error handling, the full model, your own UDX, ITscope specifics and operations.

## Modules

| Module                   | Contents                                                                                     |
|--------------------------|----------------------------------------------------------------------------------------------|
| `opentrans-optics-model` | data classes, Arrow optics, schema rules checked on creation, `XmlContent`, the UDX API, ITscope's UDX and rules |
| `opentrans-optics-xml`   | `OpenTransXml`: read, write and validate; bundled XSDs                                       |
| `opentrans-optics-dsl`   | generated builders, `order { }`, deriving follow-up documents                               |
| `codegen`                | XSD → Kotlin generator, runs at build time, not a runtime dependency                         |

`opentrans-optics-xml` and `opentrans-optics-dsl` both bring `opentrans-optics-model` with them. The code lives in the packages `io.github.matthiasmack.opentrans.model` (documents and optics), `.xml` (`OpenTransXml`), `.dsl` (builders and derived documents), `.udx` (typed UDX) and `.itscope` (ITscope's UDX and rules). The library is compiled for Java 25, so your project needs JDK 25 as well.

## Receiving documents

`OpenTransXml.read` accepts whatever your interface delivers: a `String`, a `ByteArray`, an `InputStream`, a `Reader` or a file `Path`. It returns one of the ten document types, also when the document is wrapped in `<OPENTRANS>`.

```kotlin
fun receive(body: InputStream) {                  // HTTP request, message queue, upload, ...
    when (val document = OpenTransXml.read(body)) {
        is Order -> importOrder(document)
        is OrderChange -> applyChange(document)
        else -> reject("unexpected ${document::class.simpleName}")
    }
}

fun importOrder(order: Order) {
    val buyer = order.orderHeader.orderInfo.parties.first { PartyRole.BUYER in it.partyRoles }
    for (item in order.orderItems) {
        erp.addLine(item.lineItemId, item.productId.supplierPid?.value, item.quantity, item.orderUnit)
    }
}
```

When you expect a specific type, `OpenTransXml.readAs<Order>(input)` returns it directly and throws a `DocumentTypeException` for any other type.

Bytes are decoded the way the XML says: byte order mark first, then the `encoding` in the XML declaration, UTF-8 if there is neither. ISO-8859-1, windows-1252, UTF-8 and UTF-16 files all arrive with their umlauts intact. A `String` or `Reader` is taken as it is.

## Sending documents

Build a new document with the order DSL or the generated builders, or derive it from an order you received:

```kotlin
val response = order.toOrderResponse(date = LocalDate.now())
val dispatch = order.toDispatchNotification(id = "DN-1", date = "2026-11-24", quantities = mapOf("1" to 40))
val invoice = order.toInvoice(id = "INV-1", date = "2026-11-30", vatRate = "0.19")
```

`toDispatchNotification` ships every item in full unless `quantities` says otherwise (line item id to shipped quantity). `toInvoice` computes line amounts and tax, rounded half-up to cents. Like ITscope's own responses, `toOrderResponse` refers each item back to its order line; pass `referenceLines = false` for a response that has to be plain openTRANS.

Write the result as a `String`, to an `OutputStream`, a `Writer` or a file:

```kotlin
val xml: String = OpenTransXml.write(invoice)
OpenTransXml.write(invoice, Path.of("out/INV-1.xml"))
OpenTransXml.write(invoice, httpResponse.outputStream)
```

Output is always UTF-8 and validated before it is handed out. Streams you pass in are flushed but not closed.

## ITscope and plain openTRANS

The model is generated from ITscope's extended openTRANS schema, a superset of the official one. Every document is validated against ITscope's schema by default; `Profile.OPENTRANS` checks strictly against the official schema, for partners outside ITscope:

```kotlin
OpenTransXml.write(order)                                // ITscope
OpenTransXml.write(order, Profile.OPENTRANS)             // official openTRANS 2.1
OpenTransXml.read(xml, profile = Profile.OPENTRANS)      // reject ITscope-only content on the way in
```

ITscope's extensions are ordinary parts of the model: `contractInformation` (subscriptions and rentals) and `productStockFix` on order items, `totalRecurringAmounts` in the order summary, `orderReference` on order response items, `ProductType.ESD` and `SPAREPART`, serial numbers with a type such as `imei`, optional quotation items, more order types (`OrderType.EXPRESS_SATURDAY_10`, `PICKUP`, ...). The order DSL has `contract(...)`, `stock(...)`, `productType`, `dropshipment()`, `subscriptionType` and bundles:

```kotlin
item(quantity = 1, unit = "C62") {
    supplierPid(value = "SAMPLE_SUPPLIER_ID", type = "supplier_specific")
    price(amount = "510.00")
    bundle(partsListVisible = true) {
        component(quantity = 1, unit = "C62") { supplierPid(value = "13113721000") }
        component(quantity = 1, unit = "C62") { supplierPid(value = "1000001211000") }
    }
}
```

Recurring items (those with a `contract`) are left out of `TOTAL_AMOUNT` and summed per billing unit in `TOTAL_RECURRING_AMOUNT`, as ITscope expects.

Before an order goes to ITscope, `ItScopeRules.violations(order)` lists what ITscope's order API checks beyond the schema: an ORDER_ID of at most 18 characters, complete buyer and delivery addresses (name, street, ZIP code, country) and a supplier article number on every item. An empty list means ITscope will accept the order on these points. ITscope's own UDX are ready-made in `ItScopeUdx` (`DROPSHIPMENT`, `BUNDLE_PARTS_LIST_VISIBLE`), with `order.dropshipment` and `item.bundlePartsListVisible` to read them.

## Handling errors

Every problem with a document or its data is an `OpenTransException` (package `.model`), so one `catch` at the edge of your system covers all of them. The subclasses tell you what went wrong:

| Exception                      | When                                                                                         | What it tells you                                                    |
|--------------------------------|----------------------------------------------------------------------------------------------|----------------------------------------------------------------------|
| `OpenTransValidationException` | reading or writing XML that is not well-formed or not valid, contains a DOCTYPE or has an unknown encoding | `errors`: every problem found, with line and column     |
| `FacetViolationException`      | creating or changing a value the schema forbids                                              | the XML path and the rule, e.g. `ORDER_INFO/ORDER_ID: at most 250 characters, got 251` |
| `IncompleteDocumentException`  | a required value is missing: in a builder, the order DSL, or when deriving a document        | `element`: the XML path of what is missing, e.g. `ORDER_INFO/CURRENCY` |
| `DocumentTypeException`        | `readAs` finds another document type                                                         | `expected` and `found`                                               |
| `UdxViolationException`        | reading or writing a typed UDX that breaks its description                                   | the path inside the UDX and the rule                                 |

```kotlin
try {
    importOrder(OpenTransXml.readAs<Order>(body))
} catch (e: OpenTransValidationException) {
    replyToSender(e.errors)                       // the document itself is broken
} catch (e: OpenTransException) {
    log.warn("rejected: ${e.message}")            // wrong type, missing data, ...
}
```

Using the API the wrong way, such as giving `buyer { }` twice, is a programming error and throws a plain `IllegalStateException` or `IllegalArgumentException` instead. The validator's messages follow the JVM locale.

## The full model

The order DSL covers the everyday order. For everything else, each of the 196 classes has a generated builder, from `Order { }` and `Invoice { }` down to `ProductPriceFix { }`; your IDE's completion shows what is possible at each level. Every class also has optics in its companion object, e.g. `Order.orderHeader.orderInfo.orderId`, with `every` for lists and `notNull` for optional elements.

Names follow the XML, so you find them from the specification:

- An element becomes its name in camel case: `ORDER_ID` → `orderId`, `DISPATCHNOTIFICATION` → `DispatchNotification`.
- Repeated elements are lists with plural names. List wrappers such as `ORDER_ITEM_LIST` are left out, so `order.orderItems` is the list itself.
- "Exactly one of" in the XSD is a sealed type named after what it holds, e.g. `Payment.method` (`Card`, `Accounts`, `Debit`, ...) or `OrderItem.delivery`.
- BMEcat elements whose names clash with openTRANS ones get a `Bmecat` prefix (`BmecatProductPriceDetails`).

## Your own UDX

`HEADER_UDX` and `ITEM_UDX` hold the fields the standard does not have. Describe yours once, in plain Kotlin:

```kotlin
data class CostCenter(val id: String, val budget: BigDecimal?)

object CostCenterUdx : UdxType<CostCenter>("UDX.ACME.COSTCENTER", "urn:acme:udx") {
    private val id = required(name = "ID", get = CostCenter::id, type = string(pattern = Regex("CC-\\d{4}")))
    private val budget = optional(name = "BUDGET", get = CostCenter::budget, type = decimal(min = BigDecimal.ZERO))

    override fun create(v: Values) = CostCenter(v[id], v[budget])
}
```

Reading, writing, validation and optics follow from that description:

```kotlin
val costCenter: CostCenter? = order.orderItems.first().itemUdx?.get(CostCenterUdx)

val costCenters = Order.orderItems.every.itemUdx.udx(CostCenterUdx)    // across every item
val moved = costCenters.modify(order) { it.copy(id = "CC-2000") }

order(id = "PO-1002", date = LocalDate.now()) {
    // buyer, supplier, ...
    item(quantity = 1, unit = "C62") {
        supplierPid(value = "SKU-A")
        udx { add(type = CostCenterUdx, value = CostCenter(id = "CC-1000", budget = BigDecimal("5000"))) }
    }
}
```

A violation throws `UdxViolationException` with the path, e.g. `UDX.ACME.COSTCENTER/ID: 'X-1' does not match CC-\d{4}`, on read and on write. Unknown elements, missing required values and too many repetitions are caught the same way. One UDX block can hold extensions from several parties; each type reads and writes only its own element and everything else travels through untouched.

Fields are declared with `required`, `optional`, `list` (with `min` and `max`), `attribute`, `optionalAttribute` and `text` (the element's own value, next to attributes). Simple types are `string` (length, pattern), `int` and `decimal` (range), `boolean`, `date`, `dateTime` and `enum`, or a `SimpleType` of your own; outside a declaration they are available as `SimpleTypes.boolean()` etc. Another `UdxType` can be the type of a field, for nested elements. A UDX that holds just a value takes one line: `UdxType.value(localName = "UDX.DROPSHIPMENT", type = SimpleTypes.boolean())`.

On UDX content, `content[Type]` reads one value, `getAll` reads all of them, and `with` / `withAll` set them in place. The optic `udx(Type)` works on any UDX property. UDX content without a type is an immutable `XmlContent` tree of `XmlElement`s and `XmlText`, which you can read and build directly.

## Operations

- `OpenTransXml` is thread-safe, and documents are immutable, so share them freely.
- The schemas ship with the library. Nothing is ever loaded from the network; DTDs and external entities are refused.
- A document is held in memory while it is validated and mapped. That is no problem for the sizes openTRANS documents have in practice.

## Building

Requires JDK 25; the Gradle wrapper does the rest.

```bash
./gradlew build
```

The model, the XML reader/writer and the DSL builders are generated from the XSD on every build, into `build/generated/opentrans/kotlin` of each module. Generated files are never committed or edited by hand; to change them, change the generator in `codegen` (naming rules and overrides are in `Names.kt`). `./gradlew ktlintFormat` formats the hand-written code.

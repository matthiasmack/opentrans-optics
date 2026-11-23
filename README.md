# opentrans-optics

A Kotlin library for openTRANS 2.1 business documents: orders, order responses, dispatch notifications, invoices and the rest of the ten document types. Read them from XML, change anything deep inside with one line of optics, build new ones with a type-safe DSL, and write them back, validated against the schema in both directions. It speaks the extended openTRANS that [ITscope](https://www.itscope.com/) uses (subscriptions, stock levels, ESD, bundles, references back to the order line) and plain openTRANS 2.1 for every other partner.

> **Status:** early, version `0.1.0-SNAPSHOT`, not on Maven Central yet. The API may still change.

## Stack

- **Kotlin 2.4.21 / JDK 25**, immutable data classes, nothing mutable anywhere
- **Arrow Optics 2.3.0**: optics for every class, generated via KSP
- **Code generated from the XSD at build time** (Xerces + KotlinPoet): 196 classes, the StAX reader and writer, and a builder for every class

## Quick start

1. **Include it**

   Not published yet, so check it out next to your project and include it as a composite build:

   ```kotlin
   // settings.gradle.kts
   includeBuild("../opentrans-optics")

   // build.gradle.kts
   dependencies {
       implementation("io.github.matthiasmack:opentrans-optics-xml:0.1.0-SNAPSHOT")
       implementation("io.github.matthiasmack:opentrans-optics-dsl:0.1.0-SNAPSHOT")
   }
   ```

2. **Read an order**

   Strings, bytes, streams and files are accepted, decoded the way the XML declares its encoding. Anything invalid is rejected with the XSD errors before your code ever sees it. By default a document is checked against ITscope's schema; with `Profile.OPENTRANS` it has to be strictly official openTRANS 2.1:

   ```kotlin
   val order: Order = OpenTransXml.readAs(body)
   val strict: Order = OpenTransXml.readAs(body, Profile.OPENTRANS)   // strictly official openTRANS 2.1 only

   when (val document = OpenTransXml.read(body)) {    // when it could be any of the ten types
       is Order -> importOrder(document)
       is OrderResponse -> confirm(document)
       is Invoice -> book(document)
       else -> reject(document)
   }
   ```

3. **Build an order**

   Write the order the way you think about it. The DSL is a type-safe builder: your IDE completes every block and offers only what fits there, and a misplaced block (a `buyer { }` inside an `item { }`) or a wrong type does not even compile. References, roles, line ids, line amounts and totals are filled in, recurring items get their own total. What the compiler cannot know, a forgotten required value or an ID that is too long, fails the moment the order is built, naming the XML element:

   ```kotlin
   val order = order(id = "PO-1001", date = LocalDate.now()) {
       buyer {
           id(value = "12345", type = "supplier_specific")
           address {
               name(value = "Example Ltd")
               street(value = "Musterweg 1")
               zip(value = "10000")
               city(value = "Musterstadt")
               country = "DE"
           }
       }
       supplier { id(value = "10000735", type = "buyer_specific") }
       currency = "EUR"
       dropshipment()                                                 // ship with your own delivery note

       item(quantity = 1, unit = "MON") {
           supplierPid(value = "123002", type = "supplier_specific")
           description(text = "iPad Pro, rented")
           price(amount = "49.99")
           contract(period = 12, unit = RecurringType.MON, billing = 1) // 12 months, billed monthly
       }
       item(quantity = 3, unit = "C62") {
           supplierPid(value = "SKU-4711", type = "supplier_specific")
           description(text = "USB-C dock")
           price(amount = "189.00")
       }
   }
   ```

4. **Change it**

   An optic is a path into the document, with lenses, prisms and traversals of functional programming. Write it once, then read, set or transform whatever it points to, through lists, optional elements and choices. Documents are immutable: a change is a copy-on-write that returns a new document and shares everything it did not touch, so the original document stays as it was:

   ```kotlin
   val itemPrices = Order.orderItems.every.productPriceFix.notNull.priceAmount

   val discounted = itemPrices.modify(order) { it * BigDecimal("0.9") } // 10 % off every item, as a new order
   ```

5. **Answer and write it**

   Derive the follow-up documents from the order and write them, validated against ITscope's schema or, for every other partner, the official one:

   ```kotlin
   val response = discounted.toOrderResponse(date = LocalDate.now())
   val invoice = discounted.toInvoice(id = "INV-1001", date = LocalDate.now(), vatRate = "0.19")

   val xml = OpenTransXml.write(invoice)                         // validated against ITscope's schema
   val plain = OpenTransXml.write(invoice, Profile.OPENTRANS)    // or strictly official openTRANS 2.1
   ```

The [guide](docs/GUIDE.md) covers the rest: error handling, the full model, your own UDX, ITscope's rules, operations.

## What you get

- **Valid by construction.** Every rule of the schema is checked when an object is created, not when it is written: `ORDER_INFO/ORDER_ID: at most 250 characters, got 251`.
- **Types that tell the truth, thanks to Kotlin.** Required is non-null, optional is nullable, lists are never null, and "exactly one of" is a sealed type the compiler makes you handle completely.
- **ITscope, typed.** `item.contractInformation`, `item.productStockFix`, `ProductType.ESD`, bundles, `order.dropshipment`, `ItScopeRules.violations(order)` before you send.
- **Your own UDX, first class.** Describe your extensions once in Kotlin, no XSD needed, and they are validated, reachable with optics and part of the DSL. Extensions you have not described pass through untouched.
- **One catch for everything.** Every problem with a document or its data is an `OpenTransException`, with the XML path in the message.

## Architecture

The model is generated, not written. I started with a hand-written model for a subset of ORDER to find out which schema patterns matter, then replaced it with a generator that reads the XSD through Xerces and emits the data classes, the reader, the writer and a builder for every class with KotlinPoet. They cannot drift apart, and every element of every document type is covered, not a hand-picked subset. Kotlin names are made readable (`DISPATCHNOTIFICATION_ID` becomes `dispatchNotificationId`, choices are named after what they hold), the XML names never change. Where the rules get a name wrong, an override table fixes it, and the generator fails on overrides that no longer match anything, so the table cannot rot.

XML goes through generated StAX code instead of a serialization library. openTRANS has choices with repeated alternatives, free XML content in the UDX elements, case-insensitive booleans and dates that may be just `2009-05`. Each of those would have needed a custom serializer; generated StAX code handles them directly and keeps the model free of annotations. Validation is strict both ways: documents are checked against the XSD on read, so the reader can rely on element order, and again on write, so nothing invalid reaches a partner. The single-value rules (lengths, patterns, code lists, ranges) are generated into the constructors on top, so a bad value fails where it is created, with the XML path in the message.

ITscope support went through two versions. First the model came from the official schema, and 4 of ITscope's 12 sample documents failed on its extensions, among them real order responses. Translating the extensions into UDX on the way in would have worked for the added elements, but not for longer names or extra codes without lying in the standard fields. Once ITscope published its complete schema, the decision was easy: it is a superset of the official one, every official sample validates against it, so the model is now generated from ITscope's schema and the official one ships as a validation profile. The trade-off: limits ITscope relaxes (100-character names, an optional payment method) are relaxed in the model too, and only the `OPENTRANS` profile enforces the official ones, on read and write.

UDX are the extension point. The library cannot know which fields its users keep in `HEADER_UDX` and `ITEM_UDX`, and those fields rarely come with a schema, so a UDX type is described once in plain Kotlin, without reflection or annotations, and reading, writing, validation and optics follow from that. The only UDX shipped are the ones ITscope documents. Smaller decisions in the same spirit: `BigDecimal` for every amount, partial dates kept exactly as written, enums only where the standard is closed, bytes decoded as the XML declares them, no DTDs or external entities, and no business rules beyond the schema, because matching totals depend on your processes, not on the format.

## Development

Built with [Claude Code](https://claude.com/claude-code) in an agentic workflow — see [CLAUDE.md](CLAUDE.md) for the operational docs it works from (layout, generator rules, schema updates). `./gradlew build` generates the code, checks the formatting with ktlint and runs the tests.

## License

[MIT](LICENSE). The bundled XSDs keep their own terms and are redistributed unchanged with their copyright notices: openTRANS® 2.1 © 2000–2009 Fraunhofer IAO and Universität Duisburg-Essen BLI, BMEcat® 2005 © 2005 BME e.V., ITscope's extended versions of both (download of 2026-11), and xmldsig/xmlmime © W3C under the W3C Software License. openTRANS® is a registered trademark of the Fraunhofer-Gesellschaft e.V., BMEcat® of BME e.V. This project is not affiliated with or endorsed by either of them.

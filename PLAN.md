# opentrans-optics – plan

Kotlin library to read, edit, create and write openTRANS 2.1 documents.
Input and output are XML strings or files; inside, documents are immutable
Kotlin data classes, edited with Arrow optics and created with a Kotlin DSL.

## Decisions

- openTRANS 2.1 only, all ten document types (in the end).
- Strict: XSD validation on read **and** on write. Customer dialects are
  handled by a separate converter outside this library.
- Round trip only has to be semantically equal, not byte-identical.
- No signatures. `HEADER_UDX` / `ITEM_UDX` are carried as raw XML first and
  will get typed access later.
- **UDX are a user extension point.** The library ships no UDX dialect; its
  users define their own types. UDX in the wild rarely comes with an XSD, so
  none is required: a UDX type is described once in Kotlin (explicitly, no
  reflection, annotations or compiler plugin), and reading, writing,
  validation and optics follow from that description. UDX without a
  definition (e.g. from another partner) is kept as a generic XML tree and
  written back unchanged. Exporting an XSD from a definition is an idea,
  not a planned step.
- Kotlin-only API, DSL wherever it helps. JDK 25.
- Model is generated from the XSD (KotlinPoet) after the spike.
- `BigDecimal` for amounts and quantities. Dates are a `DateTime` value class
  holding the lexical value, with java.time conversions (see findings).
- XML is read and written with the JDK's StAX API (`javax.xml.stream`), no
  serialization library. The model stays free of XML annotations.
- XML parser hardened against XXE (no DTDs, no external entities).
- Out of scope for now: business validation (sums, unique line ids),
  open enums, Nexus publishing.

## Modules

| module            | content                                                    |
|-------------------|------------------------------------------------------------|
| `opentrans-model` | data classes, serialization annotations, Arrow optics      |
| `opentrans-xml`   | read / write / validate, bundled XSDs                      |
| `opentrans-dsl`   | builder DSL for new documents, deriving follow-up documents |
| `codegen`         | (after the spike) XSD -> Kotlin generator, build time only |

## Spike: ORDER only

Goal: prove that every schema pattern survives read -> edit -> write and
learn what the generator has to produce.

1. **Model by hand** for a subset of ORDER that covers every pattern:
   sequence, optional, repeated, `xsd:choice` (PAYMENT), attributes
   (`type`, `lang`), two namespaces (openTRANS + BMEcat), multi-language
   strings, decimals, date-times, raw UDX.
2. **XML**: read and write with xmlutil, XSD validation both ways, hardened
   parser, round-trip test (`parse -> write -> parse` equals, output valid).
3. **Optics**: edit deep paths with Arrow, e.g. all item prices of an order.
4. **DSL**: create a fresh ORDER, written output validates against the XSD.
5. **Review**: what worked, what the generator must emit, open problems.

## Spike findings

Step 1 (model):

- **Partial dates.** `bmecat:dtDATETIME` is a string pattern that allows
  "2009", "2009-05", "2009-05-13" and full date-times with optional offset
  (e.g. `CARD_EXPIRATION_DATE` = "2009-05"). No single java.time type fits,
  so `DateTime` keeps the lexical value and offers conversions.
- **Open "type" attributes.** Most `type` attributes are patterns like
  `duns|iln|gln|...|\w{1,250}`, i.e. any word is valid. They are `String`.
  Only true enumerations (`PARTY_ROLE`, ORDER `type`, DELIVERY_DATE `type`)
  become Kotlin enums.
- **Large code lists.** Currencies, units (`dtPUNIT`), languages and countries
  are kept as `String` for now; the generator can turn them into enums or
  value classes with constants later.
- **Booleans** (`dtBOOLEAN`) accept any case ("TRUE", "false"), so the reader
  must parse case-insensitively.
- **Choices with repeated alternatives.** PAYMENT's choice contains a
  repeatable ACCOUNT alternative; it becomes a variant holding a list.
  Nested choices (TIME_FOR_PAYMENT) work the same way.
- **Why StAX instead of xmlutil.** Choices with repeated alternatives,
  `xsd:anyType` content (UDX), case-insensitive booleans and partial dates
  would all need custom serializers. Generated StAX code gives full control
  over element order and namespaces and keeps the model annotation-free.
- **Arrow optics** work for every pattern without extra code: lens chains,
  `every` over lists, `notNull` over optional fields and prisms into sealed
  choices (see `ModelOpticsTest`).

Step 2 (XML):

- **Round trip works** for the whole subset: `read(sample) == spikeOrder`,
  `read(write(order)) == order`, and writing is stable (`OrderXmlTest`).
- **Validation before mapping simplifies the reader.** Element order and
  cardinality are guaranteed by the XSD, so each read function is a plain
  `when` over child elements. Valid but not yet modelled elements raise
  `UnsupportedElementException` with element and parent name (e.g. the
  official ORDER sample stops at SOURCING_INFO).
- **Writer** is a small indenting serializer (`XmlOut`), no StAX writer
  needed; output is validated before it is returned.
- **UDX** is captured as XML text without indentation whitespace. Namespace
  prefixes declared further up (e.g. `bmecat:`) are re-declared inside the
  captured content, so it stays self-contained when written elsewhere.
  (Superseded in G9: UDX is now an `XmlContent` tree.)
- **Hardening findings.**
  - The schema must be loaded with external access disabled; imports are
    served from the classpath. `xmldsig-core-schema.xsd` has a DOCTYPE that
    references a W3C DTD; its entities are unused, so the resolver answers
    the DTD with an empty document instead of downloading it. The bundled
    files stay unchanged.
  - The JDK validator accepts the `disallow-doctype-decl` feature but ignores
    it for stream input: an entity expansion document passed validation.
    Documents with a DOCTYPE are therefore refused by a StAX pre-check before
    validation. External entities were already blocked (`accessExternalDTD`).
- **Error messages from the XSD validator follow the JVM locale** (German on
  a German machine). Tests only assert on locale-independent parts.
- `DateTime` now checks the XSD pattern on construction, so an invalid value
  fails where it is created, not only when the document is written.
  Generating such checks from all XSD facets (pattern, min/max length) is a
  generator task.

Step 3 (optics): covered by `ModelOpticsTest` and the read-edit-write test
in `OrderXmlTest`; no separate code needed.

Step 4 (DSL):

- `order(id, date) { ... }` rebuilds the spike order exactly
  (`OrderDslTest`), and a minimal DSL order is valid openTRANS.
- The DSL derives what can be derived: ORDER_PARTIES_REFERENCE from the
  first id of `buyer { }` / `supplier { }`, their roles, line item ids,
  PRICE_LINE_AMOUNT and ORDER_SUMMARY.
- Date-like and number-like parameters accept plain values (`LocalDate`,
  `YearMonth`, `"2026-11-17"`, `100`, `2.5`, `"0.15"`). Doubles go through
  their decimal string, so `0.1` stays `0.1`.
- **Rounding of computed line amounts** (confirmed 2026-11-17): commercial
  rounding, half-up, to the scale of the unit price but at least two
  decimals. A first version rounded to the unit price scale only, which
  turned 2.5 into 3 for an integer price.
- **BigDecimal equality is scale-sensitive** (`31.00 != 31.000`). Tests
  compare whole documents, so computed values need a deterministic scale.
- Missing or contradicting input fails at build time with a message naming
  the DSL block (`"an order needs a supplier { }"`).

## Generator

### Schema inventory

Measured with `./gradlew :codegen:inventory` and `:codegen:collisions`
(walk from the ten document roots through Xerces' XSModel):

| construct                                   | count |
|---------------------------------------------|-------|
| reachable element declarations              | 482   |
| … with simple type                          | 210   |
| complex types (element / simple / mixed content) | 164 / 80 / 10 |
| anonymous / named complex types             | 225 / 29 (19 of them xmldsig) |
| attributes (required)                       | 123 (29) |
| element uses: required / optional / repeated | 279 / 410 / 183 |
| xsd:choice                                  | 25 (none nested in another choice) |
| … optional / with repeated alternative / with sequence alternative | 12 / 10 / 5 |
| repeated groups (sequence with maxOccurs>1) | ADDRESS, EMAILS (+ xmldsig) |
| wrapper elements (one repeated child, no attributes) | 26 |
| xsd:any / mixed content                     | only UDX and xmldsig |
| distinct patterns                           | 12 |
| large code lists                            | dtPUNIT 1095, dtCURRENCIES 157 |
| anonymous enumerations on elements          | 22 (PARTY_ROLE, INVOICE_TYPE, …) |
| local names existing in both namespaces     | 17 overall, only 3 reachable: MIME, MIME_INFO, MIME_PURPOSE |
| BMEcat global elements reachable / total    | 177 / 440 (the catalog part of BMEcat is not used) |

### Mapping rules

Input is the XSModel, so element references, imports and derivation are
already resolved.

- **Classes.** One data class per global element with complex content
  (anonymous type), named after the element: `ORDER_ITEM` -> `OrderItem`.
  Named complex types become one shared class named after the type:
  `typePARTY_ID` -> `PartyId`, `dtMLSTRING` -> `MlString`. Elements that only
  restrict a named simple-content type (e.g. `bmecat:NAME` restricts
  dtMLSTRING with a max length) reuse the base class.
- **Name clashes.** openTRANS elements keep the plain name; a BMEcat element
  with the same local name that is reachable as well gets a `Bmecat` prefix.
- **Properties.** camelCase of the element name. Required -> non-null,
  optional -> nullable with default `null`, repeated -> `List` with default
  `emptyList()`, plural name (simple English rules plus an override table).
  Attributes become properties as well; `version` is not modelled.
- **Simple content.** Element with simple type and no attributes -> property
  of the Kotlin type: string -> `String`, decimal -> `BigDecimal`, integer /
  count -> `Int`, dtBOOLEAN -> `Boolean`, dtDATETIME -> `DateTime`.
  Simple content with attributes -> small class with `value` + attributes.
- **Enumerations.** Closed enumerations (22 anonymous + small named ones)
  -> Kotlin enum with an `xml` value. Patterns ending in `|\w{1,n}` are open
  -> `String`. dtPUNIT, dtCURRENCIES, dtLANG, dtCOUNTRIES stay `String`
  for now.
- **Wrappers.** An element whose content is exactly one repeated child and
  that has no attributes is flattened into a `List` property of its parent
  (26 cases, e.g. `ORDER_ITEM_LIST` -> `items`, `PARTIES` -> `parties`).
- **Choices.** Each xsd:choice -> sealed interface named `<Parent><Choice>`
  unless the override table names it (PAYMENT -> `PaymentMethod`).
  Alternatives: element with complex content -> that element's class
  implements the interface; simple element -> wrapper variant (`Debit(value)`);
  repeated element -> list variant (`Accounts(list)`); sequence -> variant
  class with the sequence's properties. Optional choice -> nullable property.
- **Repeated sequences** (ADDRESS: EMAIL + PUBLIC_KEY*) -> list of a small
  class for one repetition.
- **Raw content.** `xsd:anyType` / `xsd:any` (UDX) and the whole xmldsig
  subtree (`XML_SIGNATURE`) -> `XmlContent` (XML tree, see G9).
- **Invariants.** `init { require(...) }` for repeated elements with
  minOccurs >= 1. Facet checks (pattern, length) come in a later step.
- **Optics.** Every generated class and sealed interface gets `@optics` and a
  `companion object`; Arrow's KSP plugin does the rest.
- **Reader / writer.** Generated in the shape of the spike's hand-written
  code: one read function per class (`when` over child QNames), one write
  function per class following the xsd:sequence order.
- **Override table** in the generator for names the rules get wrong
  (plurals, choice names, a few property names). Small and explicit.
  An override that matches nothing fails the generator.
- **Compound words.** XSD names that run words together are split for
  Kotlin names only: `DISPATCHNOTIFICATION` -> `DispatchNotification`,
  `DESCR` -> `Description`. XML names are unaffected.

### Generator steps

G1. Codegen module, inventory, this spec. ✔
G2. Generate the model for all ten document types; compiles with Arrow KSP. ✔
    - `./gradlew :opentrans-model:generateModel` runs automatically before
      compilation: 191 classes, 26 enums, 20 sealed choices (about 4,500
      lines), plus 243 files of Arrow optics. Temporary package
      `opentrans.generated` until G5 replaces the spike model.
    - Additional rule found: a required attribute with exactly one allowed
      value (`version="2.1"`) is treated as fixed and not modelled.
    - Repeated choice alternatives are one class per element, shared by all
      choices that use it (e.g. `Territories`).
    - Naming work for the override table: done in G8.
G3. Generate the reader. ✔
    - `./gradlew :opentrans-xml:generateReader` emits one `XmlIn.readX()` per
      class (about 5,400 lines) from the same IR as the model, plus
      `readDocument()`, which dispatches on the root element and returns the
      new sealed `OpenTransDocument` (implemented by the ten document classes).
    - All four official samples (ORDER, INVOICE, DISPATCHNOTIFICATION,
      INVOICELIST) and the spike sample are read completely, without a single
      unsupported element (`GeneratedReaderTest`).
    - Choices, repeated choice alternatives (`Accounts`), sequence alternatives
      and repeated sequences (ADDRESS: EMAIL + PUBLIC_KEY*) are read with the
      locals-and-`when` pattern from the spike; a repeated sequence starts a
      new entry at its first element.
    - Pitfall found: attributes must be read before the text content, because
      reading text moves the cursor off the start tag.
    - Still open for G5: the optional `<OPENTRANS>` wrapper element around a
      document.
G4. Generate the writer. ✔
    - `generateXml` (replaces `generateReader`) now emits reader and writer:
      one `XmlOut.writeX(tag, value)` per class plus `writeDocument()`.
      The element name is a parameter because one class can be the content of
      several elements (`PartyId` for PARTY_ID, BUYER_IDREF, ...).
    - Every sample survives read -> write -> XSD validation -> read with an
      equal result, and writing is stable (`GeneratedRoundTripTest`).
    - Pitfall found: `xmime:contentType` on MIME_DATA is a namespace-qualified
      attribute. The IR now records attribute namespaces; the writer declares
      the namespace on the element that uses it.
G5. Replace the spike's hand-written model/reader/writer; every official
    sample reads, writes and validates. ✔
    - Generated model in `opentrans.model`, generated reader/writer in
      `opentrans.xml` (internal). Hand-written code left: `DateTime`,
      `RawXml` (replaced by `XmlContent` in G9), `XmlIn`/`XmlOut` (cursor and serializer helpers),
      `OpenTransSchema` (validation and hardening), `OpenTransXml` (API),
      and the DSL.
    - Public API: `OpenTransXml.read(xml)` returns any `OpenTransDocument`
      (also when wrapped in `<OPENTRANS>`), `readAs<Order>(xml)` checks the
      type, `write(document)` validates before returning.
    - The DSL builds the generated classes; its tests are unchanged in
      substance and still rebuild the spike order exactly.
    - Pitfall found: the generated class `Datetime` (bmecat:DATETIME) and the
      hand-written `DateTime` compile to the same `.class` file on
      case-insensitive file systems. The name registry now compares names
      case-insensitively and reserves the hand-written names; DATETIME is
      named `DatetimeElement`.
G6. DSL on top of the generated model. ✔
    - Generated (`generateDsl`, about 9,800 lines): a builder per class and
      `X { ... }` via `operator fun X.Companion.invoke`, for all ten document
      types. Per property a `var` or mutable list, nested blocks for classes,
      parameter shortcuts for small value classes (`partyId("B-1", "gln")`),
      date and decimal conversions, one helper per choice alternative
      (`card(...)`, `debit(true)`). Missing required values fail in `build()`
      with the XML element name.
    - Hand-written on top: `order(id, date) { }` (derives references, roles,
      line ids, line amounts, totals; its builders are now `New*Builder`) and
      `Derive.kt`: `Order.toOrderResponse`, `toDispatchNotification`
      (optionally partial, per line item) and `toInvoice` (one VAT rate, tax
      rounded half-up to cents, issuer/recipient from the order).
    - Tests: the generated DSL and the hand-written one build the same order;
      every derived document is written, validated and read back unchanged.
G7. Facet checks generated into the model. ✔
    - 333 checks in the generated init blocks: lengths (222), patterns (35),
      allowed values of code lists kept as strings (30, e.g. CURRENCY,
      LANGUAGE), number ranges (21) and digits (25). A violation throws
      `FacetViolationException` naming element and rule, e.g.
      `ORDER_INFO/ORDER_ID: at most 250 characters, got 251`.
    - Facets of a shared class are checked in the containing class
      (`ADDRESS/NAME` max 50, while `MlString` itself has no limit).
    - XSD patterns are translated to Java regex (`XsdRegex`): XSD `\w` means
      "letter, mark, number or symbol of any script" (umlauts included, `_`
      excluded as punctuation), Java's `\w` is ASCII only; same for `\d`.
      Verified against `xmllint`.
    - All samples and derived documents still pass, so the checks produce no
      false alarms on valid data. The XSD validation on write remains the
      second net (e.g. for malformed UDX content).
G8. Names cleaned up before the first release. ✔
    - Compound words split: `DispatchNotification`, `OrderResponse`,
      `OrderChange`, `InvoiceList`, `ReceiptAcknowledgement`,
      `RemittanceAdvice` (and their Header/Info/Item/Summary), `startValue`,
      `ftShortNames`, `*Descriptions` instead of `*Descrs`, ...
    - No numbered names left. Choices that had numbers or a vague name are
      now named after what they hold: `Feature.name` / `.value`
      (`FeatureName`, `FeatureValue`), `ProductPrice.value` / `.tax` /
      `.territory`, `CustomsTariffNumber.territory`, `OrderItem.delivery`
      (`SingleDelivery` | `PartialDeliveryList`, likewise for ORDERRESPONSE
      and QUOTATION items), `BmecatProductPriceDetails.validity`
      (`ValidityPeriod` | `Datetimes`), `Mime.content` (`MimeSources` |
      `MimeEmbeddedList`). typeFTEMPLATE is `FeatureTemplate`; the FTEMPLATE
      choice wrapper keeps the element name `Ftemplate`.
    - Feature drops BMEcat's F prefix: `unit`, `order`, `descriptions`,
      `valueDetails`, `valueType`.
    - Plurals with a trailing digit: `names2`, `referenceFeatureGroupIds2`.
    - The remaining `<Parent>Choice` names (`SignatureChoice`,
      `FtValueChoice`, ...) follow the default rule and are left as they are.
G9. Typed UDX as a user extension point. ✔
    - Layer 1: `RawXml` is replaced by `XmlContent`, an immutable,
      namespace-aware tree (`XmlElement`, `XmlText`) for every xsd:anyType
      element (UDX and XML_SIGNATURE). Equality is semantic (namespace +
      local name, prefixes are only hints). `XmlContent.parse(xml)` is
      hardened like the document reader. The writer declares only the
      namespaces the content needs, relative to the root's declarations;
      elements without a namespace get `xmlns=""`.
    - Layer 2 (`opentrans.udx`): a user describes a UDX element once as
      `object XUdx : UdxType<X>("UDX.ACME.X", "urn:acme") { ... }` with
      `required` / `optional` / `list` / `attribute` / `optionalAttribute`
      fields, simple types (`string`, `int`, `decimal`, `boolean`, `date`,
      `dateTime`, `enum`, or a custom `SimpleType`) and nested UdxTypes, plus
      `create(v)`. Reading and writing both check the rules and throw
      `UdxViolationException` with the path
      (`UDX.ACME.SHIPPING/CARRIER: at most 10 characters, got 11`).
      Strict: unknown elements/attributes and stray text are refused; child
      order is free on read, declaration order on write. Children live in the
      namespace of their parent.
    - Access: `content[XUdx]`, `getAll`, `with`, `withAll` (in place, foreign
      UDX untouched); optics `Order.orderHeader.orderInfo.headerUdx.udx(XUdx)`
      (Optional, `set` creates the content) and on traversals
      (`Order.orderItems.every.itemUdx.udx(XUdx)`).
    - DSL: `XmlContentBuilder` (`add(type, value)`, `xml(...)`, `element`,
      `text`); generated builders get `headerUdx { ... }` / `itemUdx { ... }`,
      the hand-written order DSL `udx { ... }`.
    - Example types (`ProjectUdx`, `CostCenterUdx`, `ShippingUdx`) live in
      the model's test fixtures only; the library ships no dialect.
    - Ideas, not planned: XSD export from a UdxType, user XSDs for lax
      validation.

## After the generator

6. ~~Generator for the full schema~~ (see above).
7. ~~DSL for all document types, deriving ORDERRESPONSE / DISPATCHNOTIFICATION /
   INVOICE from an ORDER~~ (G6).
8. ~~Typed UDX~~ (G9).
9. Publishing (local Nexus, later Maven), MIT license.

## Notes

- The bundled XSDs may be redistributed with their copyright notices, which
  are kept unchanged in the files: openTRANS © Fraunhofer IAO / Universität
  Duisburg-Essen BLI, BMEcat © BME e.V., xmldsig and xmlmime © W3C (W3C
  Software License). The library code is MIT (`LICENSE`).
- All four official samples in `opentrans-xml/src/test/resources/samples`
  validate against the bundled XSD (checked with `xmllint`).

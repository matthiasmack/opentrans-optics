# CLAUDE.md

Kotlin library for openTRANS 2.1 documents (read, edit with Arrow optics, create with a DSL, write).
Users and features: see README.md. Decisions, schema findings, generator rules and progress:
see PLAN.md. **Read PLAN.md before larger changes and update it when a step is done**, adding a
`G<n>` entry under "Generator steps" in the same style as the existing ones.

## Commands

- `./gradlew build`: generates the code, compiles and runs all tests. Run it before saying a
  change works.
- `./gradlew :opentrans-model:generateModel` (also `:opentrans-xml:generateXml`,
  `:opentrans-dsl:generateDsl`): regenerates one module's code. The output goes to
  `<module>/build/generated/opentrans/kotlin`.
- `./gradlew :codegen:inventory`: prints which XSD constructs are reachable (analysis only).
- Gradle is often fast because of the build cache. When a result looks suspicious, check
  `<module>/build/test-results/test/*.xml` or use `--rerun-tasks`.

JDK 25, Kotlin, Gradle wrapper, versions in `gradle/libs.versions.toml`.

## Layout

- `codegen/`: XSD → Kotlin generator (Xerces + KotlinPoet). It only runs at build time.
  - `IrBuilder` turns the XSD into a format-neutral IR (`Ir.kt`). `ModelEmitter`, `ReaderEmitter`,
    `WriterEmitter` and `DslEmitter` emit code from the IR.
  - `Names.kt` holds all naming rules and the override tables.
- `opentrans-model/`: the generated model in package `opentrans.model`. Hand-written there:
  `Types.kt` (`DateTime`), `Facets.kt`, `XmlContent.kt` (free XML tree), and `opentrans/udx/`
  (UdxType API).
- `opentrans-xml/`: the generated reader and writer (internal). Hand-written: `XmlIn`, `XmlOut`,
  `OpenTransSchema` (validation, hardening) and `OpenTransXml` (public API). The XSDs are in
  `src/main/resources/opentrans/xsd/2.1`.
- `opentrans-dsl/`: the generated builders. Hand-written: `Common.kt`, `OrderDsl.kt`,
  `PaymentDsl.kt`, `Derive.kt`.
- Test data: `opentrans-xml/src/test/resources/samples` (official samples + `order-spike.xml`).
  Shared fixtures are in `opentrans-model/src/testFixtures`: `spikeOrder` and example UDX types.

## Rules

- **Never edit or commit generated code.** Change the generator and rebuild. Names that come out
  wrong are fixed in `Names.kt`: a compound word, a class/property override, or a choice key such
  as `"FEATURE#1"`. The generator fails on an override that matches nothing, so delete stale
  entries.
- **Strict both ways.** Documents are XSD-validated on read and on write. Facets are checked in
  the generated `init` blocks. Don't add lenient paths. Customer dialects belong in a converter
  outside the library.
- **XML names never change.** Renaming only touches Kotlin names. Round-trip tests
  (`DocumentRoundTripTest`) guard this.
- **UDX is a user extension point.** The library ships no UDX dialect. Example types live only in
  test fixtures. Every UDX feature must work for third-party types nobody here knows.
- Prefer explicit code over reflection, annotations or compiler plugins (except Arrow's KSP).
- Hand-written names must not collide case-insensitively with generated ones: macOS treats
  `Datetime.class` and `DateTime.class` as the same file. Reserve new hand-written model types in
  `Names.reserved`.
- New behaviour gets tests. Prefer the official samples and `spikeOrder`, and assert exact error
  messages where they are part of the API (facet and UDX violations).
- Code, comments, KDoc, README and PLAN.md are written in English.

## Gotchas

- StAX: read attributes before the element text. Reading the text moves the cursor off the
  start tag.
- XSD `\w` / `\d` are Unicode-aware, Java's are ASCII-only. Patterns go through `XsdRegex`.
- The bundled XSDs keep their copyright headers unchanged (openTRANS: Fraunhofer IAO / BLI,
  BMEcat: BME e.V., xmldsig/xmlmime: W3C). Never modify them.

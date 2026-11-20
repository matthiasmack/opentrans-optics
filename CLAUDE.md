# CLAUDE.md

Kotlin library for openTRANS 2.1 documents (read, edit with Arrow optics, create with a DSL, write),
made primarily for ITscope and its partners: the model is generated from ITscope's extended
openTRANS schema, a superset of the official one.
README.md presents the library: features, usage and the section "Design decisions". **Keep that
section current**: a new or changed design decision goes there, in general terms (what and why),
not as a changelog. The XSD -> Kotlin mapping rules are documented on `IrBuilder`. History lives
in git.

## Commands

- `./gradlew build`: generates the code, compiles and runs all tests. Run it before saying a
  change works.
- `./gradlew :opentrans-optics-model:generateModel` (also `:opentrans-optics-xml:generateXml`,
  `:opentrans-optics-dsl:generateDsl`): regenerates one module's code. The output goes to
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
- All Kotlin code lives under the package `io.github.matthiasmack.opentrans` (directories
  `io/github/matthiasmack/opentrans/...`). Below, packages are named relative to it.
- `opentrans-optics-model/`: the generated model in `.model`. Hand-written there: `Types.kt`
  (`DateTime`), `Facets.kt`, `XmlContent.kt` (free XML tree), `.udx` (UdxType API) and `.itscope`
  (ITscope's UDX, `ItScopeRules`).
- `opentrans-optics-xml/`: the generated reader and writer (internal). Hand-written: `XmlIn`, `XmlOut`,
  `OpenTransSchema` (validation profiles, hardening) and `OpenTransXml` (public API). The XSDs are
  in `src/main/resources/io/github/matthiasmack/opentrans/xsd/`: `itscope/` (ITscope's schema,
  source of the generator and the default `Profile.ITSCOPE`) and `2.1/` (official schema,
  `Profile.OPENTRANS`). The build files point the generator at `itscope/`.
- `opentrans-optics-dsl/`: the generated builders. Hand-written: `Common.kt`, `OrderDsl.kt`,
  `PaymentDsl.kt`, `Derive.kt`.
- Test data: `opentrans-optics-xml/src/test/resources/samples` (official samples + `order-spike.xml`).
  Shared fixtures are in `opentrans-optics-model/src/testFixtures`: `spikeOrder` and example UDX types.

## Rules

- **Never edit or commit generated code.** Change the generator and rebuild. Names that come out
  wrong are fixed in `Names.kt`: a compound word, a class/property override, or a choice key such
  as `"FEATURE#1"`. The generator fails on an override that matches nothing, so delete stale
  entries.
- **ITscope first.** New ITscope schema versions replace the files in `xsd/itscope/` unchanged
  (all four files flat in one folder; imports are resolved by file name). Check the generated API
  diff afterwards: the official `2.1/` folder stays as it is, and everything valid there must
  stay valid for ITscope. A name ITscope introduces that clashes can be overridden with a
  namespace-qualified key in `Names.kt` (`"opentrans:PARAMETER"`).
- **Strict both ways.** Documents are XSD-validated on read and on write. Facets are checked in
  the generated `init` blocks. Don't add lenient paths. Customer dialects belong in a converter
  outside the library.
- **XML names never change.** Renaming only touches Kotlin names. Round-trip tests
  (`DocumentRoundTripTest`) guard this.
- **UDX is a user extension point.** The library ships no user's UDX dialect, only the UDX that
  ITscope documents (`.itscope.ItScopeUdx`). Example types live only in test fixtures. Every UDX
  feature must work for third-party types nobody here knows.
- Prefer explicit code over reflection, annotations or compiler plugins (except Arrow's KSP).
- **Exceptions:** a problem with a document or its data throws a subclass of `OpenTransException`
  (`model/Exceptions.kt`). A missing required value is an `IncompleteDocumentException` with the
  XML path in `element`. Wrong use of the API (a block given twice, a contradictory `UdxType`)
  stays a plain `IllegalStateException` / `IllegalArgumentException`. Don't add new exception
  types without a reason the README's error table can explain.
- Hand-written names must not collide case-insensitively with generated ones: macOS treats
  `Datetime.class` and `DateTime.class` as the same file. Reserve new hand-written model types in
  `Names.reserved`.
- New behaviour gets tests. Prefer the official samples and `spikeOrder`, and assert exact error
  messages where they are part of the API (facet and UDX violations).
- Code, comments, KDoc and README are written in English.

## Gotchas

- StAX: read attributes before the element text. Reading the text moves the cursor off the
  start tag.
- XSD `\w` / `\d` are Unicode-aware, Java's are ASCII-only. Patterns go through `XsdRegex`.
- The official samples `invoice.xml` and `dispatchnotification.xml` declare ISO-8859-1 but contain
  UTF-8 bytes. Read as bytes, they decode (correctly, per the declaration) to "GÃ¼nther". Tests
  read the samples as UTF-8 strings. Don't "fix" the library to guess encodings.
- ITscope's published schema (download 2026-11) lacks `SOURCE_PRICE_AMOUNT` in
  `CONTRACT_INFORMATION`, which their docs describe for topi rentals. Such orders fail validation
  until ITscope updates the openTRANS part.
- ITscope has not (yet) agreed to the redistribution of its schema files. Don't publish an
  artifact that contains `xsd/itscope/` before that is settled. ITscope's own sample files are
  not in the repo for the same reason.
- The bundled XSDs keep their copyright headers unchanged (openTRANS: Fraunhofer IAO / BLI,
  BMEcat: BME e.V., xmldsig/xmlmime: W3C). Never modify them.

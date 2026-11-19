package io.github.matthiasmack.opentrans.dsl

import io.github.matthiasmack.opentrans.model.IncompleteDocumentException
import io.github.matthiasmack.opentrans.model.Account
import io.github.matthiasmack.opentrans.model.Address
import io.github.matthiasmack.opentrans.model.BankAccount
import io.github.matthiasmack.opentrans.model.BankCode
import io.github.matthiasmack.opentrans.model.DateTime
import io.github.matthiasmack.opentrans.model.MlString
import io.github.matthiasmack.opentrans.model.Party
import io.github.matthiasmack.opentrans.model.PartyId
import io.github.matthiasmack.opentrans.model.PartyRole
import io.github.matthiasmack.opentrans.model.XmlContent
import io.github.matthiasmack.opentrans.model.XmlElement
import io.github.matthiasmack.opentrans.model.XmlNode
import io.github.matthiasmack.opentrans.model.XmlText
import io.github.matthiasmack.opentrans.udx.UdxType
import io.github.matthiasmack.opentrans.model.Remark
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth

/** Keeps builders from leaking into nested blocks (no `buyer { }` inside `item { }`). */
@DslMarker
annotation class OpenTransDsl

/*
 * Conversions so DSL users can write plain values: 100, 2.5, "0.15", LocalDate.of(...).
 * Doubles go through their decimal string, so 0.1 stays 0.1 and does not become
 * 0.1000000000000000055511151231257827.
 */

internal fun Number.toDecimal(): BigDecimal = when (this) {
    is BigDecimal -> this
    is Int, is Long, is Short, is Byte -> BigDecimal.valueOf(toLong())
    else -> BigDecimal(toString())
}

internal fun String.toDecimal(): BigDecimal = BigDecimal(this)

/** Anything that can be written as bmecat:dtDATETIME. */
internal fun Any.toDateTime(): DateTime = when (this) {
    is DateTime -> this
    is OffsetDateTime -> DateTime.of(this)
    is LocalDate -> DateTime.of(this)
    is YearMonth -> DateTime.of(this)
    is String -> DateTime(this)
    else -> throw IllegalArgumentException("Cannot use ${this::class.simpleName} as a date: $this")
}

/** Text elements that may be repeated per language. */
@OpenTransDsl
class NewRemarksBuilder internal constructor() {
    internal val remarks = mutableListOf<Remark>()

    fun remark(text: String, type: String? = null, lang: String? = null) {
        remarks += Remark(text, type, lang)
    }
}

/** PARTY */
@OpenTransDsl
class NewPartyBuilder internal constructor(private val implicitRole: PartyRole?) {
    private val ids = mutableListOf<PartyId>()
    private val roles = mutableListOf<PartyRole>().apply { implicitRole?.let(::add) }
    private val addresses = mutableListOf<Address>()
    private val accounts = mutableListOf<Account>()

    /** The first id is the one referenced by ORDER_PARTIES_REFERENCE for buyer and supplier. */
    fun id(value: String, type: String? = null) {
        ids += PartyId(value, type)
    }

    fun role(vararg roles: PartyRole) {
        roles.filterNot { it in this.roles }.forEach { this.roles += it }
    }

    fun address(block: NewAddressBuilder.() -> Unit) {
        addresses += NewAddressBuilder().apply(block).build()
    }

    fun account(holder: String, iban: String, bic: String? = null, bankName: String? = null, bankCountry: String? = null) {
        accounts += Account(
            holder = holder,
            bankAccount = BankAccount(iban, "iban"),
            bankCode = bic?.let { BankCode(it, "bic") },
            bankName = bankName,
            bankCountry = bankCountry,
        )
    }

    internal fun build(): Party {
        if (ids.isEmpty()) throw IncompleteDocumentException("PARTY/PARTY_ID", "A party needs at least one id(...)" + (implicitRole?.let { " (${it.xml})" } ?: ""))
        return Party(partyIds = ids.toList(), partyRoles = roles.toList(), addresses = addresses.toList(), accounts = accounts.toList())
    }
}

/** ADDRESS. Name, street, zip and city may be given once per language. */
@OpenTransDsl
class NewAddressBuilder internal constructor() {
    private val names = mutableListOf<MlString>()
    private val streets = mutableListOf<MlString>()
    private val zips = mutableListOf<MlString>()
    private val cities = mutableListOf<MlString>()

    /** ISO 3166 country code, e.g. "DE". */
    var country: String? = null
    var vatId: String? = null

    fun name(value: String, lang: String? = null) { names += MlString(value, lang) }
    fun street(value: String, lang: String? = null) { streets += MlString(value, lang) }
    fun zip(value: String, lang: String? = null) { zips += MlString(value, lang) }
    fun city(value: String, lang: String? = null) { cities += MlString(value, lang) }

    internal fun build() = Address(
        names = names.toList(),
        streets = streets.toList(),
        zips = zips.toList(),
        cities = cities.toList(),
        countryCoded = country,
        vatId = vatId,
    )
}

internal fun udx(xml: String) = XmlContent.parse(xml)

/**
 * Builds UDX (or other free XML) content:
 *
 * ```
 * headerUdx {
 *     add(ProjectInfoUdx, ProjectInfo("project-7", Priority.HIGH, emptyList()))
 *     xml("""<UDX.OTHER xmlns="urn:partner">...</UDX.OTHER>""")
 * }
 * ```
 */
@OpenTransDsl
class XmlContentBuilder internal constructor() {
    private val nodes = mutableListOf<XmlNode>()

    /** Adds the element of a typed UDX; checks the value against its type. */
    fun <T> add(type: UdxType<T>, value: T) {
        nodes += type.write(value)
    }

    /** Adds parsed XML text: elements and text. */
    fun xml(xml: String) {
        nodes += XmlContent.parse(xml).nodes
    }

    fun element(element: XmlElement) {
        nodes += element
    }

    fun text(text: String) {
        nodes += XmlText(text)
    }

    internal fun build() = XmlContent(nodes.toList())
}

internal fun udx(block: XmlContentBuilder.() -> Unit) = XmlContentBuilder().apply(block).build()

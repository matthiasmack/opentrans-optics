package opentrans.dsl

import opentrans.model.Account
import opentrans.model.Accounts
import opentrans.model.BankAccount
import opentrans.model.BankCode
import opentrans.model.Card
import opentrans.model.Cash
import opentrans.model.Check
import opentrans.model.Debit
import opentrans.model.Days
import opentrans.model.DiscountFactor
import opentrans.model.PaymentDate
import opentrans.model.Payment
import opentrans.model.PaymentMethod
import opentrans.model.PaymentTerms
import opentrans.model.TimeForPayment

/** PAYMENT: exactly one of card / accounts / debit / check / cash, plus terms. */
@OpenTransDsl
class NewPaymentBuilder internal constructor() {
    private var method: PaymentMethod? = null
    private var terms: PaymentTerms? = null
    var centralRegulation: Boolean? = null

    /** [expires] is anything date-like: "2028-05", YearMonth.of(2028, 5), ... */
    fun card(type: String, number: String, expires: Any, holder: String, authCode: String? = null, refNumber: String? = null) =
        choose(Card(cardNum = number, cardAuthCode = authCode, cardRefNum = refNumber, cardExpirationDate = expires.toDateTime(), cardHolderName = holder, type = type))

    fun accounts(block: NewAccountsBuilder.() -> Unit) = choose(Accounts(NewAccountsBuilder().apply(block).accounts))
    fun debit() = choose(Debit(true))
    fun check() = choose(Check(true))
    fun cash() = choose(Cash(true))

    fun terms(block: NewPaymentTermsBuilder.() -> Unit) {
        terms = PaymentTerms(timesForPayment = NewPaymentTermsBuilder().apply(block).times)
    }

    private fun choose(chosen: PaymentMethod) {
        check(method == null) { "PAYMENT allows only one payment method, already set: ${method!!::class.simpleName}" }
        method = chosen
    }

    internal fun build(): Payment {
        val chosen = checkNotNull(method) { "payment { } needs a payment method: card, accounts, debit, check or cash" }
        return Payment(method = chosen, centralRegulation = centralRegulation, paymentTerms = terms)
    }
}

@OpenTransDsl
class NewAccountsBuilder internal constructor() {
    internal val accounts = mutableListOf<Account>()

    fun account(holder: String, iban: String, bic: String? = null, bankName: String? = null, bankCountry: String? = null) {
        accounts += Account(holder, BankAccount(iban, "iban"), bic?.let { BankCode(it, "bic") }, bankName, bankCountry)
    }
}

/** PAYMENT_TERMS: `withinDays(10, discount = "0.02")` or `dueOn("2026-12-31")`. */
@OpenTransDsl
class NewPaymentTermsBuilder internal constructor() {
    internal val times = mutableListOf<TimeForPayment>()

    fun withinDays(days: Int, discount: Number? = null) {
        times += TimeForPayment(due = Days(days), discount = discount?.let { DiscountFactor(it.toDecimal()) })
    }

    fun withinDays(days: Int, discount: String) {
        times += TimeForPayment(due = Days(days), discount = DiscountFactor(discount.toDecimal()))
    }

    fun dueOn(date: Any, discount: String? = null) {
        times += TimeForPayment(due = PaymentDate(date.toDateTime()), discount = discount?.let { DiscountFactor(it.toDecimal()) })
    }
}

package io.github.matthiasmack.opentrans.model

import java.math.BigDecimal

/*
 * Checks for XSD facets, called from the init blocks of the generated model.
 * They make an invalid value fail where it is created, with the XML element
 * in the message. The XSD validation on write stays as a second net.
 */


internal fun facetLength(value: String, min: Int?, max: Int?, label: String) {
    val length = value.codePointCount(0, value.length)
    if (min != null && length < min) throw FacetViolationException("$label: at least $min characters, got $length")
    if (max != null && length > max) throw FacetViolationException("$label: at most $max characters, got $length")
}

internal fun facetPattern(value: String, pattern: Regex, label: String) {
    if (!pattern.matches(value)) throw FacetViolationException("$label: '$value' does not match the XSD pattern")
}

internal fun facetValues(value: String, allowed: Set<String>, label: String) {
    if (value !in allowed) throw FacetViolationException("$label: '$value' is not an allowed value")
}

internal fun facetRange(value: BigDecimal, minInclusive: String?, maxInclusive: String?, minExclusive: String?, maxExclusive: String?, label: String) {
    minInclusive?.let { if (value < BigDecimal(it)) throw FacetViolationException("$label: at least $it, got $value") }
    maxInclusive?.let { if (value > BigDecimal(it)) throw FacetViolationException("$label: at most $it, got $value") }
    minExclusive?.let { if (value <= BigDecimal(it)) throw FacetViolationException("$label: more than $it, got $value") }
    maxExclusive?.let { if (value >= BigDecimal(it)) throw FacetViolationException("$label: less than $it, got $value") }
}

internal fun facetDigits(value: BigDecimal, total: Int?, fraction: Int?, label: String) {
    val normalized = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
    if (total != null && normalized.precision() > total) throw FacetViolationException("$label: at most $total digits, got $value")
    if (fraction != null && normalized.scale() > fraction) throw FacetViolationException("$label: at most $fraction decimal places, got $value")
}

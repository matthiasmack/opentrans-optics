package io.github.matthiasmack.opentrans.udx

import arrow.core.Option
import arrow.optics.Optional
import arrow.optics.Traversal
import io.github.matthiasmack.opentrans.model.XmlContent
import io.github.matthiasmack.opentrans.model.XmlElement
import io.github.matthiasmack.opentrans.model.XmlNode

/*
 * Typed access to UDX content. A HEADER_UDX / ITEM_UDX may hold several UDX
 * elements side by side; each type reads and writes only its own element and
 * leaves the rest untouched.
 *
 * ```
 * val info: ProjectInfo? = order.orderHeader.orderInfo.headerUdx?.get(ProjectInfoUdx)
 *
 * val project = Order.orderHeader.orderInfo.headerUdx.udx(ProjectInfoUdx)
 * val changed = project.modify(order) { it.copy(priority = Priority.LOW) }
 * val added   = project.set(order, ProjectInfo("project-8", null, emptyList()))
 * ```
 */

/**
 * The value of [type] in this content, or null if its element is not there.
 *
 * @throws IllegalStateException if the element occurs more than once; use [getAll]
 * @throws UdxViolationException if the element does not match [type]
 */
operator fun <T> XmlContent.get(type: UdxType<T>): T? {
    val matching = elements.filter { it.name == type.name }
    check(matching.size <= 1) { "${type.name.localPart} occurs ${matching.size} times; use getAll" }
    return matching.singleOrNull()?.let(type::read)
}

/** All values of [type] in this content, in document order. */
fun <T> XmlContent.getAll(type: UdxType<T>): List<T> = elements.filter { it.name == type.name }.map(type::read)

/**
 * This content with [value] as the element of [type]: replaces an existing one
 * in place (any further ones are removed), appends it otherwise; null removes it.
 */
fun <T> XmlContent.with(type: UdxType<T>, value: T?): XmlContent = withAll(type, listOfNotNull(value))

/** This content with exactly [values] as the elements of [type], at the place of the first existing one. */
fun <T> XmlContent.withAll(type: UdxType<T>, values: List<T>): XmlContent {
    val written = values.map(type::write)
    val isType = { node: XmlNode -> node is XmlElement && node.name == type.name }
    val first = nodes.indexOfFirst(isType)
    if (first < 0) return XmlContent(nodes + written)
    return XmlContent(nodes.take(first) + written + nodes.drop(first).filterNot(isType))
}

/**
 * Optic from UDX content to the value of [type]. Absent when the content or
 * the element is missing; `set` creates both if needed.
 */
fun <T> UdxType<T>.optic(): Optional<XmlContent?, T> = Optional(
    getOption = { content -> Option.fromNullable(content?.get(this)) },
    set = { content, value -> (content ?: XmlContent.EMPTY).with(this, value) },
)

/** Focus on a typed UDX inside a UDX property: `Order.orderHeader.orderInfo.headerUdx.udx(ProjectInfoUdx)`. */
fun <S, T> Optional<S, XmlContent?>.udx(type: UdxType<T>): Optional<S, T> = this compose type.optic()

/** Focus on a typed UDX in many places: `Order.orderItems.every.itemUdx.udx(CostCenterUdx)`. */
fun <S, T> Traversal<S, XmlContent?>.udx(type: UdxType<T>): Traversal<S, T> = this compose type.optic()

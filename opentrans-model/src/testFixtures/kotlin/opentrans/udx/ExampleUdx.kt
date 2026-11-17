package opentrans.udx

import opentrans.model.DateTime
import java.math.BigDecimal
import java.time.LocalDate

/*
 * Example UDX types, defined the way a library user would. They describe the
 * UDX of the spike order (samples/order-spike.xml) plus a richer one that
 * uses every kind of field. Shared by the model, xml and dsl tests.
 */

const val EXAMPLE_NS = "urn:example:udx"

enum class Level { HIGH, LOW }

/** `<PRIORITY level="high"/>` */
data class Priority(val level: Level)

object PriorityUdx : UdxType<Priority>("PRIORITY", EXAMPLE_NS) {
    private val level = attribute("level", Priority::level, enum<Level> { it.name.lowercase() })
    override fun create(v: Values) = Priority(v[level])
}

/** HEADER_UDX of the spike order: `<UDX.CUSTOM><PROJECT>project-7</PROJECT><PRIORITY level="high"/></UDX.CUSTOM>` */
data class Project(val project: String, val priority: Priority?)

object ProjectUdx : UdxType<Project>("UDX.CUSTOM", EXAMPLE_NS) {
    private val project = required("PROJECT", Project::project, string(minLength = 1, maxLength = 20))
    private val priority = optional("PRIORITY", Project::priority, PriorityUdx)
    override fun create(v: Values) = Project(v[project], v[priority])
}

/** ITEM_UDX of the spike order: `<UDX.CUSTOM><COST_CENTER>cc-42</COST_CENTER></UDX.CUSTOM>` */
data class CostCenter(val costCenter: String)

object CostCenterUdx : UdxType<CostCenter>("UDX.CUSTOM", EXAMPLE_NS) {
    private val costCenter = required("COST_CENTER", CostCenter::costCenter, string(pattern = Regex("cc-\\d+")))
    override fun create(v: Values) = CostCenter(v[costCenter])
}

/** Uses every kind of field and simple type; no namespace, as UDX often comes. */
data class Shipping(
    val carrier: String,
    val weight: BigDecimal,
    val parcels: Int,
    val fragile: Boolean,
    val pickup: LocalDate?,
    val booked: DateTime?,
    val references: List<String>,
    val labels: List<Priority>,
    val version: Int?,
)

object ShippingUdx : UdxType<Shipping>("UDX.ACME.SHIPPING") {
    private val version = optionalAttribute("version", Shipping::version, int(min = 1))
    private val carrier = required("CARRIER", Shipping::carrier, string(maxLength = 10))
    private val weight = required("WEIGHT", Shipping::weight, decimal(min = BigDecimal.ZERO))
    private val parcels = required("PARCELS", Shipping::parcels, int(min = 1, max = 99))
    private val fragile = required("FRAGILE", Shipping::fragile, boolean())
    private val pickup = optional("PICKUP", Shipping::pickup, date())
    private val booked = optional("BOOKED", Shipping::booked, dateTime())
    private val references = list("REFERENCE", Shipping::references, string(), max = 3)
    private val labels = list("LABEL", Shipping::labels, PriorityUdx)
    override fun create(v: Values) =
        Shipping(v[carrier], v[weight], v[parcels], v[fragile], v[pickup], v[booked], v[references], v[labels], v[version])
}

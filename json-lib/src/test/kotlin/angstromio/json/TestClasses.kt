@file:kotlin.jvm.JvmMultifileClass

package angstromio.json

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import java.util.*

data class FooClass(val id: String)

data class SimplePerson(val name: String)

abstract class PointMixin(
    @JsonProperty("x") abscissa: Int,
    @JsonProperty("y") ordinate: Int) {
    @JsonIgnore abstract fun area(): Int
}

data class Point(val abscissa: Int, val ordinate: Int) {
    fun area(): Int = abscissa * ordinate
}

data class Points(val first: Point, val second: Point)

data class WithDefaults(val first: String = "Hello", val second: String = "World")

@JsonNaming(PropertyNamingStrategies.KebabCaseStrategy::class)
data class ClassWithKebabCase(val pleaseUseKebabCase: Boolean)

@JsonNaming(PropertyNamingStrategies.KebabCaseStrategy::class)
abstract class KebabCaseMixin(willThisGetTheRightCasing: Boolean)

data class ClassShouldUseKebabCaseFromMixin(val willThisGetTheRightCasing: Boolean)

enum class Weekday {
    MON, TUE, WED, THU, FRI, SAT, SUN
}
enum class Month {
    JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC
}
data class BasicDate(
    val month: Month,
    val day: Int,
    val year: Int,
    val weekday: Weekday)
data class WithOptionalScalaEnumeration(val month: Month?)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(*arrayOf(JsonSubTypes.Type(value = Rectangle::class, name = "rectangle"), JsonSubTypes.Type(value = Circle::class, name = "circle")))
sealed interface Shape
data class Rectangle(val width: Int, val height: Int) : Shape
data class Circle(val radius: Int) : Shape
data class View(val shapes: List<Shape>)
data class OptionalView(val shapes: List<Shape>, val optional: Shape?)



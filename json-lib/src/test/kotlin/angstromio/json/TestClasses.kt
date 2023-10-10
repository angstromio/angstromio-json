package angstromio.json

import angstromio.validation.constraints.OneOf
import angstromio.validation.constraints.PostConstructValidation
import angstromio.validation.constraints.UUID
import angstromio.validation.engine.PostConstructValidationResult
import arrow.core.Either
import com.fasterxml.jackson.annotation.JacksonInject
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonIgnoreType
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.annotation.JsonView
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TreeTraversingParser
import com.fasterxml.jackson.databind.node.ValueNode
import jakarta.validation.Payload
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal
import java.util.*
import kotlin.reflect.full.isSubclassOf
import java.time.Duration as JavaDuration
import kotlin.time.Duration as KotlinDuration

object TestClasses {

    data class WithDefaults(val first: String, val second: String = "World")

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
        val weekday: Weekday
    )

    data class WithNullableEnum(val month: Month?)

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes(
        value = [JsonSubTypes.Type(
            value = BoundaryA::class,
            name = "a"
        ), JsonSubTypes.Type(value = BoundaryB::class, name = "b"), JsonSubTypes.Type(
            value = BoundaryC::class,
            name = "c"
        )]
    )
    interface Boundary {
        val value: String
    }

    data class BoundaryA(override val value: String) : Boundary
    data class BoundaryB(override val value: String) : Boundary
    data class BoundaryC(override val value: String) : Boundary

    data class GG<T : Any, U : Any, V : Any, X : Any, Y : Any, Z : Any>(
        val t: T,
        val u: U,
        val v: V,
        val x: X,
        val y: Y,
        val z: Z
    )

    data class B(val value: String)
    data class D(val value: Int)
    data class AGeneric<T : Any>(val b: T?)
    data class AAGeneric<T : Any, U : Any>(val b: T?, val c: U?)
    data class C(val a: AGeneric<B>)
    data class E<T : Any, U : Any, V : Any>(val a: AAGeneric<T, U>, val b: V?)
    data class F<T : Any, U : Any, V : Any, X : Any, Y : Any, Z : Any>(
        val a: T?,
        val b: List<U>,
        val c: V?,
        val d: X,
        val e: Either<Y, Z>
    )

    data class WithTypeBounds<A : Boundary>(val a: A?)
    data class G<T : Any, U : Any, V : Any, X : Any, Y : Any, Z : Any>(val gg: GG<T, U, V, X, Y, Z>)

    data class SomeNumberType<N : Number>(
        @JsonDeserialize(
            contentAs = Number::class,
            using = NumberDeserializers.NumberDeserializer::class
        ) val n: N?
    )

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes(
        value = [JsonSubTypes.Type(
            value = Rectangle::class,
            name = "rectangle"
        ), JsonSubTypes.Type(value = Circle::class, name = "circle")]
    )
    sealed interface Shape
    data class Rectangle(@Min(0) val width: Int, @Min(0) val height: Int) : Shape
    data class Circle(@Min(0) val radius: Int) : Shape
    data class View(val shapes: List<Shape>)
    data class NullableView(val shapes: List<Shape>, val optional: Shape? = null)

    data class TestJsonCreator(val int: Int) {
        companion object {
            @JvmStatic
            @JsonCreator
            operator fun invoke(s: String): TestJsonCreator = TestJsonCreator(s.toInt())
        }
    }

    data class TestJsonCreator2(val ints: List<Int>, val default: String = "Hello, World") {
        companion object {
            @JvmStatic
            @JsonCreator
            operator fun invoke(strings: List<String>): TestJsonCreator2 = TestJsonCreator2(strings.map { it.toInt() })
        }
    }

    data class TestJsonCreatorWithValidation(val int: Int) {
        companion object {
            @JvmStatic
            @JsonCreator
            operator fun invoke(@NotEmpty s: String): TestJsonCreatorWithValidation =
                TestJsonCreatorWithValidation(s.toInt())
        }
    }

    data class TestJsonCreatorWithValidations(val int: Int) {
        companion object {
            @JvmStatic
            @JsonCreator
            operator fun invoke(@NotEmpty @OneOf(value = ["42", "137"]) s: String): TestJsonCreatorWithValidations =
                TestJsonCreatorWithValidations(s.toInt())
        }
    }

    data class ClassWithMultipleConstructors(val number1: Long, val number2: Long, val number3: Long) {
        constructor(
            numberAsString1: String,
            numberAsString2: String,
            numberAsString3: String
        ) : this(numberAsString1.toLong(), numberAsString2.toLong(), numberAsString3.toLong())
    }

    data class ClassWithMultipleConstructorsAnnotated(val number1: Long, val number2: Long, val number3: Long) {
        @JsonCreator
        constructor(
            numberAsString1: String,
            numberAsString2: String,
            numberAsString3: String
        ) : this(numberAsString1.toLong(), numberAsString2.toLong(), numberAsString3.toLong())
    }

    data class ClassWithMultipleConstructorsAnnotatedAndValidations(
        val number1: Long,
        val number2: Long,
        val uuid: String
    ) {
        @JsonCreator
        constructor(
            @NotEmpty numberAsString1: String,
            @OneOf(value = ["10001", "20002", "30003"]) numberAsString2: String,
            @UUID thirdArgument: String
        ) : this(numberAsString1.toLong(), numberAsString2.toLong(), thirdArgument)
    }

//    data class TimeWithFormat(@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX") when: Time)

    /* Note: the decoder automatically changes "_i" to "i" for de/serialization:
     * See ClassField#jsonNameForField */
    interface Aumly {
        @get:JsonProperty("i")
        @Suppress("PropertyName") val _i: Int

        @get:JsonProperty("j")
        @Suppress("PropertyName") val _j: String
    }

    data class Aum(override val _i: Int, override val _j: String) : Aumly

    interface Bar {
        @get:JsonProperty("helloWorld")
        @get:JacksonInject(value = "accept")
        val hello: String
    }

    data class FooBar(override val hello: String) : Bar

    interface Baz : Bar {
        @get:JsonProperty("goodbyeWorld")
        override val hello: String
    }

    data class FooBaz(override val hello: String) : Baz

    interface BarBaz {
        @get:JsonProperty("goodbye")
        val hello: String
    }

    data class FooBarBaz(override val hello: String) : BarBaz,
        Bar // will end up with BarBaz @JsonProperty value as interface linearization is "right-to-left"

    interface Loadable {
        @get:JsonProperty("url")
        val uri: String
    }

    abstract class Resource {
        @get:JsonProperty("resource")
        abstract val uri: String
    }

    data class File(@JsonProperty("file") override val uri: String) : Resource()
    data class Folder(@JsonProperty("folder") override val uri: String) : Resource()

    abstract class LoadableResource : Loadable {
        @get:JsonProperty("resource")
        abstract override val uri: String
    }

    data class LoadableFile(@JsonProperty("file") override val uri: String) : LoadableResource()
    data class LoadableFolder(@JsonProperty("folder") override val uri: String) : LoadableResource()

    interface TestTrait {
        @get:JsonProperty("oldnes")
        val age: Int

        @get:NotEmpty
        val name: String
    }

    @JsonNaming
    data class TestTraitImpl(
        @JsonProperty("agenes") override val age: Int, // should override inherited annotation from interface
        @JacksonInject override val name: String, // should have two annotations, one from interface and one here
        @JacksonInject val dateTime: LocalDate,
        @JacksonInject val foo: String,
        @JsonDeserialize(contentAs = BigDecimal::class, using = NumberDeserializers.BigDecimalDeserializer::class)
        val double: BigDecimal,
        @JsonIgnore val ignoreMe: String
    ) : TestTrait {

        val testFoo: String by lazy { "foo" }
        val testBar: String by lazy { "bar" }
    }

    sealed interface CarType {
        @get:JsonValue
        val toJson: String
    }

    data object Volvo : CarType {
        override val toJson: String = "volvo"
    }

    data object Audi : CarType {
        override val toJson: String = "audi"
    }

    data object Volkswagen : CarType {
        override val toJson: String = "vw"
    }

    data class Vehicle(val vin: String, val type: CarType)

    sealed interface ZeroOrOne
    data object Zero : ZeroOrOne
    data object One : ZeroOrOne
    data object Two : ZeroOrOne

    data class ClassWithZeroOrOne(val id: ZeroOrOne)

    data class DataClass(val id: Long, val name: String)
    data class ClassIdAndNullable(val id: Long, val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = false)
    data class StrictDataClassIdAndNullable(val id: Long, val name: String?)

    data class SimpleClassWithInjection(@JacksonInject(value = "accept") val hello: String)

    @JsonIgnoreProperties(ignoreUnknown = false)
    data class StrictDataClass(val id: Long, val name: String)

    @JsonIgnoreProperties(ignoreUnknown = false)
    data class StrictDataClassWithNullable(val value: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LooseDataClass(val id: Long, val name: String)

    data class ClassWithLazyVal(val id: Long) {
        val woo by lazy { "yeah" }
    }

    data class GenericTestClass<T : Any>(val data: T)
    data class GenericTestClassWithValidation<T : Any>(@NotEmpty val data: T)
    data class GenericTestClassWithValidationAndMultipleArgs<T : Any>(
        @NotEmpty val data: T,
        @Min(5) val number: Int
    )

    data class Page<T : Any>(val data: List<T>, val pageSize: Int, val next: Long? = null, val previous: Long? = null)

    data class ClassWithGeneric<T : Any>(val inside: GenericTestClass<T>)

    data class ClassWithNullableGeneric<T : Any>(val inside: GenericTestClass<T>?)

    data class ClassWithTypes<T : Any, U : Any>(val first: T, val second: U)

    data class ClassWithMapTypes<T : Any, U : Any>(val data: Map<T, U>)

    data class ClassWithManyTypes<R : Any, S : Any, T : Any>(val one: R, val two: S, val three: T)

    data class ClassIgnoredFieldInConstructorNoDefault(
        @JsonIgnore val id: Long,
        val name: String,
        val description: String
    )

    data class ClassIgnoredFieldInConstructorWithDefault(
        @JsonIgnore val id: Long = 42L,
        val name: String,
        val description: String
    )

    data class ClassWithIgnoredField(val id: Long) {
        @JsonIgnore
        val ignoreMe = "Foo"
    }

    @JsonIgnoreProperties(value = ["ignore_me", "feh"])
    data class ClassWithIgnoredFieldsMatchAfterToSnakeCase(val id: Long) {
        val ignoreMe = "Foo"
        val feh = "blah"
    }

    @JsonIgnoreProperties(value = ["ignore_me", "feh"])
    data class ClassWithIgnoredFieldsExactMatch(val id: Long) {
        @Suppress("PropertyName")
        val ignore_me = "Foo"
        val feh = "blah"
    }

    data class ClassWithTransientField(val id: Long) {
        @Transient
        val lol = "asdf"
    }

    data class ClassWithLazyField(val id: Long) {
        @get:JsonIgnore
        val lol by lazy { "asdf" }
    }

    data class ClassWithOverloadedField(val id: Long) {
        fun id(prefix: String): String = prefix + id
    }

    data class ClassWithNullable(val value: String? = null)

    data class ClassWithNullableAndValidation(@Min(100) val towingCapacity: Int? = null)

    data class ClassWithNullableAndIncompatibleValidation(@Min(100) val areYou: Boolean? = null)

    data class ClassWithJsonNode(val value: JsonNode)

    class ThingAnnotationDeserializer : StdDeserializer<Thing>(Thing::class.java) {
        override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): Thing {
            val jsonNode: ObjectNode = jp.codec.readTree(jp)
            val value = jsonNode.get("value")
            return angstromio.json.Things.named(value.asText())
        }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun getEmptyValue(): Thing = angstromio.json.Things.named("")
    }

    data class ClassWithAllTypes(
        val map: Map<String, String>,
        val set: Set<Int>,
        val string: String,
        val list: List<Int>,
        val indexedValue: IndexedValue<Int>,
        val vector: Vector<Int>,
        @JsonDeserialize(using = ThingAnnotationDeserializer::class)
        val annotation: Thing,
        val kotlinEnum: Weekday,
        val javaEnum: CarMake,
        val bigDecimal: BigDecimal,
        val bigInt: BigInteger,
        val int: Int,
        val long: Long,
        val char: Char,
        val bool: Boolean,
        val short: Short,
        val byte: Byte,
        val float: Float,
        val double: Double,
        val array: Array<Any>,
        val arrayList: ArrayList<String>,
        val any: Any,
        val intMap: Map<Int, Int> = emptyMap(),
        val longMap: Map<Long, Long> = emptyMap()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ClassWithAllTypes

            if (map != other.map) return false
            if (set != other.set) return false
            if (string != other.string) return false
            if (list != other.list) return false
            if (indexedValue != other.indexedValue) return false
            if (vector != other.vector) return false
            if (annotation != other.annotation) return false
            if (kotlinEnum != other.kotlinEnum) return false
            if (javaEnum != other.javaEnum) return false
            if (bigDecimal != other.bigDecimal) return false
            if (bigInt != other.bigInt) return false
            if (int != other.int) return false
            if (long != other.long) return false
            if (char != other.char) return false
            if (bool != other.bool) return false
            if (short != other.short) return false
            if (byte != other.byte) return false
            if (float != other.float) return false
            if (double != other.double) return false
            if (!array.contentEquals(other.array)) return false
            if (arrayList != other.arrayList) return false
            if (any != other.any) return false
            if (intMap != other.intMap) return false
            if (longMap != other.longMap) return false

            return true
        }

        override fun hashCode(): Int {
            var result = map.hashCode()
            result = 31 * result + set.hashCode()
            result = 31 * result + string.hashCode()
            result = 31 * result + list.hashCode()
            result = 31 * result + indexedValue.hashCode()
            result = 31 * result + vector.hashCode()
            result = 31 * result + annotation.hashCode()
            result = 31 * result + kotlinEnum.hashCode()
            result = 31 * result + javaEnum.hashCode()
            result = 31 * result + bigDecimal.hashCode()
            result = 31 * result + bigInt.hashCode()
            result = 31 * result + int
            result = 31 * result + long.hashCode()
            result = 31 * result + char.hashCode()
            result = 31 * result + bool.hashCode()
            result = 31 * result + short
            result = 31 * result + byte
            result = 31 * result + float.hashCode()
            result = 31 * result + double.hashCode()
            result = 31 * result + array.contentHashCode()
            result = 31 * result + arrayList.hashCode()
            result = 31 * result + any.hashCode()
            result = 31 * result + intMap.hashCode()
            result = 31 * result + longMap.hashCode()
            return result
        }
    }

    data class ClassWithException(val unused: String) {
        init {
            throw JsonMappingException.from(TreeTraversingParser(IntNode(1)), "Oops!!!")
        }
    }

    object OuterObject {

        class InnerClass {
            data class NestedClassInClass(val id: String)
        }

        data class NestedClass(val id: Long)

        object InnerObject {

            data class SuperNestedClass(val id: Long)
        }
    }

    data class ClassWithSnakeCase(val oneThing: String, val twoThing: String)

    data class ClassWithArrays(
        val one: String,
        val two: Array<String>,
        val three: Array<Int>,
        val four: Array<Long>,
        val five: Array<Char>,
        val bools: Array<Boolean>,
        val bytes: Array<Byte>,
        val doubles: Array<Double>,
        val floats: Array<Float>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ClassWithArrays

            if (one != other.one) return false
            if (!two.contentEquals(other.two)) return false
            if (!three.contentEquals(other.three)) return false
            if (!four.contentEquals(other.four)) return false
            if (!five.contentEquals(other.five)) return false
            if (!bools.contentEquals(other.bools)) return false
            if (!bytes.contentEquals(other.bytes)) return false
            if (!doubles.contentEquals(other.doubles)) return false
            if (!floats.contentEquals(other.floats)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = one.hashCode()
            result = 31 * result + two.contentHashCode()
            result = 31 * result + three.contentHashCode()
            result = 31 * result + four.contentHashCode()
            result = 31 * result + five.contentHashCode()
            result = 31 * result + bools.contentHashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + doubles.contentHashCode()
            result = 31 * result + floats.contentHashCode()
            return result
        }
    }

    data class ClassWithArrayLong(val array: Array<Long>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ClassWithArrayLong

            return array.contentEquals(other.array)
        }

        override fun hashCode(): Int {
            return array.contentHashCode()
        }
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    data class ClassWithArrayListOfIntegers(val arraylist: ArrayList<Integer>)

    data class ClassWithArrayBoolean(val array: Array<Boolean>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ClassWithArrayBoolean

            return array.contentEquals(other.array)
        }

        override fun hashCode(): Int {
            return array.contentHashCode()
        }
    }


    data class ClassWithSeqLong(val seq: List<Long>)

    data class ClassWithValidation(@Min(1) val value: Long)

    data class ClassWithSeqOfClassWithValidation(val seq: List<ClassWithValidation>)

    data class Foo(val name: String)

    data class ClassCharacter(val c: Char)

    data class Car(
        val id: Long,
        val make: CarMake,
        val model: String,
        @Min(2000) val year: Int,
        val owners: List<Person>,
        @Min(0) val numDoors: Int = 4,
        val manual: Boolean = false,
        val ownershipStart: LocalDateTime = LocalDateTime.now(),
        val ownershipEnd: LocalDateTime = LocalDateTime.now().plusYears(1),
        val warrantyStart: LocalDateTime? = null,
        val warrantyEnd: LocalDateTime? = null,
        val passengers: List<Person> = emptyList()
    ) {

        @PostConstructValidation
        fun validateId(): PostConstructValidationResult =
            PostConstructValidationResult.validIfTrue({ id.mod(2) == 1 }, { "id may not be even" })

        @PostConstructValidation
        fun validateYearBeforeNow(): PostConstructValidationResult {
            val thisYear = LocalDate.now().year
            val yearMoreThanOneYearInFuture: Boolean =
                if (year > thisYear) {
                    (year - thisYear) > 1
                } else false
            return PostConstructValidationResult.validIfFalse(
                { yearMoreThanOneYearInFuture },
                { "Model year can be at most one year newer." }
            )
        }

        @PostConstructValidation(fields = ["ownershipEnd"])
        fun ownershipTimesValid(): PostConstructValidationResult =
            validateTimeRange(
                ownershipStart,
                ownershipEnd,
                "ownershipStart",
                "ownershipEnd"
            )

        @PostConstructValidation(fields = ["warrantyStart", "warrantyEnd"])
        fun warrantyTimeValid(): PostConstructValidationResult =
            validateTimeRange(
                warrantyStart,
                warrantyEnd,
                "warrantyStart",
                "warrantyEnd"
            )

        private fun validateTimeRange(
            start: LocalDateTime?,
            end: LocalDateTime?,
            startProperty: String,
            endProperty: String
        ): PostConstructValidationResult {

            val rangeDefined = start != null && end != null
            val partialRange = !rangeDefined && (start != null || end != null)

            return if (rangeDefined) {
                PostConstructValidationResult.validIfTrue(
                    { start!!.isBefore(end) },
                    {
                        "%s <%s> must be after %s <%s>"
                            .format(
                                endProperty,
                                DateTimeFormatter.ISO_DATE_TIME.format(end),
                                startProperty,
                                DateTimeFormatter.ISO_DATE_TIME.format(start)
                            )
                    }
                )
            } else if (partialRange) {
                PostConstructValidationResult.Invalid(
                    "both %s and %s are required for a valid range".format(startProperty, endProperty)
                )
            } else {
                PostConstructValidationResult.Valid
            }
        }
    }

    data class PersonWithDottedName(val id: Int, @JsonProperty("name.last") val lastName: String)

    data class SimplePerson(val name: String)

    data class PersonWithThings(
        val id: Int,
        val name: String,
        val age: Int?,
        @Size(min = 1, max = 10) val things: Map<String, Things>
    )

    data class Things(@Size(min = 1, max = 2) val names: List<String>)

    @JsonNaming
    data class CamelCaseSimplePerson(val myName: String)

    data class CamelCaseSimplePersonNoAnnotation(val myName: String)

    data class ClassWithMap(val map: Map<String, String>)

    data class ClassWithSortedMap(val sortedMap: SortedMap<String, Int>)

    data class ClassWithSetOfLongs(val set: Set<Long>)

    data class ClassWithSeqOfLongs(val seq: List<Long>)

    data class ClassWithNestedSeqLong(
        val seqClass: ClassWithSeqLong,
        val setClass: ClassWithSetOfLongs
    )

    data class Blah(val foo: String)

    // https://github.com/FasterXML/jackson-module-kotlin/issues/199#issuecomment-1013810769
    @JvmInline
    value class TestIdStringWrapper(private val id: String)
    data class ObjWithTestId(val id: TestIdStringWrapper)

    object Obj {
        data class NestedClassInObject(val id: String)

        data class NestedClassInObjectWithNestedClassInObjectParam(
            val nested: NestedClassInObject
        )

    }

    class TypeAndCompanion {
        companion object {
            data class NestedClassInCompanion(val id: String)
        }
    }

    data class ClassWithVal(val name: String) {
        val type: String = "person"
    }

    data class ClassWithEnum(val name: String, val make: CarMake)

    data class ClassWithComplexEnums(
        val name: String,
        val make: CarMake,
        val makeOpt: CarMake?,
        val makeSeq: List<CarMake>,
        val makeSet: Set<CarMake>
    )

    data class ClassWithSeqEnum(val enumSeq: List<CarMake>)

    data class ClassWithNullableEnum(val enumOpt: CarMake?)

    data class ClassWithDateTime(val dateTime: LocalDateTime)

    data class ClassWithIntAndDateTime(
        @NotEmpty val name: String,
        val age: Int,
        val age2: Int,
        val age3: Int,
        val dateTime: LocalDateTime,
        val dateTime2: LocalDateTime,
        val dateTime3: LocalDateTime,
        val dateTime4: LocalDateTime,
        @NotEmpty val dateTime5: LocalDateTime?
    )

    data class ClassWithKotlinDuration(val duration: KotlinDuration)

    data class ClassWithJavaDuration(val duration: JavaDuration)

    data class ClassWithFooClassInject(@JacksonInject val fooClass: FooClass)

    data class ClassWithFooClassInjectAndDefault(@JacksonInject val fooClass: FooClass = FooClass("12345"))

    data class ClassWithQueryParamDateTimeInject(@JacksonInject val dateTime: Temporal)

    @Suppress("PropertyName")
    data class ClassWithEscapedLong(val `1-5`: Long)

    @Suppress("PropertyName")
    data class ClassWithEscapedLongAndAnnotation(@Max(25) val `1-5`: Long)

    @Suppress("PropertyName")
    data class ClassWithEscapedString(val `1-5`: String)

    @Suppress("RemoveRedundantBackticks")
    data class ClassWithEscapedNormalString(val `a`: String)

    @Suppress("PropertyName")
    data class UnicodeNameClass(val `winning-id`: Int, val name: String)

    data class TestEntityIdsResponse(val entityIds: List<Long>, val previousCursor: String, val nextCursor: String)

    data class TestEntityIdsResponseWithCompanion(
        val entityIds: List<Long>,
        val previousCursor: String,
        val nextCursor: String
    ) {
        companion object {
            const val MSG: String = "im the companion"
        }
    }

    data class FooClass(val id: String)

    @JsonIgnoreProperties(value = ["foo", "bar"])
    interface SomethingSomething {
        val foo: String
            get() = "Hello"

        val bar: String
            get() = "World"
    }

    data class Group3(val id: String) : SomethingSomething

    data class ClassWithNotEmptyValidation(@NotEmpty val name: String, val make: CarMake)

    data class ClassWithInvalidValidation(
        @ThrowsRuntimeExceptionConstraint val name: String,
        val make: CarMake
    )

    // a constraint with no provided or registered validator class implementation
    data class ClassWithNoValidatorImplConstraint(
        @NoValidatorImplConstraint val name: String,
        val make: CarMake
    )

    data class ClassWithMethodValidationException(
        @NotEmpty val name: String,
        val passphrase: String
    ) {
        @PostConstructValidation(fields = ["passphrase"])
        fun checkPassphrase(): PostConstructValidationResult {
            throw RuntimeException("oh noe")
        }
    }

    data class ClassWithBoolean(val foo: Boolean)

    data class ClassWithSeqBooleans(val foos: List<Boolean>)

    data class ClassInjectStringWithDefault(@JacksonInject val string: String = "DefaultHello")

    data class ClassInjectInt(@JacksonInject val age: Int)

    data class ClassInjectNullableInt(@JacksonInject val age: Int?)

    data class ClassInjectNullableString(@JacksonInject val string: String?)

    data class ClassInjectString(@JacksonInject val string: String)

    data class ClassWithCustomDecimalFormat(
        @JsonDeserialize(using = MyBigDecimalDeserializer::class) val myBigDecimal: BigDecimal,
        @JsonDeserialize(using = MyBigDecimalDeserializer::class) val optMyBigDecimal: BigDecimal?
    )

    data class ClassWithLongAndDeserializer(
        @JsonDeserialize(contentAs = java.lang.Long::class)
        val long: Number
    )

    data class ClassWithNullableLongAndDeserializer(
        @JsonDeserialize(contentAs = java.lang.Long::class)
        val optLong: Long?
    )

    data class ClassWithTwoConstructors(val id: Long, @NotEmpty val name: String) {
        constructor(id: Long) : this(id, "New User")
    }

    data class ClassWithThreeConstructors(val id: Long, @NotEmpty val name: String) {
        constructor(id: Long) : this(id, "New User")

        constructor(name: String) : this(42, name)
    }

    data class Person(
        val id: Int,
        @NotEmpty val name: String,
        val dob: LocalDate? = null,
        val age: Int? = null,
        @Suppress("PropertyName") val age_with_default: Int? = null,
        val nickname: String = "unknown",
        val address: Address? = null
    )

    data class PersonNoDefaultAge(
        val id: Int,
        @NotEmpty val name: String,
        val dob: LocalDate? = null,
        val age: Int?,
        @Suppress("PropertyName") val age_with_default: Int? = null,
        val nickname: String = "unknown",
        val address: Address? = null
    )

    class MyBigDecimalDeserializer : StdDeserializer<BigDecimal>(BigDecimal::class.java) {
        override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): BigDecimal? {
            val jsonNode: ValueNode = jp.codec.readTree(jp)
            return when (jsonNode) {
                is NullNode -> null
                else -> BigDecimal(jsonNode.asText()).setScale(2, RoundingMode.HALF_UP)
            }
        }

        @Deprecated("Deprecated in Java", ReplaceWith(""))
        override fun getEmptyValue(): BigDecimal = BigDecimal(0)
    }

    // allows parsing a JSON null into a null type for this object
    class NullableCarMakeDeserializer : StdDeserializer<CarMake>(CarMake::class.java) {
        override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): CarMake? {
            val jsonNode: ValueNode = jp.codec.readTree(jp)
            return if (jsonNode.isNull) {
                null
            } else {
                CarMake.valueOf(jsonNode.asText())
            }
        }
    }

    data class WithNullableCarMake(
        val nonNullCarMake: CarMake,
        @JsonDeserialize(using = NullableCarMakeDeserializer::class) val nullableCarMake: CarMake?
    )

    data class WithDefaultNullableCarMake(
        @JsonDeserialize(using = NullableCarMakeDeserializer::class) val nullableCarMake: CarMake? = CarMake.HONDA
    )

    // is a string type but json types are passed as the value
    data class WithJsonStringType(val value: String)

    data class WithEmptyJsonProperty(@JsonProperty val foo: String)

    data class WithNonemptyJsonProperty(@JsonProperty("bar") val foo: String)

    data class WithoutJsonPropertyAnnotation(val foo: String)

    data class NamingStrategyJsonProperty(@JsonProperty val longFieldName: String)

    data class Address(
        @NotEmpty val street: String? = null,
        @NotEmpty val city: String,
        @NotEmpty val state: String
    ) {

        @PostConstructValidation
        fun validateState(): PostConstructValidationResult =
            PostConstructValidationResult.validIfTrue(
                { state == "CA" || state == "MD" || state == "WI" },
                { "state must be one of <CA, MD, WI>" }
            )
    }

    interface ClassTrait {
        @get:JsonProperty("fedoras")
        @get:Size(min = 1, max = 2)
        val names: List<String>

        @get:Min(1L)
        val age: Int
    }

    data class ClassTraitImpl(override val names: List<String>, @JsonProperty("oldness") override val age: Int) :
        ClassTrait

    data class LimiterProfile(val id: Long, val name: String)
    data class LimiterProfiles(val profiles: List<LimiterProfile>? = null) {
        companion object {
            operator fun invoke(profiles: List<LimiterProfile>): LimiterProfiles = if (profiles.isEmpty()) {
                LimiterProfiles()
            } else {
                LimiterProfiles(profiles)
            }
        }
    }

    data class ClassWithBoxedPrimitives(val events: Int, val errors: Int)

    sealed interface ClusterRequest
    data class AddClusterRequest(
        @Size(min = 0, max = 30) val clusterName: String,
        @NotEmpty val job: String,
        val zone: String,
        val environment: String,
        val dtab: String,
        val address: String,
        val owners: String = "",
        val dedicated: Boolean = true,
        val enabled: Boolean = true,
        val description: String = ""
    ) : ClusterRequest {

        @PostConstructValidation(fields = ["clusterName"], payload = [PatternNotMatched::class])
        fun validateClusterName(): PostConstructValidationResult = validateName(clusterName)

        private fun validateName(name: String): PostConstructValidationResult {
            val regex = """[\w_.-]+""".toRegex(RegexOption.IGNORE_CASE)

            return PostConstructValidationResult.validIfTrue(
                { name.matches(regex) },
                { "$name is invalid. Only alphanumeric and special characters from (_,-,.,>) are allowed." },
                PatternNotMatched(name, regex)
            )
        }
    }

    data class PatternNotMatched(val pattern: String, val regex: Regex) : Payload

    data class Points(val first: Point, val second: Point)

    data class Point(val abscissa: Int, val ordinate: Int) {
        fun area(): Int = abscissa * ordinate
    }

    interface PointMixin {
        @get:JsonProperty("x")
        @get:Min(0)
        @get:Max(100)
        val abscissa: Int

        @get:JsonProperty("y")
        @get:Min(0)
        @get:Max(100)
        val ordinate: Int

        @get:JsonIgnore
        val area: Int
    }

    @JsonIgnoreType
    data class IgnoreMe(val id: Long)

    data class ContainsAnIgnoreTypeNoDefault(val ignored: IgnoreMe, val name: String, val description: String)
    data class ContainsAnIgnoreTypeWithDefault(
        val ignored: IgnoreMe = IgnoreMe(42L),
        val name: String,
        val description: String
    )

    object Views {
        open class Public
        class Internal : Public()
    }

    data class Item(
        @JsonView(value = [Views.Public::class]) val id: Long = 1L,
        @JsonView(value = [Views.Public::class]) val name: String = "",
        @JsonView(value = [Views.Internal::class]) val owner: String = ""
    )

    data class ItemSomeViews(
        @JsonView(value = [Views.Public::class]) val id: Long = 1L,
        @JsonView(value = [Views.Public::class]) val name: String = "",
        val owner: String = ""
    )

    data class ItemNoDefaultForView(@JsonView(value = [Views.Public::class]) val name: String)

    @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy::class)
    data class ClassWithKebabCase(val pleaseUseKebabCase: Boolean)

    @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy::class)
    interface KebabCaseMixin

    data class ClassShouldUseKebabCaseFromMixin(val willThisGetTheRightCasing: Boolean)

    @JsonNaming
    data class UseDefaultNamingStrategy(val thisFieldShouldUseDefaultPropertyNamingStrategy: Boolean)

}



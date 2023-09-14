package angstromio.json

import angstromio.json.exceptions.DataClassFieldMappingException
import angstromio.json.exceptions.DataClassMappingException
import angstromio.util.extensions.Strings
import arrow.core.Either
import arrow.integrations.jackson.module.registerArrowModule
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TreeTraversingParser
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.matchers.be
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.time.Duration.Companion.hours
import java.time.Duration as JavaDuration

class ObjectMapperTest : AbstractObjectMapperTest() {
    private val testISO8601DateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME // "2014-05-30T03:57:59.302Z"

    private val defaultBigDecimalMathContext = MathContext(4, RoundingMode.HALF_DOWN)

    class ZeroOrOneDeserializer : JsonDeserializer<TestClasses.ZeroOrOne>() {
        override fun deserialize(
            jsonParser: JsonParser, deserializationContext: DeserializationContext
        ): TestClasses.ZeroOrOne = when (jsonParser.valueAsString) {
            "zero" -> TestClasses.Zero
            "one" -> TestClasses.One
            else -> throw JsonMappingException(null, "Invalid value")
        }
    }

    class MixInAnnotationsModule : SimpleModule() {
        init {
            setMixInAnnotation(TestClasses.Point::class.java, TestClasses.PointMixin::class.java)
            setMixInAnnotation(
                TestClasses.ClassShouldUseKebabCaseFromMixin::class.java, TestClasses.KebabCaseMixin::class.java
            )
        }
    }

    private val alice: TestClasses.Person =
        TestClasses.Person(id = 1, name = "Alice", age = 32, age_with_default = 24, nickname = "751A69C24D97009")
    private val aliceJson: String = """{"id":1,"name":"Alice","age":32,"age_with_default":24,"nickname":"751A69C24D97009"}"""

    override val mapper: ObjectMapper by lazy {
        val m: ObjectMapper = ObjectMapper().defaultMapper()
        m.registerModule(MixInAnnotationsModule())
        m.registerArrowModule()
    }

    init {
        test("constructors") {
            // copies the underlying Jackson object mapper
            assertNotNull(mapper.makeCopy())
            // underlying mapper needs to have a JsonFactory of type YAMLFactory
            assertThrows<IllegalArgumentException> {
                mapper.toYamlObjectMapper()
            }
            assertNotNull(
                yamlObjectMapper().toYamlObjectMapper()
            )
            assertNotNull(mapper.toCamelCaseObjectMapper())
            assertNotNull(mapper.toSnakeCaseMapper())
        }

        test("mapper register module") {
            val testMapper = jacksonObjectMapper()

            val simpleJacksonModule = SimpleModule()
            simpleJacksonModule.addDeserializer(TestClasses.ZeroOrOne::class.java, ZeroOrOneDeserializer())
            testMapper.registerModule(simpleJacksonModule)

            // regular mapper (without the custom deserializer) -- doesn't readValue
            assertThrows<JsonMappingException> {
                mapper.readValue<TestClasses.ClassWithZeroOrOne>("{\"id\" :\"zero\"}")
            }

            // mapper with custom deserializer -- parses correctly
            testMapper.readValue<TestClasses.ClassWithZeroOrOne>("{\"id\" :\"zero\"}") should be(
                TestClasses.ClassWithZeroOrOne(TestClasses.Zero)
            )
            testMapper.readValue<TestClasses.ClassWithZeroOrOne>("{\"id\" :\"one\"}") should be(
                TestClasses.ClassWithZeroOrOne(TestClasses.One)
            )
            assertThrows<JsonMappingException> {
                testMapper.readValue<TestClasses.ClassWithZeroOrOne>("{\"id\" :\"two\"}")
            }
        }

        test("class with an injectable field is not constructed from JSON") {
            // no field injection configured and parsing proceeds as normal
            val result = readValue<TestClasses.ClassWithFooClassInject>("""{"foo_class":{"id":"11"}}""")
            result.fooClass should be(TestClasses.FooClass("11"))
        }

        test("class with a defaulted injectable field is constructed from the default") {
            // no field injection configured and default value is not used since a value is passed in the JSON.
            val result =
                readValue<TestClasses.ClassWithFooClassInjectAndDefault>("""{"foo_class":{"id":"1"}}""".trimMargin())
            result.fooClass should be(TestClasses.FooClass("1"))
        }

        test("injectable field should use default when field not sent in json") {
            readValue<TestClasses.ClassInjectStringWithDefault>("""{}""") should be(
                TestClasses.ClassInjectStringWithDefault("DefaultHello")
            )
        }

        test("injectable field shouldNot use default when field sent in json") {
            readValue<TestClasses.ClassInjectStringWithDefault>("""{"string": "123"}""") should be(
                TestClasses.ClassInjectStringWithDefault("123")
            )
        }

        test("injectable field should use null assumed default when field not sent in json") {
            readValue<TestClasses.ClassInjectNullableString>("""{}""") should be(
                TestClasses.ClassInjectNullableString(
                    null
                )
            )
        }

        test("injectable field should throw exception when no value is passed in the json") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassInjectString>("""{}""")
            }
        }

        test("regular mapper handles unknown properties when json provides MORE fields than data class") {
            // regular mapper -- doesn't fail
            mapper.readValue<TestClasses.DataClass>("""{"id":12345,"name":"gadget","extra":"fail"}""")

            // mapper = loose, data class = annotated strict --> Fail
            assertThrows<JsonMappingException> {
                mapper.readValue<TestClasses.StrictDataClass>("""{"id":12345,"name":"gadget","extra":"fail"}""")
            }
        }

        test("regular mapper handles unknown properties when json provides LESS fields than data class") {
            // regular mapper -- doesn't fail
            mapper.readValue<TestClasses.ClassWithNullable>("""{"value":12345,"extra":"fail"}""")

            // mapper = loose, data class = annotated strict --> Fail
            assertThrows<JsonMappingException> {
                mapper.readValue<TestClasses.StrictDataClassWithNullable>("""{"value":12345,"extra":"fail"}""")
            }
        }

        test("regular mapper handles unknown properties") {
            // regular mapper -- doesn't fail (no field named 'flame')
            mapper.readValue<TestClasses.ClassIdAndNullable>("""{"id":12345,"flame":"gadget"}""")

            // mapper = loose, data class = annotated strict --> Fail (no field named 'flame')
            assertThrows<JsonMappingException> {
                mapper.readValue<TestClasses.StrictDataClassIdAndNullable>("""{"id":12345,"flame":"gadget"}""")
            }
        }

        test("mapper with deserialization config fails on unknown properties") {
            val testMapper = jsonMapper {
                addModule(kotlinModule())
                addModule(beanDeserializerModule())
                enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }

            // mapper = strict, data class = unannotated --> Fail
            assertThrows<JsonMappingException> {
                testMapper.readValue<TestClasses.DataClass>(
                    """
          |{
          |  "id": 12345,
          |  "name": "gadget",
          |  "extra": "fail"
          |}
          |""".trimMargin()
                )
            }

            // mapper = strict, data class = annotated strict --> Fail
            assertThrows<JsonMappingException> {
                testMapper.readValue<TestClasses.StrictDataClass>(
                    """
          |{
          |  "id": 12345,
          |  "name": "gadget",
          |  "extra": "fail"
          |}
          |""".trimMargin()
                )
            }

            // mapper = strict, data class = annotated loose --> Parse
            testMapper.readValue<TestClasses.LooseDataClass>(
                """
        |{
        |  "id": 12345,
        |  "name": "gadget",
        |  "extra": "pass"
        |}
        |""".trimMargin()
            )
        }

        test("mapper with additional configuration handles unknown properties") {
            // test with additional configuration set on mapper
            val testMapper = jsonMapper {
                addModule(kotlinModule())
                addModule(beanDeserializerModule())
                enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            }

            // mapper = strict, data class = unannotated --> Fail
            assertThrows<JsonMappingException> {
                testMapper.readValue<TestClasses.DataClass>(
                    """
          |{
          |  "id": 12345,
          |  "name": "gadget",
          |  "extra": "fail"
          |}
          |""".trimMargin()
                )
            }

            // mapper = strict, data class = annotated strict --> Fail
            assertThrows<JsonMappingException> {
                testMapper.readValue<TestClasses.StrictDataClass>(
                    """
          |{
          |  "id": 12345,
          |  "name": "gadget",
          |  "extra": "fail"
          |}
          |""".trimMargin()
                )
            }

            // mapper = strict, data class = annotated loose --> Parse
            testMapper.readValue<TestClasses.LooseDataClass>(
                """
        |{
        |  "id": 12345,
        |  "name": "gadget",
        |  "extra": "pass"
        |}
        |""".trimMargin()
            )
        }

        test("support camel case mapper") {
            val camelCaseObjectMapper =
                mapper.toCamelCaseObjectMapper()//KotlinObjectMapper.camelCaseObjectMapper(mapper.underlying)

            camelCaseObjectMapper.readValue<Map<String, String>>("""{"firstName": "Bob"}""") should be(
                mapOf("firstName" to "Bob")
            )
        }

        test("support snake case mapper") {
            val snakeCaseObjectMapper =
                mapper.toSnakeCaseMapper()//KotlinObjectMapper.snakeCaseObjectMapper(mapper.underlying)

            val person = TestClasses.CamelCaseSimplePersonNoAnnotation(myName = "Bob")

            val serialized = snakeCaseObjectMapper.writeValueAsString(person)
            serialized should be("""{"my_name":"Bob"}""")
            snakeCaseObjectMapper.readValue<TestClasses.CamelCaseSimplePersonNoAnnotation>(serialized) should be(person)
        }

        test("support yaml mapper") {
            val yamlObjectMapper = yamlObjectMapper(mapper)

            val person = TestClasses.CamelCaseSimplePersonNoAnnotation(myName = "Bob")

            val serialized = yamlObjectMapper.writeValueAsString(person)
            // default PropertyNamingStrategy for the generate YAML object mapper is snake_case
            serialized should be(
                """---
                              |my_name: "Bob"
                              |""".trimMargin()
            )
            yamlObjectMapper.readValue<TestClasses.CamelCaseSimplePersonNoAnnotation>(serialized) should be(person)

            // but we can also update to a camelCase PropertyNamingStrategy:
            val camelCaseObjectMapper =
                yamlObjectMapper.toCamelCaseObjectMapper()//KotlinObjectMapper.camelCaseObjectMapper(yamlObjectMapper.underlying)
            val serializedCamelCase = camelCaseObjectMapper.writeValueAsString(person)
            serializedCamelCase should be(
                """---
                                       |myName: "Bob"
                                       |""".trimMargin()
            )
            camelCaseObjectMapper.readValue<TestClasses.CamelCaseSimplePersonNoAnnotation>(
                serializedCamelCase
            ) should be(person)
        }

        test("enums") {
            val expectedInstance = TestClasses.BasicDate(TestClasses.Month.FEB, 29, 2020, TestClasses.Weekday.SAT)
            val expectedStr = """{"month":"FEB","day":29,"year":2020,"weekday":"SAT"}"""
            readValue<TestClasses.BasicDate>(expectedStr) should be(expectedInstance)
            generate(expectedInstance) should be(expectedStr) // ser

            // test with default kotlin module
            val defaultKotlinObjectMapper = jacksonObjectMapper()
            defaultKotlinObjectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            deserialize<TestClasses.BasicDate>(defaultKotlinObjectMapper, expectedStr) should be(expectedInstance)

            // case-insensitive mapper feature works with Kotlin defined enums (like Java)
            val caseInsensitiveEnumMapper = jsonMapper {
                addModule(kotlinModule())
                addModule(beanDeserializerModule())
                enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            }

            val expectedCaseInsensitiveStr = """{"month":"feb","day":29,"year":2020,"weekday":"sat"}"""
            caseInsensitiveEnumMapper.readValue<TestClasses.BasicDate>(expectedCaseInsensitiveStr) should be(
                expectedInstance
            )

            // non-existent values
            val withErrors2 = listOf(
                "month: 'Nnn' is not a valid Month with valid values: JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC",
                "weekday: 'Flub' is not a valid Weekday with valid values: MON, TUE, WED, THU, FRI, SAT, SUN"
            )

            val expectedStr2 = """{"month":"Nnn","day":29,"year":2020,"weekday":"Flub"}"""
            val e2 = assertThrows<DataClassMappingException> {
                readValue<TestClasses.BasicDate>(expectedStr2)
            }
            val actualMessages2 = e2.errors.map { it.message }
            actualMessages2.joinToString(",") should be(withErrors2.joinToString(","))

            val invalidWithNullableEnum = """{"month":"Nnn"}"""
            val e3 = assertThrows<DataClassMappingException> {
                readValue<TestClasses.WithNullableEnum>(invalidWithNullableEnum)
            }
            e3.errors.size shouldBeEqual 1
            val e3Error = e3.errors.first()
            e3Error.message should be("month: 'Nnn' is not a valid Month with valid values: JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC")
            e3Error.reason.message should be("'Nnn' is not a valid Month with valid values: JAN, FEB, MAR, APR, MAY, JUN, JUL, AUG, SEP, OCT, NOV, DEC")
            e3Error.reason.detail::class should be(DataClassFieldMappingException.JsonProcessingError::class)
            (e3Error.reason.detail as DataClassFieldMappingException.JsonProcessingError).cause::class should be(
                InvalidFormatException::class
            )
            (e3Error.reason.detail as DataClassFieldMappingException.JsonProcessingError).cause.message shouldNot beNull()

            val validWithNullableEnumJson = """{"month":"APR"}"""
            val validWithNullableEnum = readValue<TestClasses.WithNullableEnum>(validWithNullableEnumJson)
            validWithNullableEnum.month shouldNot beNull()
            validWithNullableEnum.month should be(TestClasses.Month.APR)
        }

        // based on Jackson test to ensure compatibility:
        test("deserialization#generic types") {
            readValue<TestClasses.GenericTestClass<Int>>("""{"data" : 3}""") should be(TestClasses.GenericTestClass(3))
            readValue<TestClasses.GenericTestClass<TestClasses.DataClass>>("""{"data" : {"id" : 123, "name" : "foo"}}""") should be(
                TestClasses.GenericTestClass(TestClasses.DataClass(123, "foo"))
            )
            readValue<TestClasses.ClassWithGeneric<TestClasses.DataClass>>(
                """{"inside" : { "data" : {"id" : 123, "name" : "foo"}}}"""
            ) should be(TestClasses.ClassWithGeneric(TestClasses.GenericTestClass(TestClasses.DataClass(123, "foo"))))

            readValue<TestClasses.GenericTestClass<String>>("""{"data" : "Hello, World"}""") should be(
                TestClasses.GenericTestClass("Hello, World")
            )
            readValue<TestClasses.GenericTestClass<Double>>("""{"data" : 3.14}""") should be(
                TestClasses.GenericTestClass(3.14)
            )

            readValue<TestClasses.ClassWithNullableGeneric<Int>>("""{"inside": {"data": 3}}""") should be(
                TestClasses.ClassWithNullableGeneric(TestClasses.GenericTestClass(3))
            )

            readValue<TestClasses.ClassWithTypes<String, Int>>("""{"first": "Bob", "second" : 42}""") should be(
                TestClasses.ClassWithTypes("Bob", 42)
            )
            readValue<TestClasses.ClassWithTypes<Int, Float>>("""{"first": 127, "second" : 39.0}""") should be(
                TestClasses.ClassWithTypes(127, 39.0f)
            )

            readValue<TestClasses.ClassWithMapTypes<String, Float>>(
                """{"data": {"pi": 3.14, "inverse fine structure constant": 137.035}}"""
            ) should be(
                TestClasses.ClassWithMapTypes(
                    mapOf("pi" to 3.14f, "inverse fine structure constant" to 137.035f)
                )
            )
            readValue<TestClasses.ClassWithManyTypes<Int, Float, String>>(
                """{"one": 1, "two": 3.1, "three": "Hello, World!"}"""
            ) should be(
                TestClasses.ClassWithManyTypes(1, 3.1f, "Hello, World!")
            )

            val result = TestClasses.Page(
                listOf(
                    TestClasses.Person(1, "Bob Marley", null, 32, 32, "Music Master"),
                    TestClasses.Person(2, "Jimi Hendrix", null, 27, null, "Melody Man")
                ), 5, null, null
            )
            val input = """
        |{
        |  "data": [
        |    {"id": 1, "name": "Bob Marley", "age": 32, "age_with_default": 32, "nickname": "Music Master"},
        |    {"id": 2, "name": "Jimi Hendrix", "age": 27, "nickname": "Melody Man"}
        |  ],
        |  "page_size": 5
        |}
            """.trimMargin()

            val defaultKotlinObjectMapper = jacksonObjectMapper()
            defaultKotlinObjectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            deserialize<TestClasses.Page<TestClasses.Person>>(defaultKotlinObjectMapper, input) should be(result)

            // test with KotlinObjectMapper
            deserialize<TestClasses.Page<TestClasses.Person>>(mapper, input) should be(result)
        }

        // tests for GH #547
        test("deserialization#generic types 2") {
            val aGeneric = mapper.readValue<TestClasses.AGeneric<TestClasses.B>>("""{"b":{"value":"string"}}""")
            aGeneric.b should be(TestClasses.B("string"))

            val c = mapper.readValue<TestClasses.C>("""{"a":{"b":{"value":"string"}}}""")
            c.a.b should be(TestClasses.B("string"))

            val aaGeneric =
                mapper.readValue<TestClasses.AAGeneric<TestClasses.B, Int>>("""{"b":{"value":"string"},"c":42}""")
            aaGeneric.b should be(TestClasses.B("string"))
            aaGeneric.c should be(42)

            val aaGeneric2 =
                mapper.readValue<TestClasses.AAGeneric<TestClasses.B, TestClasses.D>>("""{"b":{"value":"string"},"c":{"value":42}}""")
            aaGeneric2.b should be(TestClasses.B("string"))
            aaGeneric2.c should be(TestClasses.D(42))

            val eGeneric =
                mapper.readValue<TestClasses.E<TestClasses.B, TestClasses.D, Int>>("""{"a": {"b":{"value":"string"},"c":{"value":42}}, "b":42}""")
            eGeneric.a.b should be(TestClasses.B("string"))
            eGeneric.a.c should be(TestClasses.D(42))
            eGeneric.b should be(42)

            val fGeneric =
                mapper.readValue<TestClasses.F<TestClasses.B, Int, TestClasses.D, TestClasses.AGeneric<TestClasses.B>, Int, String>>(
                    """
          |{
          |  "a":{"value":"string"},
          |  "b":[1,2,3],
          |  "c":{"value":42},
          |  "d":{"b":{"value":"string"}},
          |  "e":{"right":"forty-two"}
          |}
          |""".trimMargin()
                )
            fGeneric.a should be(TestClasses.B("string"))
            fGeneric.b should be(listOf(1, 2, 3))
            fGeneric.c should be(TestClasses.D(42))
            fGeneric.d.b should be(TestClasses.B("string"))
            fGeneric.e should be(Either.Right("forty-two"))

            val fGeneric2 =
                mapper.readValue<TestClasses.F<TestClasses.B, Int, TestClasses.D, TestClasses.AGeneric<TestClasses.B>, Int, String>>(
                    """
         |{
         |  "a":{"value":"string"},
         |  "b":[1,2,3],
         |  "c":{"value":42},
         |  "d":{"b":{"value":"string"}},
         |  "e":{"left":42}
         |}
         |""".trimMargin()
                )
            fGeneric2.a should be(TestClasses.B("string"))
            fGeneric2.b should be(listOf(1, 2, 3))
            fGeneric2.c should be(TestClasses.D(42))
            fGeneric2.d.b should be(TestClasses.B("string"))
            fGeneric2.e should be(Either.Left(42))

            // requires polymorphic handling with @JsonTypeInfo
            val withTypeBounds = mapper.readValue<TestClasses.WithTypeBounds<TestClasses.BoundaryA>>(
                """
        |{
        |  "a": {"type":"a", "value":"Guineafowl"}
        |}
        |""".trimMargin()
            )
            withTypeBounds.a should be(TestClasses.BoundaryA("Guineafowl"))

            // uses a specific json deserializer
            val someNumberType = mapper.readValue<TestClasses.SomeNumberType<Integer>>(
                """
        |{
        |  "n": 42
        |}
        |""".trimMargin()
            )
            someNumberType.n should be(42)

            // multi-parameter non-collection type
            val gGeneric =
                mapper.readValue<TestClasses.G<TestClasses.B, Int, TestClasses.D, TestClasses.AGeneric<TestClasses.B>, Int, String>>(
                    """
          |{
          |  "gg": {
          |    "t":{"value":"string"},
          |    "u":42,
          |    "v":{"value":42},
          |    "x":{"b":{"value":"string"}},
          |    "y":137,
          |    "z":"string"
          |  }
          |}
          |""".trimMargin()
                )
            gGeneric.gg.t.value should be("string")
            gGeneric.gg.u should be(42)
            gGeneric.gg.v.value should be(42)
            gGeneric.gg.x.b should be(TestClasses.B("string"))
            gGeneric.gg.y should be(137)
            gGeneric.gg.z should be("string")
        }

        test("JsonProperty#annotation inheritance") {
            val aumJson = """{"i":1,"j":"J"}"""
            val aum = readValue<TestClasses.Aum>(aumJson)
            aum should be(TestClasses.Aum(1, "J"))
            mapper.writeValueAsString(TestClasses.Aum(1, "J")) should be(aumJson)

            val testJson = """{"fedoras":["felt","straw"],"oldness":27}"""
            val testClass = readValue<TestClasses.ClassTraitImpl>(testJson)
            testClass should be(TestClasses.ClassTraitImpl(listOf("felt", "straw"), 27))
            mapper.writeValueAsString(TestClasses.ClassTraitImpl(listOf("felt", "straw"), 27)) should be(
                testJson
            )
        }

        test("simple tests#readValue super simple") {
            val foo = readValue<TestClasses.SimplePerson>("""{"name": "Steve"}""")
            foo should be(TestClasses.SimplePerson("Steve"))
        }

        test("simple tests#readValue super simple -- YAML") {
            val yamlMapper = yamlObjectMapper(mapper)
            val foo = yamlMapper.readValue<TestClasses.SimplePerson>("""name: Steve""")
            foo should be(TestClasses.SimplePerson("Steve"))

            yamlMapper.writeValueAsString(foo) should be(
                """---
                                                      |name: "Steve"
                                                      |""".trimMargin()
            )
        }

        test("get PropertyNamingStrategy") {
            val namingStrategy = mapper.propertyNamingStrategy
            namingStrategy shouldNot beNull()
        }

        test("simple tests#readValue simple") {
            val foo = readValue<TestClasses.SimplePerson>("""{"name": "Steve"}""")
            foo should be(TestClasses.SimplePerson("Steve"))
        }

        test("simple tests#readValue CamelCase simple person") {
            val foo = readValue<TestClasses.CamelCaseSimplePerson>("""{"myName": "Steve"}""")
            foo should be(TestClasses.CamelCaseSimplePerson("Steve"))
        }

        test("simple tests#readValue json") {
            val person = readValue<TestClasses.Person>(aliceJson)
            person should be(alice)
        }

        test("simple tests#readValue json list of objects") {
            val json = listOf(aliceJson, aliceJson).joinToString(",", "[", "]")
            val persons = readValue<List<TestClasses.Person>>(json)
            persons should be(listOf(alice, alice))
        }

        test("simple tests#readValue json list of ints") {
            val nums = readValue<List<Int>>("""[1,2,3]""")
            nums should be(listOf(1, 2, 3))
        }

        test("simple tests#readValue json with extra field at end") {
            val person = readValue<TestClasses.Person>("""{"id" : 1,"name" : "Alice","age" : 32,"age_with_default" : 24,"nickname" : "751A69C24D97009","extra" : "extra"}""")
            person should be(alice)
        }

        test("simple tests#readValue json with extra field in middle") {
            val person = readValue<TestClasses.Person>("""{"id" : 1,"name" : "Alice","age" : 32,"extra" : "extra","age_with_default" : 24,"nickname" : "751A69C24D97009"}""")
            person should be(alice)
        }

        test("simple tests#readValue json with extra field name with dot") {
            val person = readValue<TestClasses.PersonWithDottedName>("""{"id" : 1,"name.last" : "Jackson"}""")

            person should be(
                TestClasses.PersonWithDottedName(
                    id = 1, lastName = "Jackson"
                )
            )
        }

        test("simple tests#readValue json with missing 'id' and 'name' field and invalid age field") {
            assertJsonParse<TestClasses.Person>(
                """ {"age" : "foo","age_with_default" : 24,"nickname" : "751A69C24D97009"}""",
                withErrors = listOf(
                    "age: 'foo' is not a valid Integer", "id: field is required", "name: field is required"
                )
            )
        }

        test("simple tests#readValue nested json with missing fields") {
            assertJsonParse<TestClasses.Car>(
                """{"id" : 0,"make": "Foo","year": 2000,"passengers" : [ { "id": "-1", "age": "blah" } ]}""", withErrors = listOf(
                    "make: 'Foo' is not a valid CarMake with valid values: FORD, VOLKSWAGEN, TOYOTA, HONDA",
                    "model: field is required",
                    "owners: field is required",
                    "passengers.age: 'blah' is not a valid Integer",
                    "passengers.name: field is required"
                )
            )
        }

        test("simple test#readValue Character") {
            assertJsonParse<TestClasses.ClassCharacter>(
                """{"c" : -1}""",
                withErrors = listOf(
                    "c: '-1' is not a valid Character"
                )
            )
        }

        test("simple tests#readValue json with missing 'nickname' field that has a string default") {
            val person = readValue<TestClasses.Person>("""{"id" : 1,"name" : "Alice","age" : 32,"age_with_default" : 24}""")
            person should be(alice.copy(nickname = "unknown"))
        }

        test(
            "simple tests#readValue json with missing 'age' field that is an Nullable without a default should succeed"
        ) {
            readValue<TestClasses.Person>("""{"id" : 1,"name" : "Steve","age_with_default" : 20,"nickname" : "bob"}""")
        }

        test("simple tests#readValue json into JsonNode") {
            readValue<JsonNode>(aliceJson)
        }

        test("simple tests#generate json") {
            assertJson(alice, aliceJson)
        }

        test("simple tests#generate then readValue") {
            val json = generate(alice)
            val person = readValue<TestClasses.Person>(json)
            person should be(alice)
        }

        test("simple tests#generate then readValue Either type") {
            val l: Either<String, Int> = Either.Left("Q?")
            val r: Either<String, Int> = Either.Right(42)
            assertJson(l, """{"left":"Q?"}""")
            assertJson(r, """{"right":42}""")
        }

        test("simple tests#generate then readValue nested data class") {
            val origCar = TestClasses.Car(1, CarMake.FORD, "Explorer", 2001, listOf(alice, alice))
            val carJson = generate(origCar)
            val car = readValue<TestClasses.Car>(carJson)
            car should be(origCar)
        }

        test("simple tests#Prevent overwriting val in data class") {
            readValue<TestClasses.ClassWithVal>("""{"name" : "Bob","type" : "dog"}""") should be(TestClasses.ClassWithVal("Bob"))
        }

        test("simple tests#readValue WithEmptyJsonProperty then write and see if it equals original") {
            val withEmptyJsonProperty = """{
                |  "foo" : "abc"
                |}""".trimMargin()
            val obj = readValue<TestClasses.WithEmptyJsonProperty>(withEmptyJsonProperty)
            val json = mapper.writeValueAsString(obj, prettyPrint = true)
            json should be(withEmptyJsonProperty)
        }

        test("simple tests#readValue WithNonemptyJsonProperty then write and see if it equals original") {
            val withNonemptyJsonProperty = """{
                |  "bar" : "abc"
                |}""".trimMargin()
            val obj = readValue<TestClasses.WithNonemptyJsonProperty>(withNonemptyJsonProperty)
            val json = mapper.writeValueAsString(obj, prettyPrint = true)
            json should be(withNonemptyJsonProperty)
        }

        test(
            "simple tests#readValue WithoutJsonPropertyAnnotation then write and see if it equals original"
        ) {
            val withoutJsonPropertyAnnotation = """{
                |  "foo" : "abc"
                |}""".trimMargin()
            val obj = readValue<TestClasses.WithoutJsonPropertyAnnotation>(withoutJsonPropertyAnnotation)
            val json = mapper.writeValueAsString(obj, prettyPrint = true)
            json should be(withoutJsonPropertyAnnotation)
        }

        test(
            "simple tests#use default Jackson mapper without setting naming strategy to see if it remains camelCase to verify default Jackson behavior"
        ) {
            val objMapper = jacksonObjectMapper()

            val response = objMapper.writeValueAsString(TestClasses.NamingStrategyJsonProperty("abc"))
            response should be("""{"longFieldName":"abc"}""")
        }

        test(
            "simple tests#use default Jackson mapper after setting naming strategy and see if it changes to verify default Jackson behavior"
        ) {
            val objMapper = jacksonObjectMapper()
            objMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SnakeCaseStrategy())

            val response = objMapper.writeValueAsString(TestClasses.NamingStrategyJsonProperty("abc"))
            response should be("""{"long_field_name":"abc"}""")
        }

        test("enums#simple") {
            readValue<TestClasses.ClassWithEnum>(
                """{"name" : "Bob","make" : "FORD"}"""
            ) should be(TestClasses.ClassWithEnum("Bob", CarMake.FORD))
        }

        test("enums#complex") {
            JsonDiff.assertDiff(
                expected = TestClasses.ClassWithComplexEnums(
                "Bob",
                CarMake.VOLKSWAGEN,
                CarMake.FORD,
                listOf(CarMake.VOLKSWAGEN, CarMake.FORD),
                setOf(CarMake.FORD, CarMake.VOLKSWAGEN)
            ), actual = readValue<TestClasses.ClassWithComplexEnums>(
                """{"name" : "Bob","make" : "VOLKSWAGEN","make_opt" : "FORD","make_seq" : ["VOLKSWAGEN", "FORD"],"make_set" : ["FORD", "VOLKSWAGEN"]}"""),
                normalizeFn = { jsonNode -> // kotlin mutable set order is non-deterministic between test runs
                when (jsonNode) {
                    is ObjectNode -> {
                        val arrayNode = jsonNode.putArray("make_set")
                        setOf(CarMake.FORD, CarMake.VOLKSWAGEN).map { arrayNode.add(it.name) }
                        jsonNode.set("make_set", arrayNode)
                    }

                    else -> jsonNode
                }
            })
        }

        test("enums#complex case insensitive") {
            val caseInsensitiveEnumMapper = mapper.makeCopy().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)

            JsonDiff.assertDiff(expected = TestClasses.ClassWithComplexEnums(
                "Bob",
                CarMake.VOLKSWAGEN,
                CarMake.FORD,
                listOf(CarMake.VOLKSWAGEN, CarMake.FORD),
                setOf(CarMake.FORD, CarMake.VOLKSWAGEN)
            ), actual = caseInsensitiveEnumMapper.readValue<TestClasses.ClassWithComplexEnums>(
                """{"name" : "Bob","make" : "VOLKSWAGEN","make_opt" : "Ford","make_seq" : ["vOlkSWaGen", "foRD"],"make_set" : ["fORd", "vOlkSWaGen"]}"""
            ), normalizeFn = { jsonNode -> // kotlin mutable set order is non-deterministic between test runs
                when (jsonNode) {
                    is ObjectNode -> {
                        val arrayNode = jsonNode.putArray("make_set")
                        setOf(CarMake.FORD, CarMake.VOLKSWAGEN).map { arrayNode.add(it.name) }
                        jsonNode.set("make_set", arrayNode)
                    }

                    else -> jsonNode
                }
            })
        }

        test("enums#invalid enum entry") {
            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithEnum>("""{"name" : "Bob","make" : "foo"}""")
            }
            e.errors.map { it.message } should be(
                listOf("""make: 'foo' is not a valid CarMake with valid values: FORD, VOLKSWAGEN, TOYOTA, HONDA""")
            )
        }

        test("LocalDateTime support") {
            readValue<TestClasses.ClassWithDateTime>("""{"date_time" : "2014-05-30T03:57:59.302Z"}""") should be(
                TestClasses.ClassWithDateTime(
                    LocalDateTime.parse("2014-05-30T03:57:59.302Z", testISO8601DateTimeFormatter)
                )
            )
        }

        test("invalid LocalDateTime 1") {
            assertJsonParse<TestClasses.ClassWithDateTime>("""{"date_time" : ""}""",
                withErrors = listOf("date_time: error parsing ''")
            )
        }

        test("invalid LocalDateTime 2") {
            assertJsonParse<TestClasses.ClassWithIntAndDateTime>(
                """{"name" : "Bob","age" : "old","age2" : "1","age3" : "","date_time" : "today","date_time2" : "1","date_time3" : -1,"date_time4" : ""}""",
                withErrors = listOf(
                    "age3: error parsing ''",
                    "age: 'old' is not a valid Integer",
                    "date_time2: '1' is not a valid LocalDateTime",
                    "date_time3: '' is not a valid LocalDateTime",
                    "date_time4: error parsing ''",
                    "date_time: 'today' is not a valid LocalDateTime"
                )
            )
        }

        test("Duration serialize") {
            mapper.writeValueAsString(TestClasses.ClassWithKotlinDuration(3.hours)) should be("""{"duration":21600000000000}""")
            mapper.writeValueAsString(TestClasses.ClassWithJavaDuration(JavaDuration.ofHours(3))) should be("""{"duration":"PT3H"}""")
        }

        test("Duration deserialize") {
            // cannot currently deserialize Kotlin Durations: https://github.com/FasterXML/jackson-module-kotlin/commit/79d8e4ebc404015ae6047e4d84b79ace5480d266
            assertThrows<InvalidDefinitionException> {
                readValue<TestClasses.ClassWithKotlinDuration>("""{"duration": 21600000000000}""") should be(TestClasses.ClassWithKotlinDuration(3.hours))
            }

            // Java Durations work with the JSR310Module
            readValue<TestClasses.ClassWithJavaDuration>("""{"duration": "PT3H"}""") should be(TestClasses.ClassWithJavaDuration(JavaDuration.ofHours(3)))
        }

        test("Duration deserialize invalid") {
            // cannot currently deserialize kotlin durations: https://github.com/FasterXML/jackson-module-kotlin/commit/79d8e4ebc404015ae6047e4d84b79ace5480d266
            assertThrows<InvalidDefinitionException> {
                readValue<TestClasses.ClassWithKotlinDuration>("""{"duration": "0" }""")
            }

            // Java Durations work with the JSR310Module
            assertJsonParse<TestClasses.ClassWithJavaDuration>(
                """{"duration": "0" }""",
                withErrors = listOf("duration: '0' is not a valid Duration")
            )
        }

        test("escaped fields#long") {
            readValue<TestClasses.ClassWithEscapedLong>("""{"1-5" : 10}""") should be(TestClasses.ClassWithEscapedLong(`1-5` = 10))
        }

        test("escaped fields#string") {
            readValue<TestClasses.ClassWithEscapedString>("""{"1-5" : "10"}""") should be(TestClasses.ClassWithEscapedString(`1-5` = "10"))
        }

        test("escaped fields#non-unicode escaped") {
            readValue<TestClasses.ClassWithEscapedNormalString>("""{"a" : "foo"}""") should be(TestClasses.ClassWithEscapedNormalString("foo"))
        }

        test("escaped fields#unicode and non-unicode fields") {
            readValue<TestClasses.UnicodeNameClass>("""{"winning-id":23,"name":"the name of this"}""") should be(
                TestClasses.UnicodeNameClass(23, "the name of this")
            )
        }

        test("wrapped values#deser Map<Long, String>") {
            val obj = readValue<Map<Long, String>>("""{"11111111":"asdf"}""")
            val expected = mapOf(11111111L to "asdf")
            obj should be(expected)
        }

        test("wrapped values#deser Map<String, String>") {
            readValue<Map<String, String>>("""{"11111111":"asdf"}""") should be(mapOf("11111111" to "asdf"))
        }

        test("fail when TestClasses.ClassWithSeqLongs with null array element") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithSeqOfLongs>("""{"seq": [null]}""")
            }
        }

        test("fail when TestClasses.ClassWithArrayLong with null field in object") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithArrayLong>("""{"array": [null]}""")
            }
        }

        test("fail when TestClasses.ClassWithArrayBoolean with null field in object") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithArrayBoolean>("""{"array": [null]}""")
            }
        }

        test("A basic data class generates a JSON object with matching field values") {
            generate(TestClasses.DataClass(1, "Coda")) should be("""{"id":1,"name":"Coda"}""")
        }

        test("A basic data class is parseable from a JSON object with corresponding fields") {
            readValue<TestClasses.DataClass>("""{"id":111,"name":"Coda"}""") should be(
                TestClasses.DataClass(
                    111L, "Coda"
                )
            )
        }

        test("A basic data class is parseable from a JSON object with extra fields") {
            readValue<TestClasses.DataClass>("""{"id":1,"name":"Coda","derp":100}""") should be(
                TestClasses.DataClass(
                    1, "Coda"
                )
            )
        }

        test("A basic data class is not parseable from an incomplete JSON object") {
            assertThrows<Exception> {
                readValue<TestClasses.DataClass>("""{"id":1}""")
            }
        }

        test("A data class with lazy fields generates a JSON object with those fields evaluated") {
            generate(TestClasses.ClassWithLazyVal(1)) should be("""{"id":1,"woo":"yeah"}""")
        }

        test("A data class with lazy fields is parseable from a JSON object without those fields") {
            readValue<TestClasses.ClassWithLazyVal>("""{"id":1}""") should be(TestClasses.ClassWithLazyVal(1))
        }

        test("A data class with lazy fields is not parseable from an incomplete JSON object") {
            assertThrows<Exception> {
                readValue<TestClasses.ClassWithLazyVal>("""{}""")
            }
        }

        test("A data class with ignored members generates a JSON object without those fields") {
            generate(TestClasses.ClassWithIgnoredField(1)) should be("""{"id":1}""")
            generate(TestClasses.ClassWithIgnoredFieldsExactMatch(1)) should be("""{"id":1}""")
            generate(TestClasses.ClassWithIgnoredFieldsMatchAfterToSnakeCase(1)) should be("""{"id":1}""")
        }

        test("A data class with some ignored members is not parseable from an incomplete JSON object") {
            assertThrows<Exception> {
                readValue<TestClasses.ClassWithIgnoredField>("""{}""")
            }
            assertThrows<Exception> {
                readValue<TestClasses.ClassWithIgnoredFieldsMatchAfterToSnakeCase>("""{}""")
            }
        }

        test("A data class with transient members generates a JSON object with those fields") {
            generate(TestClasses.ClassWithTransientField(1)) should be("""{"id":1,"lol":"asdf"}""")
        }

        test("A data class with transient members is parseable from a JSON object without those fields") {
            readValue<TestClasses.ClassWithTransientField>("""{"id":1}""") should be(
                TestClasses.ClassWithTransientField(
                    1
                )
            )
        }

        test("A data class with some transient members is not parseable from an incomplete JSON object") {
            assertThrows<Exception> {
                readValue<TestClasses.ClassWithTransientField>("""{}""")
            }
        }

        test("A data class with lazy vals generates a JSON object without those fields") {
            generate(TestClasses.ClassWithLazyField(1)) should be("""{"id":1}""")
        }

        test("A data class with lazy vals is parseable from a JSON object without those fields") {
            readValue<TestClasses.ClassWithLazyField>("""{"id":1}""") should be(TestClasses.ClassWithLazyField(1))
        }

        test(
            "A data class with an overloaded field generates a JSON object with the nullary version of that field"
        ) {
            generate(TestClasses.ClassWithOverloadedField(1)) should be("""{"id":1}""")
        }

        test("A data class with an Nullable<String> member generates a field if the member is Some") {
            generate(TestClasses.ClassWithNullable("what")) should be("""{"value":"what"}""")
        }

        test(
            "A data class with an Nullable<String> member is parseable from a JSON object with that field"
        ) {
            readValue<TestClasses.ClassWithNullable>("""{"value":"what"}""") should be(TestClasses.ClassWithNullable("what"))
        }

        test(
            "A data class with an Nullable<String> member doesn't generate a field if the member is null"
        ) {
            generate(TestClasses.ClassWithNullable(null)) should be("""{}""")
        }

        test(
            "A data class with an Nullable<String> member is parseable from a JSON object without that field"
        ) {
            readValue<TestClasses.ClassWithNullable>("""{}""") should be(TestClasses.ClassWithNullable(null))
        }

        test(
            "A data class with an Nullable<String> member is parseable from a JSON object with a null value for that field"
        ) {
            readValue<TestClasses.ClassWithNullable>("""{"value":null}""") should be(TestClasses.ClassWithNullable(null))
        }

        test("A data class with a JsonNode member generates a field of the given type") {
            generate(TestClasses.ClassWithJsonNode(IntNode(2))) should be("""{"value":2}""")
        }

        test("issues#standalone map") {
            val map = readValue<Map<String, String>>("""{"one": "two"}""")
            map should be(mapOf("one" to "two"))
        }

        test("issues#data class with map") {
            val obj = readValue<TestClasses.ClassWithMap>(
                """{"map": {"one": "two"}}"""
            )
            obj.map should be(mapOf("one" to "two"))
        }

        test("issues#data class with multiple constructors") {
            assertThrows<InvalidDefinitionException> {
                readValue<TestClasses.ClassWithTwoConstructors>("""{"id":42,"name":"TwoConstructorsNoDefault"}"""")
            }
        }

        test("issues#data class with multiple (3) constructors") {
            assertThrows<InvalidDefinitionException> {
                readValue<TestClasses.ClassWithThreeConstructors>("""{"id":42,"name":"ThreeConstructorsNoDefault"}"""")
            }
        }

        test("issues#data class nested within an object") {
            readValue<TestClasses.Obj.NestedClassInObject>("""{"id": "foo"}""") should be(TestClasses.Obj.NestedClassInObject(id = "foo"))
        }

        test(
            "issues#data class nested within an object with member that is also a data class in an object"
        ) {
            readValue<TestClasses.Obj.NestedClassInObjectWithNestedClassInObjectParam>("""{"nested": {"id": "foo"}}""") should be(
                TestClasses.Obj.NestedClassInObjectWithNestedClassInObjectParam(
                    nested = TestClasses.Obj.NestedClassInObject(id = "foo")
                )
            )
        }

        test("deserialization#data class nested within a companion object works") {
            readValue<TestClasses.TypeAndCompanion.Companion.NestedClassInCompanion>("""{"id": "foo"}""") should be(TestClasses.TypeAndCompanion.Companion.NestedClassInCompanion(id = "foo"))
        }


        test("deserialization#data class nested within a class") {
            readValue<TestClasses.OuterObject.InnerClass.NestedClassInClass>("""{"id":"foo"}""") should be(
                TestClasses.OuterObject.InnerClass.NestedClassInClass(
                    id = "foo"
                )
            )
        }

        test("deserialization#data class with set of longs") {
            val obj = readValue<TestClasses.ClassWithSetOfLongs>("""{"set": [5000000, 1, 2, 3, 1000]}""")
            obj.set.toList().sorted() should be(listOf(1L, 2L, 3L, 1000L, 5000000L))
        }

        test("deserialization#data class with seq of longs") {
            val obj = readValue<TestClasses.ClassWithSeqOfLongs>("""{"seq": [%s]}""".format((1004..1500).joinToString()))
            obj.seq.sorted() should be(expected = (1004L..1500L).toList().toTypedArray())
        }

        test("deserialization#nested data class with collection of longs") {
            val idsStr = (1004..1500).joinToString()
            val obj = readValue<TestClasses.ClassWithNestedSeqLong>("""{"seq_class" : {"seq": [%s]},"set_class" : {"set": [%s]}}""".format(idsStr, idsStr))
            obj.seqClass.seq.sorted() should be(expected = (1004L..1500L).toList().toTypedArray())
            obj.setClass.set.toList().sorted() should be(expected = (1004L..1500L).toList().toTypedArray())
        }

        test("deserialization#complex without companion class") {
            val json = """{
                 "entity_ids" : [ 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014, 1015, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1023, 1024, 1025, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1033, 1034, 1035, 1036, 1037, 1038, 1039, 1040, 1041, 1042, 1043, 1044, 1045, 1046, 1047, 1048, 1049, 1050, 1051, 1052, 1053, 1054, 1055, 1056, 1057, 1058, 1059, 1060, 1061, 1062, 1063, 1064, 1065, 1066, 1067, 1068, 1069, 1070, 1071, 1072, 1073, 1074, 1075, 1076, 1077, 1078, 1079, 1080, 1081, 1082, 1083, 1084, 1085, 1086, 1087, 1088, 1089, 1090, 1091, 1092, 1093, 1094, 1095, 1096, 1097, 1098, 1099, 1100, 1101, 1102, 1103, 1104, 1105, 1106, 1107, 1108, 1109, 1110, 1111, 1112, 1113, 1114, 1115, 1116, 1117, 1118, 1119, 1120, 1121, 1122, 1123, 1124, 1125, 1126, 1127, 1128, 1129, 1130, 1131, 1132, 1133, 1134, 1135, 1136, 1137, 1138, 1139, 1140, 1141, 1142, 1143, 1144, 1145, 1146, 1147, 1148, 1149, 1150, 1151, 1152, 1153, 1154, 1155, 1156, 1157, 1158, 1159, 1160, 1161, 1162, 1163, 1164, 1165, 1166, 1167, 1168, 1169, 1170, 1171, 1172, 1173, 1174, 1175, 1176, 1177, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194, 1195, 1196, 1197, 1198, 1199, 1200, 1201, 1202, 1203, 1204, 1205, 1206, 1207, 1208, 1209, 1210, 1211, 1212, 1213, 1214, 1215, 1216, 1217, 1218, 1219, 1220, 1221, 1222, 1223, 1224, 1225, 1226, 1227, 1228, 1229, 1230, 1231, 1232, 1233, 1234, 1235, 1236, 1237, 1238, 1239, 1240, 1241, 1242, 1243, 1244, 1245, 1246, 1247, 1248, 1249, 1250, 1251, 1252, 1253, 1254, 1255, 1256, 1257, 1258, 1259, 1260, 1261, 1262, 1263, 1264, 1265, 1266, 1267, 1268, 1269, 1270, 1271, 1272, 1273, 1274, 1275, 1276, 1277, 1278, 1279, 1280, 1281, 1282, 1283, 1284, 1285, 1286, 1287, 1288, 1289, 1290, 1291, 1292, 1293, 1294, 1295, 1296, 1297, 1298, 1299, 1300, 1301, 1302, 1303, 1304, 1305, 1306, 1307, 1308, 1309, 1310, 1311, 1312, 1313, 1314, 1315, 1316, 1317, 1318, 1319, 1320, 1321, 1322, 1323, 1324, 1325, 1326, 1327, 1328, 1329, 1330, 1331, 1332, 1333, 1334, 1335, 1336, 1337, 1338, 1339, 1340, 1341, 1342, 1343, 1344, 1345, 1346, 1347, 1348, 1349, 1350, 1351, 1352, 1353, 1354, 1355, 1356, 1357, 1358, 1359, 1360, 1361, 1362, 1363, 1364, 1365, 1366, 1367, 1368, 1369, 1370, 1371, 1372, 1373, 1374, 1375, 1376, 1377, 1378, 1379, 1380, 1381, 1382, 1383, 1384, 1385, 1386, 1387, 1388, 1389, 1390, 1391, 1392, 1393, 1394, 1395, 1396, 1397, 1398, 1399, 1400, 1401, 1402, 1403, 1404, 1405, 1406, 1407, 1408, 1409, 1410, 1411, 1412, 1413, 1414, 1415, 1416, 1417, 1418, 1419, 1420, 1421, 1422, 1423, 1424, 1425, 1426, 1427, 1428, 1429, 1430, 1431, 1432, 1433, 1434, 1435, 1436, 1437, 1438, 1439, 1440, 1441, 1442, 1443, 1444, 1445, 1446, 1447, 1448, 1449, 1450, 1451, 1452, 1453, 1454, 1455, 1456, 1457, 1458, 1459, 1460, 1461, 1462, 1463, 1464, 1465, 1466, 1467, 1468, 1469, 1470, 1471, 1472, 1473, 1474, 1475, 1476, 1477, 1478, 1479, 1480, 1481, 1482, 1483, 1484, 1485, 1486, 1487, 1488, 1489, 1490, 1491, 1492, 1493, 1494, 1495, 1496, 1497, 1498, 1499, 1500 ],
                 "previous_cursor" : "$",
                 "next_cursor" : "2892e7ab37d44c6a15b438f78e8d76ed$"
               }"""
            val entityIdsResponse = readValue<TestClasses.TestEntityIdsResponse>(json)
            entityIdsResponse.entityIds.sorted().size shouldBeGreaterThan 0
        }

        test("deserialization#complex with companion class") {
            val json = """{
                 "entity_ids" : [ 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014, 1015, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1023, 1024, 1025, 1026, 1027, 1028, 1029, 1030, 1031, 1032, 1033, 1034, 1035, 1036, 1037, 1038, 1039, 1040, 1041, 1042, 1043, 1044, 1045, 1046, 1047, 1048, 1049, 1050, 1051, 1052, 1053, 1054, 1055, 1056, 1057, 1058, 1059, 1060, 1061, 1062, 1063, 1064, 1065, 1066, 1067, 1068, 1069, 1070, 1071, 1072, 1073, 1074, 1075, 1076, 1077, 1078, 1079, 1080, 1081, 1082, 1083, 1084, 1085, 1086, 1087, 1088, 1089, 1090, 1091, 1092, 1093, 1094, 1095, 1096, 1097, 1098, 1099, 1100, 1101, 1102, 1103, 1104, 1105, 1106, 1107, 1108, 1109, 1110, 1111, 1112, 1113, 1114, 1115, 1116, 1117, 1118, 1119, 1120, 1121, 1122, 1123, 1124, 1125, 1126, 1127, 1128, 1129, 1130, 1131, 1132, 1133, 1134, 1135, 1136, 1137, 1138, 1139, 1140, 1141, 1142, 1143, 1144, 1145, 1146, 1147, 1148, 1149, 1150, 1151, 1152, 1153, 1154, 1155, 1156, 1157, 1158, 1159, 1160, 1161, 1162, 1163, 1164, 1165, 1166, 1167, 1168, 1169, 1170, 1171, 1172, 1173, 1174, 1175, 1176, 1177, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187, 1188, 1189, 1190, 1191, 1192, 1193, 1194, 1195, 1196, 1197, 1198, 1199, 1200, 1201, 1202, 1203, 1204, 1205, 1206, 1207, 1208, 1209, 1210, 1211, 1212, 1213, 1214, 1215, 1216, 1217, 1218, 1219, 1220, 1221, 1222, 1223, 1224, 1225, 1226, 1227, 1228, 1229, 1230, 1231, 1232, 1233, 1234, 1235, 1236, 1237, 1238, 1239, 1240, 1241, 1242, 1243, 1244, 1245, 1246, 1247, 1248, 1249, 1250, 1251, 1252, 1253, 1254, 1255, 1256, 1257, 1258, 1259, 1260, 1261, 1262, 1263, 1264, 1265, 1266, 1267, 1268, 1269, 1270, 1271, 1272, 1273, 1274, 1275, 1276, 1277, 1278, 1279, 1280, 1281, 1282, 1283, 1284, 1285, 1286, 1287, 1288, 1289, 1290, 1291, 1292, 1293, 1294, 1295, 1296, 1297, 1298, 1299, 1300, 1301, 1302, 1303, 1304, 1305, 1306, 1307, 1308, 1309, 1310, 1311, 1312, 1313, 1314, 1315, 1316, 1317, 1318, 1319, 1320, 1321, 1322, 1323, 1324, 1325, 1326, 1327, 1328, 1329, 1330, 1331, 1332, 1333, 1334, 1335, 1336, 1337, 1338, 1339, 1340, 1341, 1342, 1343, 1344, 1345, 1346, 1347, 1348, 1349, 1350, 1351, 1352, 1353, 1354, 1355, 1356, 1357, 1358, 1359, 1360, 1361, 1362, 1363, 1364, 1365, 1366, 1367, 1368, 1369, 1370, 1371, 1372, 1373, 1374, 1375, 1376, 1377, 1378, 1379, 1380, 1381, 1382, 1383, 1384, 1385, 1386, 1387, 1388, 1389, 1390, 1391, 1392, 1393, 1394, 1395, 1396, 1397, 1398, 1399, 1400, 1401, 1402, 1403, 1404, 1405, 1406, 1407, 1408, 1409, 1410, 1411, 1412, 1413, 1414, 1415, 1416, 1417, 1418, 1419, 1420, 1421, 1422, 1423, 1424, 1425, 1426, 1427, 1428, 1429, 1430, 1431, 1432, 1433, 1434, 1435, 1436, 1437, 1438, 1439, 1440, 1441, 1442, 1443, 1444, 1445, 1446, 1447, 1448, 1449, 1450, 1451, 1452, 1453, 1454, 1455, 1456, 1457, 1458, 1459, 1460, 1461, 1462, 1463, 1464, 1465, 1466, 1467, 1468, 1469, 1470, 1471, 1472, 1473, 1474, 1475, 1476, 1477, 1478, 1479, 1480, 1481, 1482, 1483, 1484, 1485, 1486, 1487, 1488, 1489, 1490, 1491, 1492, 1493, 1494, 1495, 1496, 1497, 1498, 1499, 1500 ],
                 "previous_cursor" : "$",
                 "next_cursor" : "2892e7ab37d44c6a15b438f78e8d76ed$"
               }"""
            val entityIdsResponse = readValue<TestClasses.TestEntityIdsResponseWithCompanion>(json)
            entityIdsResponse.entityIds.sorted().size shouldBeGreaterThan 0
        }

        test(
            "deserialization#data class with members of all types is parseable from a JSON object with those fields"
        ) {
            val vector = Vector<Int>()
            vector.addAll(listOf(22, 23, 24))
            val expected = TestClasses.ClassWithAllTypes(
                map = mapOf("one" to "two"),
                set = setOf(1, 2, 3),
                string = "woo",
                list = listOf(4, 5, 6),
                indexedValue = IndexedValue(16, 17),
                vector = vector,
                annotation = Things.named("ANGSTROMIO"),
                kotlinEnum = TestClasses.Weekday.FRI,
                javaEnum = CarMake.VOLKSWAGEN,
                bigDecimal = BigDecimal.valueOf(12.0),
                bigInt = BigInteger.valueOf(13L),
                int = 1,
                long = 2L,
                char = 'x',
                bool = false,
                short = 14,
                byte = 15,
                float = 34.5f,
                double = 44.9,
                array = arrayOf("Hello", 300, true),
                arrayList = arrayListOf("Hello", "World"),
                any = true,
                intMap = mapOf(1 to 1),
                longMap = mapOf(2L to 2L)
            )

            val json = generate(expected)
            readValue<TestClasses.ClassWithAllTypes>(json) should be(expected)
        }

        test("deserialization# data class that throws an exception is not parseable from a JSON object") {
            assertThrows<JsonMappingException> {
                readValue<TestClasses.ClassWithException>("""{}""")
            }
        }

        test("deserialization#data class nested inside of an object is parseable from a JSON object") {
            readValue<TestClasses.OuterObject.NestedClass>("""{"id": 1}""") should be(
                TestClasses.OuterObject.NestedClass(
                    1
                )
            )
        }

        test(
            "deserialization#data class nested inside of an object nested inside of an object is parseable from a JSON object"
        ) {
            readValue<TestClasses.OuterObject.InnerObject.SuperNestedClass>("""{"id": 1}""") should be(
                TestClasses.OuterObject.InnerObject.SuperNestedClass(1)
            )
        }

        test("A data class with array members is parseable from a JSON object") {
            val jsonStr = """{"one":"1","two":["a","b","c"],"three":[1,2,3],"four":[4, 5],"five":["x", "y"],"bools":["true", false],"bytes":[1,2],"doubles":[1,5.0],"floats":[1.1, 22]}"""
            val c = readValue<TestClasses.ClassWithArrays>(jsonStr)
            c.one should be("1")
            c.two should be(arrayOf("a", "b", "c"))
            c.three should be(arrayOf(1, 2, 3))
            c.four should be(arrayOf(4L, 5L))
            c.five should be(arrayOf('x', 'y'))

            JsonDiff.assertDiff(
                """{"bools":[true,false],"bytes":[1,2],"doubles":[1.0,5.0],"five":["x","y"],"floats":[1.1,22.0],"four":[4,5],"one":"1","three":[1,2,3],"two":["a","b","c"]}""",
                generate(c)
            )
        }

        test("deserialization#data class with collection of Longs array of longs") {
            val c = readValue<TestClasses.ClassWithArrayLong>("""{"array":[3,1,2]}""")
            c.array.sorted() should be(arrayOf(1, 2, 3))
        }

        test("deserialization#data class with collection of Longs seq of longs") {
            val c = readValue<TestClasses.ClassWithSeqLong>("""{"seq":[3,1,2]}""")
            c.seq.sorted() should be(listOf(1, 2, 3))
        }

        test("deserialization#data class with an ArrayList of Integers") {
            val c = readValue<TestClasses.ClassWithArrayListOfIntegers>("""{"arraylist":[3,1,2]}""")
            val l = ArrayList<Int>(3)
            l.add(3)
            l.add(1)
            l.add(2)
            c.arraylist should be(l)
        }

        test("serde#data class with a SortedMap<String, Int>") {
            val origClass = TestClasses.ClassWithSortedMap(mapOf("aggregate" to 20).toSortedMap())
            val dataClassJson = generate(origClass)
            val dataClass = readValue<TestClasses.ClassWithSortedMap>(dataClassJson)
            dataClass should be(origClass)
        }

        test("serde#data class with a Seq of Longs") {
            val origClass = TestClasses.ClassWithSeqOfLongs(listOf(10, 20, 30))
            val dataClassJson = generate(origClass)
            val dataClass = readValue<TestClasses.ClassWithSeqOfLongs>(dataClassJson)
            dataClass should be(origClass)
        }

        test("deserialization#seq of longs") {
            val seq = readValue<List<Long>>("""[3,1,2]""")
            seq.sorted() should be(listOf(1L, 2L, 3L))
        }

        test("deserialization#readValue seq of longs") {
            val ids = readValue<List<Long>>("[3,1,2]")
            ids.sorted() should be(listOf(1L, 2L, 3L))
        }

        test("deserialization#handle options and defaults in data class") {
            val bob = readValue<TestClasses.Person>("""{"id" :1,"name" : "Bob","age" : 21}""")
            bob should be(TestClasses.Person(1, "Bob", null, 21, null))
        }

        test("deserialization#missing required field") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.Person>("""{}""")
            }
        }

        test("deserialization#incorrectly specified required field") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.PersonWithThings>("""{"id" :1,"name" : "Luke","age" : 21,"things" : {"foo" : ["NoDriods"]}}""")
            }
        }

        test("serialization#nulls will not render") {
            generate(
                TestClasses.Person(
                    1, "Bobert", dob = null, age = null, age_with_default = null, nickname = "TallPerson"
                )
            ) should be("""{"id":1,"name":"Bobert","nickname":"TallPerson"}""")
        }


        test("deserialization#string wrapper deserialization").config(enabled = false) {
            // Not supported by jackson-module-kotlin yet https://github.com/FasterXML/jackson-module-kotlin/issues/199#issuecomment-1013810769
            // https://github.com/FasterXML/jackson-module-kotlin/issues/413
            // https://github.com/FasterXML/jackson-module-kotlin/issues/650
            val parsedValue = readValue<TestClasses.ObjWithTestId>("""{"id": "5"}""")
            val expectedValue = TestClasses.ObjWithTestId(TestClasses.TestIdStringWrapper("5"))

            parsedValue should be(expectedValue)
            parsedValue.id should be(expectedValue.id)
            parsedValue.id should be(expectedValue.id)
            parsedValue.id.toString() should be(expectedValue.id.toString())
        }

        test("deserialization#readValue input stream") {
            val bais = ByteArrayInputStream("""{"foo": "bar"}""".toByteArray())
            mapper.readValue<TestClasses.Blah>(bais) should be(TestClasses.Blah("bar"))
        }

        test("serialization#interface fields should be ignored") {
            generate(TestClasses.Group3("123")) should be("""{"id":"123"}""")
        }

        //Jackson parses numbers into boolean type without error. see https://jira.codehaus.org/browse/JACKSON-78
        test("deserialization#data class with boolean as number") {
            readValue<TestClasses.ClassWithBoolean>(""" {"foo": 100}""") should be(TestClasses.ClassWithBoolean(true))
        }

        //Jackson parses numbers into boolean type without error. see https://jira.codehaus.org/browse/JACKSON-78
        test("deserialization#data class with List<Boolean>") {
            readValue<TestClasses.ClassWithSeqBooleans>(""" {"foos": [100, 5, 0, 9]}""") should be(TestClasses.ClassWithSeqBooleans(listOf(true, true, false, true)))
        }

        //Jackson parses numbers into boolean type without error. see https://jira.codehaus.org/browse/JACKSON-78
        test("List<Boolean>") {
            readValue<List<Boolean>>("""[100, 5, 0, 9]""") should be(listOf(true, true, false, true))
        }

        test("data class with boolean as number 0") {
            readValue<TestClasses.ClassWithBoolean>(""" {"foo": 0}""") should be(TestClasses.ClassWithBoolean(false))
        }

        test("data class with boolean as string") {
            assertJsonParse<TestClasses.ClassWithBoolean>(
                """ {"foo": "bar"}""",
                withErrors = listOf("foo: 'bar' is not a valid Boolean")
            )
        }

        test("data class with boolean number as string") {
            assertJsonParse<TestClasses.ClassWithBoolean>(
                """ {"foo": "1"}""",
                withErrors = listOf("foo: '1' is not a valid Boolean")
            )
        }

        val msgHiJsonStr = """{"msg":"hi"}"""

        test("readValue jsonParser") {
            val jsonNode = mapper.readValue<JsonNode>("{}")
            val jsonParser = TreeTraversingParser(jsonNode)
            mapper.readValue<JsonNode>(jsonParser) should be(jsonNode)
        }

        test("writeValue") {
            val os = ByteArrayOutputStream()
            mapper.writeValue(mapOf("msg" to "hi"), os)
            os.close()
            String(os.toByteArray()) should be(msgHiJsonStr)
        }

        test("writePrettyString") {
            val jsonStr = mapper.writeValueAsString("""{"msg": "hi"}""", prettyPrint = true)
            mapper.readValue<JsonNode>(jsonStr).get("msg").textValue() should be("hi")
        }

        test("reader") {
            mapper.readerFor<JsonNode>() shouldNot beNull()
        }

        test(
            "deserialization#jackson JsonDeserialize annotations deserializes json to data class with 2 decimal places for mandatory field"
        ) {
            readValue<TestClasses.ClassWithCustomDecimalFormat>(""" {"my_big_decimal": 23.1201}""") should be(TestClasses.ClassWithCustomDecimalFormat(BigDecimal(23.12, defaultBigDecimalMathContext), null))
        }

        test("deserialization#jackson JsonDeserialize annotations long with JsonDeserialize") {
            val result = readValue<TestClasses.ClassWithLongAndDeserializer>(""" {"long": 12345}""")
            result should be(TestClasses.ClassWithLongAndDeserializer(12345L))
            result.long should be(12345L) // promotes the returned integer into a long
        }

        test(
            "deserialization#jackson JsonDeserialize annotations deserializes json to data class with 2 decimal places for option field"
        ) {
            readValue<TestClasses.ClassWithCustomDecimalFormat>(""" {"my_big_decimal": 23.1201,"opt_my_big_decimal": 23.1201}""") should be(
                TestClasses.ClassWithCustomDecimalFormat(
                    BigDecimal(23.12, defaultBigDecimalMathContext), BigDecimal(23.12, defaultBigDecimalMathContext)
                )
            )
        }

        test("deserialization#jackson JsonDeserialize annotations opt long with JsonDeserialize") {
            readValue<TestClasses.ClassWithNullableLongAndDeserializer>("""{"opt_long": 12345}""") should be(TestClasses.ClassWithNullableLongAndDeserializer(12345))
        }

        test("serialization#upport sealed interfaces and data objects#json serialization") {
            val vin = Strings.randomAlphanumericString()
            val vehicle = TestClasses.Vehicle(vin, TestClasses.Audi)

            mapper.writeValueAsString(vehicle) should be("""{"vin":"$vin","type":"audi"}""")
        }

        test("deserialization#json creator") {
            readValue<TestClasses.TestJsonCreator>("""{"s" : "1234"}""") should be(TestClasses.TestJsonCreator("1234"))
        }

        test("deserialization#json creator with parameterized type") {
            readValue<TestClasses.TestJsonCreator2>("""{"strings" : ["1", "2", "3", "4"]}""") should be(
                TestClasses.TestJsonCreator2(listOf(1, 2, 3, 4))
            )
        }


        test("deserialization#multiple data class constructors#no annotation") {
            // https://github.com/FasterXML/jackson-module-kotlin#caveats
            // caveats state if you have multiple constructors to annotate the proper constructor with @JsonCreator

            val m = jacksonObjectMapper()

            // but deserialization works for the primary constructor
            m.readValue<TestClasses.ClassWithMultipleConstructors>("""{"number1" : 12345,"number2" : 65789,"number3" : 99999}""") should be(TestClasses.ClassWithMultipleConstructors(12345L, 65789L, 99999L))

            // but we do not support
            assertThrows<InvalidDefinitionException> {
                readValue<TestClasses.ClassWithMultipleConstructors>("""{"number1" : 12345,"number2" : 65789,"number3" : 99999}""") should be(TestClasses.ClassWithMultipleConstructors(12345L, 65789L, 99999L))
            }

            // as well as for the secondary
            m.readValue<TestClasses.ClassWithMultipleConstructors>("""{"number1" : "12345","number2" : "65789","number3" : "99999"}""".trimMargin()) should be(TestClasses.ClassWithMultipleConstructors(12345L, 65789L, 99999L))

            // but we do not support
            assertThrows<InvalidDefinitionException> {
                readValue<TestClasses.ClassWithMultipleConstructors>("""{"number1" : "12345","number2" : "65789","number3" : "99999"}""".trimMargin()) should be(TestClasses.ClassWithMultipleConstructors(12345L, 65789L, 99999L))
            }
        }

        test("deserialization#multiple data class constructors#annotated") {
            readValue<TestClasses.ClassWithMultipleConstructorsAnnotated>("""{"number_as_string1" : "12345","number_as_string2" : "65789","number_as_string3" : "99999"}""".trimMargin()) should be(
                TestClasses.ClassWithMultipleConstructorsAnnotated(12345L, 65789L, 99999L)
            )
        }

        test("deserialization#complex object") {
            val profiles = listOf(
                TestClasses.LimiterProfile(1, "up"),
                TestClasses.LimiterProfile(2, "down"),
                TestClasses.LimiterProfile(3, "left"),
                TestClasses.LimiterProfile(4, "right")
            )
            val expected = TestClasses.LimiterProfiles(profiles)

            readValue<TestClasses.LimiterProfiles>(
                """
        |{
        |  "profiles" : [
        |    {
        |      "id" : "1",
        |      "name" : "up"
        |    },
        |    {
        |      "id" : "2",
        |      "name" : "down"
        |    },
        |    {
        |      "id" : "3",
        |      "name" : "left"
        |    },
        |    {
        |      "id" : "4",
        |      "name" : "right"
        |    }
        |  ]
        |}
        |""".trimMargin()
            ) should be(expected)
        }

        test("deserialization#simple class with boxed primitive constructor args") {
            readValue<TestClasses.ClassWithBoxedPrimitives>("""{"events" : 2,"errors" : 0}""") should be(TestClasses.ClassWithBoxedPrimitives(2, 0))
        }

        test("deserialization#complex object with primitives") {
            val json = """
                 |{
                 |  "cluster_name" : "cluster1",
                 |  "zone" : "abcd1",
                 |  "environment" : "devel",
                 |  "job" : "devel-1",
                 |  "owners" : "owning-group",
                 |  "dtab" : "/s#/devel/abcd1/space/devel-1",
                 |  "address" : "local-test.foo.bar.com:1111/owning-group/space/devel-1",
                 |  "enabled" : "true"
                 |}
                 |""".trimMargin()

            readValue<TestClasses.AddClusterRequest>(json) should be(
                TestClasses.AddClusterRequest(
                    clusterName = "cluster1",
                    zone = "abcd1",
                    environment = "devel",
                    job = "devel-1",
                    dtab = "/s#/devel/abcd1/space/devel-1",
                    address = "local-test.foo.bar.com:1111/owning-group/space/devel-1",
                    owners = "owning-group"
                )
            )
        }

        test("deserialization#inject request field fails with mapping exception") {
            // with an injector and default injectable values but the field cannot be parsed
            // thus we get a data class mapping exception
            // without an injector, the incoming JSON is used to populate the data class, which
            // is empty, and thus we also get a data class mapping exception
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithQueryParamDateTimeInject>("""{}""")
            }
        }

        test("deserialization#ignored constructor field - no default value") {
            val json = """{"name" : "Widget","description" : "This is a thing."}"""

            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassIgnoredFieldInConstructorNoDefault>(json)
            }
            e.errors.first().message should be("id: ignored field has no default value specified")
        }

        test("ignored constructor field - with default value") {
            val json = """{"name" : "Widget","description" : "This is a thing."}""".trimMargin()

            val result = readValue<TestClasses.ClassIgnoredFieldInConstructorWithDefault>(json)
            result.id should be(42L)
            result.name should be("Widget")
            result.description should be("This is a thing.")
        }

        test("deserialization#mixin annotations") {
            val points = TestClasses.Points(first = TestClasses.Point(1, 1), second = TestClasses.Point(4, 5))
            val json = """{"first": { "x": 1, "y": 1 }, "second": { "x": 4, "y": 5 }}"""

            readValue<TestClasses.Points>(json) shouldBeEqual points
            generate(points) shouldBeEqual """{"first":{"x":1,"y":1},"second":{"x":4,"y":5}}"""
        }

        test("deserialization#ignore type with no default fails") {
            val json = """{"name" : "Widget","description" : "This is a thing."}""".trimMargin()
            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.ContainsAnIgnoreTypeNoDefault>(json)
            }
            e.errors.first().message should be("ignored: ignored field has no default value specified")
        }

        test("deserialization#ignore type with default passes") {
            val json = """{"name" : "Widget","description" : "This is a thing."}""".trimMargin()
            val result = readValue<TestClasses.ContainsAnIgnoreTypeWithDefault>(json)
            result.ignored should be(TestClasses.IgnoreMe(42L))
            result.name should be("Widget")
            result.description should be("This is a thing.")
        }

        test("deserialization#readValue SimpleClassWithInjection fails") {
            // no field injection configured, parsing happens normally
            val json = """{"hello" : "Mom"}"""

            val result = readValue<TestClasses.SimpleClassWithInjection>(json)
            result.hello should be("Mom")
        }

        test("deserialization#support JsonView") {
            /* using Item which has @JsonView annotation on all fields:
               Public -> id
               Public -> name
               Internal -> owner
             */
            val json = """{"id": 42,"name": "My Item"}""".trimMargin()

            val item = mapper.readerWithView(TestClasses.Views.Public::class.java).forType(TestClasses.Item::class.java)
                .readValue<TestClasses.Item>(json)
            item.id should be(42L)
            item.name should be("My Item")
            item.owner should be("")
        }

        test("deserialization#support JsonView 2") {
            /* using Item which has @JsonView annotation on all fields:
               Public -> id
               Public -> name
               Internal -> owner
             */
            val json = """{"owner": "Mister Magoo"}""".trimMargin()

            val item =
                mapper.readerWithView(TestClasses.Views.Internal::class.java).forType(TestClasses.Item::class.java)
                    .readValue<TestClasses.Item>(json)
            item.id should be(1L)
            item.name should be("")
            item.owner should be("Mister Magoo")
        }

        test("deserialization#support JsonView 3") {
            /* using ItemSomeViews which has no @JsonView annotation on owner field:
               Public -> id
               Public -> name
                      -> owner
             */
            val json = """{"id": 42,"name": "My Item","owner": "Mister Magoo"}""".trimMargin()

            val item = mapper.readerWithView(TestClasses.Views.Public::class.java)
                .forType(TestClasses.ItemSomeViews::class.java).readValue<TestClasses.ItemSomeViews>(json)
            item.id should be(42L)
            item.name should be("My Item")
            item.owner should be("Mister Magoo")
        }

        test("deserialization#support JsonView 4") {
            /* using ItemSomeViews which has no @JsonView annotation on owner field:
               Public -> id
               Public -> name
                      -> owner
             */
            val json = """{"owner": "Mister Magoo"}"""

            val item = mapper.readerWithView(TestClasses.Views.Internal::class.java)
                .forType(TestClasses.ItemSomeViews::class.java).readValue<TestClasses.ItemSomeViews>(json)
            item.id should be(1L)
            item.name should be("")
            item.owner should be("Mister Magoo")
        }

        test("deserialization#support JsonView 5") {
            /* using ItemNoDefaultForView which has @JsonView annotation on non-defaulted field:
               Public -> name (no default value)
             */
            val json = """{"name": "My Item"}"""

            val item = mapper.readerWithView(TestClasses.Views.Public::class.java)
                .forType(TestClasses.ItemNoDefaultForView::class.java).readValue<TestClasses.ItemNoDefaultForView>(json)
            item.name should be("My Item")
        }

        test("deserialization#support JsonView 6") {
            /* using ItemNoDefaultForView which has @JsonView annotation on non-defaulted field:
               Public -> name (no default value)
             */

            assertThrows<DataClassMappingException> {
                mapper.readerWithView(TestClasses.Views.Public::class.java)
                    .forType(TestClasses.ItemNoDefaultForView::class.java)
                    .readValue<TestClasses.ItemNoDefaultForView>("{}")
            }
        }

        test("deserialization#test @JsonNaming") {
            val json = """{"please-use-kebab-case": true}""".trimMargin()

            readValue<TestClasses.ClassWithKebabCase>(json) shouldBeEqual TestClasses.ClassWithKebabCase(true)

            val snakeCaseMapper = mapper.toSnakeCaseMapper()
            snakeCaseMapper.readValue<TestClasses.ClassWithKebabCase>(json) shouldBeEqual TestClasses.ClassWithKebabCase(
                true
            )

            val camelCaseMapper = mapper.toCamelCaseObjectMapper()
            camelCaseMapper.readValue<TestClasses.ClassWithKebabCase>(json) shouldBeEqual TestClasses.ClassWithKebabCase(
                true
            )
        }

        test("deserialization#test @JsonNaming 2") {
            val snakeCaseMapper = mapper.toSnakeCaseMapper()

            // data class is marked with @JsonNaming and the default naming strategy is LOWER_CAMEL_CASE
            // which overrides the mapper's configured naming strategy
            val json = """{"thisFieldShouldUseDefaultPropertyNamingStrategy": true}""".trimMargin()

            snakeCaseMapper.readValue<TestClasses.UseDefaultNamingStrategy>(json) should be(
                TestClasses.UseDefaultNamingStrategy(true)
            )

            // default for mapper is snake_case, but the data class is annotated with @JsonNaming which
            // tells the mapper what property naming strategy to expect.
            readValue<TestClasses.UseDefaultNamingStrategy>(json) should be(TestClasses.UseDefaultNamingStrategy(true))
        }

        test("deserialization#test @JsonNaming 3") {
            val camelCaseMapper = mapper.toCamelCaseObjectMapper()

            val json = """{"thisFieldShouldUseDefaultPropertyNamingStrategy": true}""".trimMargin()

            camelCaseMapper.readValue<TestClasses.UseDefaultNamingStrategy>(json) should be(
                TestClasses.UseDefaultNamingStrategy(true)
            )

            // default for mapper is snake_case, but the data class is annotated with @JsonNaming which
            // tells the mapper what property naming strategy to expect.
            readValue<TestClasses.UseDefaultNamingStrategy>(json) should be(TestClasses.UseDefaultNamingStrategy(true))
        }

        test("deserialization#test @JsonNaming with mixin") {
            val json = """{"will-this-get-the-right-casing": true}""".trimMargin()

            readValue<TestClasses.ClassShouldUseKebabCaseFromMixin>(json) should be(
                TestClasses.ClassShouldUseKebabCaseFromMixin(true)
            )

            val snakeCaseMapper = mapper.toSnakeCaseMapper()
            snakeCaseMapper.readValue<TestClasses.ClassShouldUseKebabCaseFromMixin>(json) should be(
                TestClasses.ClassShouldUseKebabCaseFromMixin(true)
            )

            val camelCaseMapper = mapper.toCamelCaseObjectMapper()
            camelCaseMapper.readValue<TestClasses.ClassShouldUseKebabCaseFromMixin>(json) should be(
                TestClasses.ClassShouldUseKebabCaseFromMixin(true)
            )
        }

        test("serde#can serialize and deserialize polymorphic types") {
            val view = TestClasses.View(
                shapes = listOf(
                    TestClasses.Circle(10),
                    TestClasses.Rectangle(5, 5),
                    TestClasses.Circle(3),
                    TestClasses.Rectangle(10, 5)
                )
            )
            val result = generate(view)
            result should be(
                """{"shapes":[{"type":"circle","radius":10},{"type":"rectangle","width":5,"height":5},{"type":"circle","radius":3},{"type":"rectangle","width":10,"height":5}]}"""
            )

            readValue<TestClasses.View>(result) should be(view)
        }

        test("serde#can serialize and deserialize optional polymorphic types") {
            val view = TestClasses.NullableView(
                shapes = listOf(TestClasses.Circle(10), TestClasses.Rectangle(5, 5)),
                optional = TestClasses.Rectangle(10, 5)
            )
            val result = generate(view)
            result should be(
                """{"shapes":[{"type":"circle","radius":10},{"type":"rectangle","width":5,"height":5}],"optional":{"type":"rectangle","width":10,"height":5}}"""
            )

            readValue<TestClasses.NullableView>(result) should be(view)
        }

        test("serde#can deserialize data class with option when missing") {
            val view = TestClasses.NullableView(
                shapes = listOf(TestClasses.Circle(10), TestClasses.Rectangle(5, 5)), optional = null
            )
            val result = generate(view)
            result should be(
                """{"shapes":[{"type":"circle","radius":10},{"type":"rectangle","width":5,"height":5}]}"""
            )

            readValue<TestClasses.NullableView>(result) should be(view)
        }

        test("nullable deserializer - allows parsing a JSON null into a null type") {
            readValue<TestClasses.WithNullableCarMake>(
                """{"non_null_car_make": "VOLKSWAGEN", "nullable_car_make": null}"""
            ) should be(
                TestClasses.WithNullableCarMake(nonNullCarMake = CarMake.VOLKSWAGEN, nullableCarMake = null)
            )
        }

        test("nullable deserializer - fail on missing value even when null allowed") {
            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.WithNullableCarMake>("""{}""")
            }
            e.errors.size should be(1)
            e.errors.first().message should be("non_null_car_make: field is required")
        }

        test("nullable deserializer - allow null values when default is provided") {
            readValue<TestClasses.WithDefaultNullableCarMake>("""{"nullable_car_make":null}""") should be(
                TestClasses.WithDefaultNullableCarMake(null)
            )
        }

        test("nullable deserializer - use default when null is allowed, but not provided") {
            readValue<TestClasses.WithDefaultNullableCarMake>("""{}""") should be(
                TestClasses.WithDefaultNullableCarMake(CarMake.HONDA)
            )
        }

        test("deserialization of json array into a string field") {
            val json = """{"value": [{"languages": "es","reviewable": "tweet","baz": "too","foo": "bar","coordinates": "polar"}]}"""

            val expected = TestClasses.WithJsonStringType("""[{"languages":"es","reviewable":"tweet","baz":"too","foo":"bar","coordinates":"polar"}]""")
            readValue<TestClasses.WithJsonStringType>(json) should be(expected)
        }

        test("deserialization of json int into a string field") {
            val json = """{"value": 1}""".trimMargin()

            val expected = TestClasses.WithJsonStringType("1")
            readValue<TestClasses.WithJsonStringType>(json) should be(expected)
        }

        test("deserialization of json object into a string field") {
            val json = """{"value": {"foo": "bar"}}""".trimMargin()

            val expected = TestClasses.WithJsonStringType("""{"foo":"bar"}""")
            readValue<TestClasses.WithJsonStringType>(json) should be(expected)
        }
    }
}
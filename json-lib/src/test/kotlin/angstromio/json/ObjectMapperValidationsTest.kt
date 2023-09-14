package angstromio.json

import angstromio.json.exceptions.DataClassFieldMappingException
import angstromio.json.exceptions.DataClassMappingException
import angstromio.validation.extensions.getLeafNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.fail
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import jakarta.validation.ConstraintViolation
import jakarta.validation.ElementKind
import jakarta.validation.Path
import jakarta.validation.UnexpectedTypeException
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

class ObjectMapperValidationsTest : AbstractObjectMapperTest() {

    override val mapper: ObjectMapper by lazy {
        val m: ObjectMapper = ObjectMapper().defaultMapper()
        m.registerModule(MixInAnnotationsModule())
    }

    companion object {
        private val now: LocalDateTime =
            LocalDateTime.parse("2015-04-09T05:17:15Z", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        private val ownershipEnd = now.plusYears(15)
        private val warrantyEnd = now.plusYears(5)
        private val BaseCar: TestClasses.Car = TestClasses.Car(
            id = 1,
            make = CarMake.FORD,
            model = "Model-T",
            year = 2000,
            owners = emptyList(),
            numDoors = 2,
            manual = true,
            ownershipStart = now,
            ownershipEnd = ownershipEnd,
            warrantyStart = now,
            warrantyEnd = warrantyEnd,
            passengers = emptyList()
        )
    }

    init {
        test("deserialization#generic types") {
            // passes validation
            readValue<TestClasses.GenericTestClassWithValidation<String>>("""{"data" : "widget"}""") should be(
                TestClasses.GenericTestClassWithValidation("widget")
            )

            val e1 = assertThrows<DataClassMappingException> {
                readValue<TestClasses.GenericTestClassWithValidationAndMultipleArgs<String>>(
                    """{"data" : "", "number" : 3}"""
                )
            }
            e1.errors.size should be(2)
            val e2 = assertThrows<DataClassMappingException> {
                readValue<TestClasses.GenericTestClassWithValidationAndMultipleArgs<String>>(
                    """{"data" : "widget", "number" : 3}"""
                )
            }
            e2.errors.size should be(1)
            // passes validation
            readValue<TestClasses.GenericTestClassWithValidationAndMultipleArgs<String>>(
                """{"data" : "widget", "number" : 5}"""
            )
        }

        test("enums#default validation") {
            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithNotEmptyValidation>(
                    """{"name" : "","make" : "foo"}"""
                )
            }
            e.errors.map { it.message } should be(
                listOf(
                    """make: 'foo' is not a valid CarMake with valid values: FORD, VOLKSWAGEN, TOYOTA, HONDA""",
                    "name: must not be empty"
                )
            )
        }

        test("enums#invalid validation") {
            // the name field is annotated with @ThrowsRuntimeExceptionConstraint which is implemented
            // by the ThrowsRuntimeExceptionConstraintValidator...that simply throws a RuntimeException
            val e = assertThrows<RuntimeException> {
                readValue<TestClasses.ClassWithInvalidValidation>("""{"name" : "Bob","make" : "foo"}""")
            }

            e.message should be("validator foo error")
        }

        test("enums#invalid validation 1") {
            // the name field is annotated with @NoValidatorImplConstraint which has no constraint validator
            // specified nor registered and thus errors when applied.
            val e = assertThrows<UnexpectedTypeException> {
                readValue<TestClasses.ClassWithNoValidatorImplConstraint>("""{"name" : "Bob","make" : "foo"}""")
            }

            e.message?.contains(
                "No validator could be found for constraint 'class angstromio.json.NoValidatorImplConstraint' validating type 'java.lang.String'. Check configuration for 'ClassWithNoValidatorImplConstraint.name'"
            ) should be(
                true
            )
        }

        test("method validation exceptions") {
            val e = assertThrows<RuntimeException> {
                readValue<TestClasses.ClassWithMethodValidationException>(
                    """{"name" : "foo barr","passphrase" : "the quick brown fox jumps over the lazy dog" }"""
                )
            }
            e.message?.contains("oh noes")
        }

        /*
         Notes:
      
         ValidationError.Field types will not have a root bean instance and the violation property path
         will include the class name, e.g., for a violation on the `id` parameter of the `RentalStation` case
         class, the property path would be: `RentalStation.id`.
      
         ValidationError.Method types will have a root bean instance (since they require an instance in order for
         the method to be invoked) and the violation property path will not include the class name since this
         is understood to be known since the method validations typically have to be manually invoked, e.g.,
         for the method `validateState` that is annotated with field `state`, the property path of a violation would be:
         `validateState.state`.
         */

        test("class and field level validations#success") {
            readValue<TestClasses.Car>(BaseCar)
        }

        test("class and field level validations#top-level failed validations") {
            val value = BaseCar.copy(id = 2, year = 1910)
            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.Car>(value)
            }

            e.errors.size should be(1)

            val error = e.errors.first()
            error.path should be(DataClassFieldMappingException.PropertyPath.leaf("year"))
            error.reason.message should be("must be greater than or equal to 2000")
            when (val detail = error.reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Field)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.propertyPath.toString() should be("Car.year")
                    violation.message should be("must be greater than or equal to 2000")
                    violation.invalidValue should be(1910)
                    violation.rootBeanClass should be(TestClasses.Car::class.java)
                    violation.rootBean should beNull() // ValidationError.Field types won't have a root bean instance
                }

                else -> fail("")
            }
        }

        test("method validation#top-level") {
            val address = TestClasses.Address(
                street = "123 Main St.", city = "Tampa", state = "FL" // invalid
            )

            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.Address>(address)
            }

            e.errors.size should be(1)
            val errors = e.errors

            errors.first().path should be(DataClassFieldMappingException.PropertyPath.Empty)
            errors.first().reason.message should be("state must be one of <CA, MD, WI>")
            errors.first().reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = errors.first().reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Method)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.message should be("state must be one of <CA, MD, WI>")
                    violation.invalidValue should be(address)
                    violation.rootBeanClass should be(TestClasses.Address::class.java)
                    violation.rootBean should be(address) // ValidationError.Method types have a root bean instance
                    violation.propertyPath.toString() should be("validateState") // the @PostConstructValidation annotation does not specify a field.
                    val leafNode = violation.propertyPath.getLeafNode()
                    leafNode.kind should be(ElementKind.METHOD)
                    leafNode.name should be("validateState")
                }

                else -> fail("")
            }
        }

        test("class and field level validations#nested failed validations") {
            val invalidAddress = TestClasses.Address(
                street = "", // invalid
                city = "", // invalid
                state = "FL" // invalid
            )

            val owners = listOf(
                TestClasses.Person(
                    id = 1, name = "joe smith", dob = LocalDate.now(), age = null, address = invalidAddress
                )
            )
            val car = BaseCar.copy(owners = owners)

            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.Car>(car)
            }

            e.errors.size should be(2)
            val errors = e.errors

            errors.first().path should be(
                DataClassFieldMappingException.PropertyPath.leaf("city").withParent("address").withParent("owners")
            )
            errors.first().reason.message should be("must not be empty")
            when (val detail = errors.first().reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Field)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.propertyPath.toString() should be("Address.city")
                    violation.message should be("must not be empty")
                    violation.invalidValue should be("")
                    violation.rootBeanClass should be(TestClasses.Address::class.java)
                    violation.rootBean should beNull() // ValidationError.Field types won't have a root bean instance
                }

                else -> fail("")
            }

            errors[1].path should be(
                DataClassFieldMappingException.PropertyPath.leaf("street").withParent("address")
                    .withParent("owners")
            )
            errors[1].reason.message should be("must not be empty")
            when (val detail = errors[1].reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Field)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.propertyPath.toString() should be("Address.street")
                    violation.message should be("must not be empty")
                    violation.invalidValue should be("")
                    violation.rootBeanClass should be(TestClasses.Address::class.java)
                    violation.rootBean should beNull() // ValidationError.Field types won't have a root bean instance
                }

                else -> fail("")
            }
        }

        test("class and field level validations#nested method validations") {
            val owners = listOf(
                TestClasses.Person(
                    id = 2,
                    name = "joe smith",
                    dob = LocalDate.now(),
                    age = null,
                    address = TestClasses.Address(city = "pyongyang", state = "KP" /* invalid */)
                )
            )
            val car: TestClasses.Car = BaseCar.copy(owners = owners)

            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.Car>(car)
            }

            e.errors.size should be(1)
            val errors = e.errors

            errors.first().path should be(
                DataClassFieldMappingException.PropertyPath.leaf("address").withParent("owners")
            )
            errors.first().reason.message should be("state must be one of <CA, MD, WI>")
            errors.first().reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = errors.first().reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Method)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.message should be("state must be one of <CA, MD, WI>")
                    violation.invalidValue should be(TestClasses.Address(city = "pyongyang", state = "KP"))
                    violation.rootBeanClass should be(TestClasses.Address::class.java)
                    violation.rootBean should be(
                        TestClasses.Address(
                            city = "pyongyang", state = "KP"
                        )
                    ) // ValidationError.Method types have a root bean instance
                    violation.propertyPath.toString() should be("validateState") // the @PostConstructValidation annotation does not specify a field.
                    val leafNode = violation.propertyPath.getLeafNode()
                    leafNode.kind should be(ElementKind.METHOD)
                    leafNode.name should be("validateState")
                }

                else -> fail("")
            }

            errors.map { it.message } should be(listOf("owners.address: state must be one of <CA, MD, WI>"))
        }

        test("class and field level validations#end before start") {
            val value: TestClasses.Car =
                BaseCar.copy(ownershipStart = BaseCar.ownershipEnd, ownershipEnd = BaseCar.ownershipStart)
            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.Car>(value)
            }

            e.errors.size should be(1)
            val errors = e.errors

            errors.first().path should be(DataClassFieldMappingException.PropertyPath.leaf("ownership_end"))
            errors.first().reason.message should be(
                "ownershipEnd <2015-04-09T05:17:15> must be after ownershipStart <2030-04-09T05:17:15>"
            )
            errors.first().reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = errors.first().reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Method)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.message should be(
                        "ownershipEnd <2015-04-09T05:17:15> must be after ownershipStart <2030-04-09T05:17:15>"
                    )
                    violation.invalidValue should be(now)
                    violation.rootBeanClass should be(TestClasses.Car::class.java)
                    violation.rootBean should be(
                        value
                    ) // ValidationError.Method types have a root bean instance
                    violation.propertyPath.toString() should be(
                        "ownershipTimesValid.ownershipEnd"
                    ) // the @PostConstructValidation annotation specifies a single field.
                    val leafNode = violation.propertyPath.getLeafNode()
                    leafNode.kind should be(ElementKind.PARAMETER)
                    leafNode.name should be("ownershipEnd")
                    leafNode.`as`(Path.ParameterNode::class.java) // shouldn't blow up
                }

                else ->
                    fail("")
            }
        }

        test("class and field level validations#optional end before start") {
            val value: TestClasses.Car =
                BaseCar.copy(warrantyStart = BaseCar.warrantyEnd, warrantyEnd = BaseCar.warrantyStart)
            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.Car>(value)
            }

            e.errors.size should be(2)
            val errors = e.errors

            errors.first().path should be(DataClassFieldMappingException.PropertyPath.leaf("warranty_end"))
            errors.first().reason.message should be(
                "warrantyEnd <2015-04-09T05:17:15> must be after warrantyStart <2020-04-09T05:17:15>"
            )
            errors.first().reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = errors.first().reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Method)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.message should be(
                        "warrantyEnd <2015-04-09T05:17:15> must be after warrantyStart <2020-04-09T05:17:15>"
                    )
                    violation.invalidValue should be(now)
                    violation.rootBeanClass should be(TestClasses.Car::class.java)
                    violation.rootBean should be(
                        value
                    ) // ValidationError.Method types have a root bean instance
                    violation.propertyPath.toString() should be(
                        "warrantyTimeValid.warrantyEnd"
                    ) // the @PostConstructValidation annotation specifies two fields.
                    val leafNode = violation.propertyPath.getLeafNode()
                    leafNode.kind should be(ElementKind.PARAMETER)
                    leafNode.name should be("warrantyEnd")
                    leafNode.`as`(Path.ParameterNode::class.java) // shouldn't blow up
                }

                else -> fail("")
            }

            errors[1].path should be(DataClassFieldMappingException.PropertyPath.leaf("warranty_start"))
            errors[1].reason.message should be(
                "warrantyEnd <2015-04-09T05:17:15> must be after warrantyStart <2020-04-09T05:17:15>"
            )
            errors[1].reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = errors[1].reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Method)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.message should be(
                        "warrantyEnd <2015-04-09T05:17:15> must be after warrantyStart <2020-04-09T05:17:15>"
                    )
                    violation.invalidValue should be(warrantyEnd)
                    violation.rootBeanClass should be(TestClasses.Car::class.java)
                    violation.rootBean should be(
                        value
                    ) // ValidationError.Method types have a root bean instance
                    violation.propertyPath.toString() should be(
                        "warrantyTimeValid.warrantyStart"
                    ) // the @PostConstructValidation annotation specifies two fields.
                    val leafNode = violation.propertyPath.getLeafNode()
                    leafNode.kind should be(ElementKind.PARAMETER)
                    leafNode.name should be("warrantyStart")
                    leafNode.`as`(Path.ParameterNode::class.java) // shouldn't blow up
                }

                else -> fail("")
            }
        }

        test("class and field level validations#no start with end") {
            val value: TestClasses.Car = BaseCar.copy(warrantyStart = null, warrantyEnd = BaseCar.warrantyEnd)
            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.Car>(value)
            }

            e.errors.size should be(2)
            val errors = e.errors

            errors.first().path should be(DataClassFieldMappingException.PropertyPath.leaf("warranty_end"))
            errors.first().reason.message should be(
                "both warrantyStart and warrantyEnd are required for a valid range"
            )
            errors.first().reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = errors.first().reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Method)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.message should be(
                        "both warrantyStart and warrantyEnd are required for a valid range"
                    )
                    violation.invalidValue should be(warrantyEnd)
                    violation.rootBeanClass should be(TestClasses.Car::class.java)
                    violation.rootBean should be(
                        value
                    ) // ValidationError.Method types have a root bean instance
                    violation.propertyPath.toString() should be(
                        "warrantyTimeValid.warrantyEnd"
                    ) // the @PostConstructValidation annotation specifies two fields.
                    val leafNode = violation.propertyPath.getLeafNode()
                    leafNode.kind should be(ElementKind.PARAMETER)
                    leafNode.name should be("warrantyEnd")
                    leafNode.`as`(Path.ParameterNode::class.java) // shouldn't blow up
                }

                else -> fail("")
            }

            errors[1].path should be(DataClassFieldMappingException.PropertyPath.leaf("warranty_start"))
            errors[1].reason.message should be(
                "both warrantyStart and warrantyEnd are required for a valid range"
            )
            errors[1].reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = errors[1].reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Method)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.message should be(
                        "both warrantyStart and warrantyEnd are required for a valid range"
                    )
                    violation.invalidValue should be(null)
                    violation.rootBeanClass should be(TestClasses.Car::class.java)
                    violation.rootBean should be(
                        value
                    ) // ValidationError.Method types have a root bean instance
                    violation.propertyPath.toString() should be(
                        "warrantyTimeValid.warrantyStart"
                    ) // the @PostConstructValidation annotation specifies two fields.
                    val leafNode = violation.propertyPath.getLeafNode()
                    leafNode.kind should be(ElementKind.PARAMETER)
                    leafNode.name should be("warrantyStart")
                    leafNode.`as`(Path.ParameterNode::class.java) // shouldn't blow up
                }

                else -> fail("")
            }
        }

        test("class and field level validations#errors sorted by message") {
            val first = DataClassFieldMappingException(
                DataClassFieldMappingException.PropertyPath.Empty, DataClassFieldMappingException.Reason("123")
            )
            val second = DataClassFieldMappingException(
                DataClassFieldMappingException.PropertyPath.Empty, DataClassFieldMappingException.Reason("aaa")
            )
            val third = DataClassFieldMappingException(
                DataClassFieldMappingException.PropertyPath.leaf("bla"), DataClassFieldMappingException.Reason("zzz")
            )
            val fourth = DataClassFieldMappingException(
                DataClassFieldMappingException.PropertyPath.Empty, DataClassFieldMappingException.Reason("xxx")
            )

            val unsorted = setOf(third, second, fourth, first)
            val expectedSorted = listOf(first, second, third, fourth)

            DataClassMappingException(unsorted).errors should be((expectedSorted))
        }

        test("class and field level validations#option<string> validation") {
            val address = TestClasses.Address(
                street = "", // invalid
                city = "New Orleans", state = "LA"
            )

            assertThrows<DataClassMappingException> {
                mapper.readValue<TestClasses.Address>(mapper.writeValueAsBytes(address))
            }
        }

        test("fail when ClassWithSeqOfClassWithValidation with null array element") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithSeqOfClassWithValidation>("""{"seq":[null]}""")
            }
        }

        test("fail when ClassWithSeqOfClassWithValidation with invalid array element") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithSeqOfClassWithValidation>("""{"seq":[0]}""")
            }
        }

        test("fail when ClassWithSeqOfClassWithValidation with null field in object") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithSeqOfClassWithValidation>("""{"seq":[{"value": null}]}""")
            }
        }

        test("fail when ClassWithSeqOfClassWithValidation with invalid field in object") {
            assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithSeqOfClassWithValidation>("""{"seq":[{"value": 0}]}""")
            }
        }

        test(
            "A data class with an Nullable Int member and validation annotation is readValue-able from a JSON object with value that passes the validation"
        ) {
            readValue<TestClasses.ClassWithNullableAndValidation>("""{"towing_capacity":10000}""") should be(
                TestClasses.ClassWithNullableAndValidation(10000)
            )
        }

        test(
            "A data class with an Nullable Int member and validation annotation is readValue-able from a JSON object with null value"
        ) {
            readValue<TestClasses.ClassWithNullableAndValidation>("""{"towing_capacity":null}""") should be(
                TestClasses.ClassWithNullableAndValidation(null)
            )
        }

        test(
            "A data class with an Nullable Int member and validation annotation is readValue-able from a JSON without that field"
        ) {
            readValue<TestClasses.ClassWithNullableAndValidation>("""{}""") should be(
                TestClasses.ClassWithNullableAndValidation(null)
            )
        }

        test(
            "A data class with an Nullable Int member and validation annotation is readValue-able from a JSON object with value that fails the validation"
        ) {
            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.ClassWithNullableAndValidation>("""{"towing_capacity":1}""") should be(
                    TestClasses.ClassWithNullableAndValidation(1)
                )
            }
            e.errors.first().message should be("towing_capacity: must be greater than or equal to 100")
        }

        test(
            "A data class with an Nullable Boolean member and incompatible validation annotation is not readValue-able from a JSON object"
        ) {
            assertThrows<UnexpectedTypeException> {
                readValue<TestClasses.ClassWithNullableAndIncompatibleValidation>("""{"are_you":true}""") should be(
                    TestClasses.ClassWithNullableAndIncompatibleValidation(true)
                )
            }
        }

        test(
            "A data class with an Nullable Boolean member and incompatible validation annotation is readValue-able from a JSON object with null value"
        ) {
            readValue<TestClasses.ClassWithNullableAndIncompatibleValidation>("""{"are_you":null}""") should be(
                TestClasses.ClassWithNullableAndIncompatibleValidation(null)
            )
        }

        test(
            "A data class with an Nullable Boolean member and incompatible validation annotation is readValue-able from a JSON object without that field"
        ) {
            readValue<TestClasses.ClassWithNullableAndIncompatibleValidation>("""{}""") should be(
                TestClasses.ClassWithNullableAndIncompatibleValidation(null)
            )
        }

        test("deserialization#JsonCreator with Validation") {
            val e = assertThrows<DataClassMappingException> {
                // fails validation
                readValue<TestClasses.TestJsonCreatorWithValidation>("""{"s":""}""")
            }
            e.errors.size should be(1)
            e.errors.first().message should be("s: must not be empty")
        }

        test("deserialization#JsonCreator with Validation 1") {
            // works with multiple validations
            var e = assertThrows<DataClassMappingException> {
                // fails validation
                readValue<TestClasses.TestJsonCreatorWithValidations>("""{"s":""}""")
            }
            e.errors.size shouldBeEqual 2
            // errors are alpha-sorted by message to be stable for testing
            e.errors.first().message should be("s: <empty> not one of [42, 137]")
            e.errors.last().message should be("s: must not be empty")

            e = assertThrows<DataClassMappingException> {
                // fails validation
                readValue<TestClasses.TestJsonCreatorWithValidations>("""{"s":"99"}""")
            }
            e.errors.size shouldBeEqual 1
            e.errors.first().message should be("s: 99 not one of [42, 137]")

            readValue<TestClasses.TestJsonCreatorWithValidations>("""{"s":"42"}""") should be(
                TestClasses.TestJsonCreatorWithValidations("42")
            )

            readValue<TestClasses.TestJsonCreatorWithValidations>("""{"s":"137"}""") should be(
                TestClasses.TestJsonCreatorWithValidations(137)
            )
        }

        test("deserialization#JsonCreator with Validation 2") {
            val uuid = UUID.randomUUID().toString()

            var e = assertThrows<DataClassMappingException> {
                // fails validation
                readValue<TestClasses.ClassWithMultipleConstructorsAnnotatedAndValidations>(
                    """{"number_as_string1":"","number_as_string2":"20002","third_argument":"$uuid"}"""
                )
            }
            e.errors.size should be(1)
            e.errors.first().message should be("number_as_string1: must not be empty")

            e = assertThrows<DataClassMappingException> {
                // fails validation
                readValue<TestClasses.ClassWithMultipleConstructorsAnnotatedAndValidations>(
                    """{"number_as_string1":"","number_as_string2":"65789","third_argument":"$uuid"}"""
                )
            }
            e.errors.size should be(2)
            // errors are alpha-sorted by message to be stable for testing
            e.errors.first().message should be("number_as_string1: must not be empty")
            e.errors.last().message should be("number_as_string2: 65789 not one of [10001, 20002, 30003]")

            e = assertThrows<DataClassMappingException> {
                // fails validation
                readValue<TestClasses.ClassWithMultipleConstructorsAnnotatedAndValidations>(
                    """{"number_as_string1":"","number_as_string2":"65789","third_argument":"foobar"}"""
                )
            }
            e.errors.size should be(3)
            // errors are alpha-sorted by message to be stable for testing
            e.errors.first().message should be("number_as_string1: must not be empty")
            e.errors[1].message should be("number_as_string2: 65789 not one of [10001, 20002, 30003]")
            e.errors[2].message should be("third_argument: must be a valid UUID")

            readValue<TestClasses.ClassWithMultipleConstructorsAnnotatedAndValidations>(
                """{"number_as_string1":"12345","number_as_string2":"20002","third_argument":"$uuid"}"""
            ) should be(
                TestClasses.ClassWithMultipleConstructorsAnnotatedAndValidations(12345L, 20002L, uuid)
            )
        }

        test("deserialization#mixin annotations with validations") {
            val json = """{"first":{"x":-1,"y":120},"second":{"x":4,"y":5}}"""

            val e = assertThrows<DataClassMappingException> {
                readValue<TestClasses.Points>(json)
            }
            e.errors.size shouldBeEqual 2
            e.errors.first().message should be("first.x: must be greater than or equal to 0")
            when (val detail = e.errors.first().reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Field)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.propertyPath.toString() should be("Point.x")
                    violation.message should be("must be greater than or equal to 0")
                    violation.invalidValue shouldBeEqual -1
                    violation.rootBeanClass should be(TestClasses.Point::class.java)
                    violation.rootBean should beNull()
                }

                else ->
                    fail("")
            }
            e.errors.last().message should be("first.y: must be less than or equal to 100")
            when (val detail = e.errors.last().reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Field)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.propertyPath.toString() should be("Point.y")
                    violation.message should be("must be less than or equal to 100")
                    violation.invalidValue shouldBeEqual 120
                    violation.rootBeanClass should be(TestClasses.Point::class.java)
                    violation.rootBean should beNull()
                }

                else -> fail("")
            }
        }
    }
}
package angstromio.json

import angstromio.flags.Flags
import angstromio.json.exceptions.DataClassFieldMappingException
import angstromio.json.exceptions.DataClassMappingException
import angstromio.util.extensions.Anys.isInstanceOf
import angstromio.validation.constraints.OneOf
import angstromio.validation.constraints.UUID
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import jakarta.validation.ConstraintViolation
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import org.junit.jupiter.api.assertThrows

class JSONFlagTypeTest : FunSpec() {

    private val mapper: ObjectMapper = ObjectMapper().defaultMapper()

    data class FooClass(val name: String)
    data class ClassWithValidation(
        @NotEmpty val name: String,
        @Min(20) val size: Int,
        @UUID val uuid: String,
        @OneOf(value = ["A", "B", "C"]) val choice: String
    )

    init {

        test("JSONFlagType#JSON default") {
            val jsonFlagType = JSONFlagType<FooClass>()
            val expected = FooClass("Alice Smith")
            val parsedByArgType: FooClass = jsonFlagType.convert("""{"name":"Alice Smith"}""", "person")
            parsedByArgType shouldBeEqual expected

            val flags = Flags("test")
            val personFlag = flags.required(
                name = "person",
                description = "The name of a person as a JSON blob.",
                flagType = jsonFlagType
            )

            flags.parse(arrayOf("-person", """{"name":"Alice Smith"}"""))
            // assert values
            personFlag.value() shouldNot beNull()
            personFlag.value()?.shouldBeEqual(expected) ?: fail("")
        }

        test("JSONFlagType#JSON with mapper") {
            val jsonFlagType = JSONFlagType<FooClass>(mapper)
            val expected = FooClass("Alice Smith")
            val parsedByArgType: FooClass = jsonFlagType.convert("""{"name":"Alice Smith"}""", "person")
            parsedByArgType shouldBeEqual expected

            val flags = Flags("test")
            val personFlag = flags.required(
                name = "person",
                description = "The name of a person as a JSON blob.",
                flagType = jsonFlagType
            )

            flags.parse(arrayOf("-person", """{"name":"Alice Smith"}"""))
            // assert values
            personFlag.value() shouldNot beNull()
            personFlag.value()?.shouldBeEqual(expected) ?: fail("")
        }

        test("JSONFlagType#JSON with validation") {
            val jsonFlagType = JSONFlagType<ClassWithValidation>(mapper)

            /*
             * data class ClassWithValidation(
             *         @NotEmpty val name: String,
             *         @Min(20) val size: Int,
             *         @UUID val uuid: String,
             *         @OneOf(value = ["A", "B", "C"]) val choice: String)
             */
            val e = assertThrows<JsonMappingException> {
                jsonFlagType.convert("""{"name":"","size":0,"uuid":"DefinitelyNotAUUID","choice":"X"}""", "class")
            }
            e.isInstanceOf<DataClassMappingException>() should be(true)
            val errors = (e as DataClassMappingException).errors
            errors.size shouldBeEqual 4
            val mappedErrors = errors.associateBy { error -> error.path!!.names.joinToString(".") }

            val nameFieldMappingException = mappedErrors["name"]!!
            nameFieldMappingException.reason.message should be("must not be empty")
            nameFieldMappingException.reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = nameFieldMappingException.reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Field)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.propertyPath.toString() should be("ClassWithValidation.name")
                    violation.message should be("must not be empty")
                    violation.invalidValue should be("")
                    violation.rootBeanClass should be(ClassWithValidation::class.java)
                    violation.rootBean should beNull() // ValidationError.Field types do not have a root bean instance
                }

                else -> fail("")
            }

            val sizeFieldMappingException = mappedErrors["size"]!!
            sizeFieldMappingException.reason.message should be("must be greater than or equal to 20")
            sizeFieldMappingException.reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = sizeFieldMappingException.reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Field)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.propertyPath.toString() should be("ClassWithValidation.size")
                    violation.message should be("must be greater than or equal to 20")
                    violation.invalidValue should be(0)
                    violation.rootBeanClass should be(ClassWithValidation::class.java)
                    violation.rootBean should beNull() // ValidationError.Field types do not have a root bean instance
                }

                else -> fail("")
            }

            val choiceFieldMappingException = mappedErrors["choice"]!!
            choiceFieldMappingException.reason.message should be("X not one of [A, B, C]")
            choiceFieldMappingException.reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = choiceFieldMappingException.reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Field)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.propertyPath.toString() should be("ClassWithValidation.choice")
                    violation.message should be("X not one of [A, B, C]")
                    violation.invalidValue should be("X")
                    violation.rootBeanClass should be(ClassWithValidation::class.java)
                    violation.rootBean should beNull() // ValidationError.Field types do not have a root bean instance
                }

                else -> fail("")
            }

            val uuidFieldMappingException = mappedErrors["uuid"]!!
            uuidFieldMappingException.reason.message should be("must be a valid UUID")
            uuidFieldMappingException.reason.detail::class.java should be(DataClassFieldMappingException.ValidationError::class.java)
            when (val detail = uuidFieldMappingException.reason.detail) {
                is DataClassFieldMappingException.ValidationError -> {
                    detail.location should be(DataClassFieldMappingException.ValidationError.Location.Field)
                    val violation = detail.violation as ConstraintViolation<*>
                    violation.propertyPath.toString() should be("ClassWithValidation.uuid")
                    violation.message should be("must be a valid UUID")
                    violation.invalidValue should be("DefinitelyNotAUUID")
                    violation.rootBeanClass should be(ClassWithValidation::class.java)
                    violation.rootBean should beNull() // ValidationError.Field types do not have a root bean instance
                }

                else -> fail("")
            }
        }

        test("JSONFlagType#JSON with validation from resource") {
            val jsonFlagType = JSONFlagType<ClassWithValidation>(mapper)
            val expected = ClassWithValidation(
                name = "Alice Smith",
                size = 22,
                uuid = "ba138f15-e805-489f-b95c-e303304c8ba6",
                choice = "B"
            )
            val parsedByArgType: ClassWithValidation = jsonFlagType.convert("file://person.json", "class")
            parsedByArgType shouldBeEqual expected
        }
    }
}
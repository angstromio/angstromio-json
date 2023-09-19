package angstromio.json

import angstromio.json.exceptions.DataClassFieldMappingException
import angstromio.json.exceptions.DataClassMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.should
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.full.isSubclassOf

abstract class AbstractObjectMapperTest : FunSpec() {

    class MixInAnnotationsModule : SimpleModule() {
        init {
            setMixInAnnotation(TestClasses.Point::class.java, TestClasses.PointMixin::class.java)
            setMixInAnnotation(
                TestClasses.ClassShouldUseKebabCaseFromMixin::class.java, TestClasses.KebabCaseMixin::class.java
            )
        }
    }

    abstract val mapper: ObjectMapper

    internal inline fun <reified T : Any> readValue(string: String): T =
        mapper.readValue<T>(string)

    internal inline fun <reified T : Any> readValue(jsonNode: JsonNode): T =
        mapper.readValue<T>(jsonNode)

    internal inline fun <reified T : Any> readValue(obj: T): T =
        mapper.readValue<T>(mapper.writeValueAsBytes(obj))

    internal fun generate(any: Any): String =
        mapper.writeValueAsString(any)

    internal inline fun <reified T : Any> deserialize(m: ObjectMapper, value: String): T =
        m.readValue<T>(value)

    internal inline fun <reified T : Any> assertJson(obj: T, expected: String) {
        val json = generate(obj)
        JSONDiff.assertDiff(expected, json)
        readValue<T>(json) should be(obj)
    }

    internal inline fun <reified T : Any> assertObjectParseException(
        t: Throwable?,
        withErrors: List<String>
    ) {
        if (t == null) { fail("Throwable should not be null.") }
        t::class.isSubclassOf(DataClassMappingException::class) should be(true)
        assertObjectParseException<T>(t as DataClassMappingException, withErrors)
    }

    internal inline fun <reified T : Any> assertObjectParseException(
        e: DataClassMappingException,
        withErrors: List<String>
    ) {
        clearStackTrace(e.errors)

        val actualMessages = e.errors.map { it.message }
        JSONDiff.assertDiff(withErrors, actualMessages)
    }

    internal inline fun <reified T : Any> assertJsonParse(json: String, withErrors: List<String>) {
        if (withErrors.isNotEmpty()) {
            val e1 = assertThrows<DataClassMappingException> {
                val parsed = readValue<T>(json)
                println("Incorrectly parsed: " + mapper.writeValueAsString(parsed, prettyPrint = true))
            }
            assertObjectParseException<T>(e1, withErrors)

            // also check that we can parse into an intermediate JsonNode (throws an IllegalArgumentException with the DataClassMappingException as the cause)
            val e2 = assertThrows<IllegalArgumentException> {
                val jsonNode = readValue<JsonNode>(json)
                readValue<T>(jsonNode)
            }
            assertObjectParseException<T>(e2.cause, withErrors)
        } else readValue<T>(json)
    }

    private fun clearStackTrace(exceptions: List<DataClassFieldMappingException>): List<DataClassFieldMappingException> {
        exceptions.forEach { it.setStackTrace(emptyArray()) }
        return exceptions
    }
}
package angstromio.json

import angstromio.util.control.NonFatal
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.PrintStream

/**
 * This is based on the Scala utility [com.twitter.util.jackson.JsonDiff](https://github.com/twitter/util/blob/develop/util-jackson/src/main/scala/com/twitter/util/jackson/JsonDiff.scala)
 *
 * It is intended to provide two modes of computing the differences in JSON strings. One is
 * for programmatic use and the other is for assertions, generally more useful in testing.
 *
 * The `diff` functions provide a [JSONDiff.Result] that contains the difference information if
 * a difference was detected. This can be used to programmatically detect and handle differences
 * in JSON strings.
 *
 * The `assert` functions throw [AssertionError] when a difference is detected in the JSON strings.
 * These functions are generally more useful for the testing of JSON processing code.
 */
object JSONDiff {
    private val mapper: ObjectMapper = ObjectMapper().defaultMapper()
    private val sortingObjectMapper: ObjectMapper by lazy {
        val newMapper = mapper.makeCopy()
        newMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        newMapper.setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS)
        newMapper
    }

    /**
     * Creates a string representation of the given [JsonNode] with entries
     * sorted alphabetically by key.
     *
     * @param jsonNode - input [JsonNode]
     * @return string representation of the [JsonNode].
     */
    fun toSortedString(jsonNode: JsonNode): String {
        return if (jsonNode.isTextual) {
            jsonNode.textValue()
        } else {
            val node = sortingObjectMapper.treeToValue(jsonNode, Any::class.java)
            sortingObjectMapper.writeValueAsString(node)
        }
    }

    /** A [JSONDiff] result */
    data class Result internal constructor(
        val expected: JsonNode,
        val expectedPrettyString: String,
        val expectedJsonSorted: String,
        val actual: JsonNode,
        val actualPrettyString: String,
        val actualJsonSorted: String
    ) {
        override fun toString(): String {
            val expectedHeader = "Expected: "
            val diffStartIdx = actualJsonSorted.zip(expectedJsonSorted).indexOfFirst { (x, y) -> x != y }

            val message = StringBuilder()
            message.append(" ".repeat(expectedHeader.length + diffStartIdx) + "*\n")
            message.append(expectedHeader + expectedJsonSorted + "\n")
            message.append("Actual:   $actualJsonSorted")

            return message.toString()
        }
    }

    /**
     * Computes the diff for two snippets of json both of expected type `T`.
     * If a difference is detected a [Result] is returned otherwise a None.
     *
     * @param expected the expected json
     * @param actual the actual or received json
     * @return if a difference is detected a [Result]
     *         is returned otherwise a None.
     */
    inline fun <reified T : Any> diff(expected: T, actual: T): Result? = diff(expected, actual, null)

    /**
     * Computes the diff for two snippets of json both of expected type `T`.
     * If a difference is detected a [Result] is returned otherwise a None.
     *
     * @param expected the expected json
     * @param actual the actual or received json
     * @param normalizeFn a function to apply to the actual json in order to "normalize" values.
     *
     * @return if a difference is detected a [Result]
     *         is returned otherwise a None.
     *
     * ==Usage==
     *
     *
     *   private fun normalize(jsonNode: JsonNode): JsonNode = when(jsonNode) {
     *     ObjectNode -> on.put("time", "1970-01-01T00:00:00Z")
     *     else -> jsonNode
     *   }
     *
     *   val expected = """{"foo": "bar", "time": ""1970-01-01T00:00:00Z"}"""
     *   val actual = ??? ({"foo": "bar", "time": ""2021-05-14T00:00:00Z"})
     *   val result: JsonDiff.Result? = JsonDiff.diff(expected, actual, normalize)
     *
     */
    fun <T : Any> diff(
        expected: T,
        actual: T,
        normalizeFn: ((JsonNode) -> JsonNode)?
    ): Result? {
        val actualJson = jsonString(actual)
        val expectedJson = jsonString(expected)

        val jsonNode = tryJsonNodeParse(actualJson)
        val actualJsonNode: JsonNode =
            when (normalizeFn) {
                null -> jsonNode
                else -> normalizeFn.invoke(jsonNode)
            }

        val expectedJsonNode: JsonNode = tryJsonNodeParse(expectedJson)
        return if (actualJsonNode != expectedJsonNode) {
            val result = Result(
                expected = expectedJsonNode,
                expectedPrettyString = mapper.writeValueAsString(expectedJsonNode, prettyPrint = true),
                expectedJsonSorted = toSortedString(expectedJsonNode),
                actual = actualJsonNode,
                actualPrettyString = mapper.writeValueAsString(actualJsonNode, prettyPrint = true),
                actualJsonSorted = toSortedString(actualJsonNode)
            )
            result
        } else null
    }

    /**
     * Asserts that the actual equals the expected. Will throw an [AssertionError] with details
     * printed to [System.out].
     *
     * @param expected the expected json
     * @param actual the actual or received json
     *
     * @throws AssertionError - when the expected does not match the actual.
     */
    @Throws(AssertionError::class)
    fun <T : Any> assertDiff(expected: T, actual: T): Unit =
        assertDiff(expected, actual, null, System.out)

    /**
     * Asserts that the actual equals the expected. Will throw an [AssertionError] with details
     * printed to [System.out] using the given normalizeFn to normalize the actual contents.
     *
     * @param expected the expected json
     * @param actual the actual or received json
     * @param normalizeFn a function to apply to the actual json in order to "normalize" values.
     *
     * @throws AssertionError - when the expected does not match the actual.
     */
    @Throws(AssertionError::class)
    fun <T : Any> assertDiff(
        expected: T,
        actual: T,
        normalizeFn: ((JsonNode) -> JsonNode)?
    ): Unit = assertDiff(expected, actual, normalizeFn, System.out)

    /**
     * Asserts that the actual equals the expected. Will throw an [AssertionError] with details
     * printed to the given [PrintStream].
     *
     * @param expected the expected json
     * @param actual the actual or received json
     * @param p the [PrintStream] for reporting details
     *
     * @throws AssertionError - when the expected does not match the actual.
     */
    @Throws(AssertionError::class)
    fun <T : Any> assertDiff(expected: T, actual: T, p: PrintStream): Unit =
        assertDiff(expected, actual, null, p)

    /**
     * Asserts that the actual equals the expected. Will throw an [AssertionError] with details
     * printed to the given [PrintStream] using the given normalizeFn to normalize the actual contents.
     *
     * @param expected the expected json
     * @param actual the actual or received json
     * @param p the [PrintStream] for reporting details
     * @param normalizeFn a function to apply to the actual json in order to "normalize" values.
     *
     * @throws AssertionError - when the expected does not match the actual.
     */
    @Throws(AssertionError::class)
    fun <T : Any> assertDiff(
        expected: T,
        actual: T,
        normalizeFn: ((JsonNode) -> JsonNode)?,
        p: PrintStream
    ) {
        when (val result = diff(expected, actual, normalizeFn)) {
            null -> Unit // do nothing -- no difference
            else -> {
                p.println("JSON DIFF FAILED!")
                result.let { p.println(it) }
                throw AssertionError("${JSONDiff::class.qualifiedName} failure\n$result")
            }
        }
    }

    /* Private */

    private fun tryJsonNodeParse(expectedJsonStr: String): JsonNode {
        return try {
            mapper.readValue<JsonNode>(expectedJsonStr)
        } catch (e: Exception) {
            if (NonFatal.isNonFatal(e)) {
                TextNode(expectedJsonStr)
            } else throw e
        }
    }

    private fun jsonString(receivedJson: Any): String {
        return when (receivedJson) {
            is String -> receivedJson
            else -> mapper.writeValueAsString(receivedJson)
        }
    }
}
package angstromio.json

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import io.kotest.property.Arb
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.forAll
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class JsonDiffTest : FunSpec() {

    private val mapper = jacksonObjectMapper()
    private val baos = autoClose(ByteArrayOutputStream())
    private val ps = autoClose(PrintStream(baos, true, "utf-8"))
    
    init {

        beforeTest {
            ps.flush()
        }

        test("JsonDiff#JsonDiff success") {
            val a = """
          {
            "a": 1,
            "b": 2
          }
          """

            val b = """
          {
            "b": 2,
            "a": 1
          }
          """

            JsonDiff.diff(a, b) should beNull()
        }


        test("JsonDiff#JsonDiff with normalizer success") {
            val expected =
                """
              {
                "a": 1,
                "b": 2,
                "c": 5
              }
              """

            // normalizerFn will "normalize" the value for "c" into the expected value
            val actual =
                """
              {
                "b": 2,
                "a": 1,
                "c": 3
              }
              """

            val result = JsonDiff.diff(
                expected,
                actual,
                normalizeFn = { jsonNode: JsonNode ->
                    (jsonNode as ObjectNode).put("c", 5)
                }
            )
            result should beNull()
        }

        test("JsonDiff#JsonDiff failure") {
            val expected =
                """
              {
                "a": 1,
                "b": 2
              }
              """

            val actual =
                """
              {
                "b": 22,
                "a": 1
              }
              """

            val result = JsonDiff.diff(expected, actual)
            result shouldNot beNull()
        }

        test("JsonDiff#JsonDiff with normalizer failure") {
            val expected =
                """
              {
                "a": 1,
                "b": 2,
                "c": 5
              }
              """

            // normalizerFn only touches the value for "c" and "b" still doesn't match
            val actual =
                """
              {
                "b": 22,
                "a": 1,
                "c": 3
              }
              """

            val result = JsonDiff.diff(
                expected,
                actual,
                normalizeFn = { jsonNode: JsonNode ->
                    (jsonNode as ObjectNode).put("c", 5)
                }
            )
            result shouldNot beNull()
        }

        test("JsonDiff#assertDiff pass") {
            val expected =
                """
              {
                "a": 1,
                "b": 2
              }
            """

            val actual =
                """
              {
                "a": 1,
                "b": 2
              }
            """

            JsonDiff.assertDiff(expected, actual, p = ps)
        }

        test("JsonDiff#assertDiff with normalizer pass") {
            val expected =
                """
              {
                "a": 1,
                "b": 2,
                "c": 3
              }
            """

            // normalizerFn will "normalize" the value for "c" into the expected value
            val actual =
                """
              {
                "c": 5,
                "a": 1,
                "b": 2
              }
            """

            JsonDiff.assertDiff(
                expected,
                actual,
                normalizeFn = { jsonNode: JsonNode ->
                    (jsonNode as ObjectNode).put("c", 3)
                },
                p = ps
            )
        }

        test("JsonDiff#assertDiff with normalizer fail") {
            val expected =
                """
              {
                "a": 1,
                "b": 2,
                "c": 3
              }
            """

            // normalizerFn only touches the value for "c" and "a" still doesn't match
            val actual =
                """
              {
                "c": 5,
                "a": 11,
                "b": 2
              }
            """

            shouldThrow<AssertionError> {
                JsonDiff.assertDiff(
                    expected,
                    actual,
                    normalizeFn = { jsonNode: JsonNode ->
                        (jsonNode as ObjectNode).put("c", 3)
                    },
                    p = ps
                )
            }
        }

        test("JsonDiff#JsonDiff arbitrary pass") {
            val expected: Arb<ObjectNode> = Arb.int(1, 10).flatMap { depth -> JsonGenerator.objectNode(depth) }

            forAll(expected) { node ->
                val json = mapper.writeValueAsString(node)
                JsonDiff.diff(expected = json, actual = json) == null
            }
        }

        test("JsonDiff#JsonDiff arbitrary fail") {
            val values: Arb<Pair<ObjectNode?, ObjectNode?>> = Arb.int(1, 5).flatMap { depth ->
                JsonGenerator.objectNode(depth).flatMap { expected ->
                    JsonGenerator.objectNode(depth).map { actual ->
                        if (expected != actual) Pair(expected, actual)
                        else Pair(null, null)
                    }
                }
            }

            forAll(values) { value: Pair<ObjectNode?, ObjectNode?> ->
                val (expected, actual) = value
                if (expected != null && actual != null) JsonDiff.diff(expected = expected, actual = actual) != null
                else true // skip
            }
        }

        // (the arbitrary tests above do not generate tests for array depth 1)

        test("JsonDiff#JsonDiff array depth 1 fail") {
            val expected = """["a","b"]"""
            val actual = """["b","a"]"""
            JsonDiff.diff(expected, actual) shouldNot beNull()
        }

        test("JsonDiff#JsonDiff array depth 1 pass") {
            val expected = """["a","b"]"""
            val actual = """["a","b"]"""
            JsonDiff.diff(expected, actual) should beNull()
        }

        // (the arbitrary tests above do not generate tests with null values or escapes)

        test("JsonDiff#JsonDiff with null values - fail") {
            val expected = """{"a":null}"""
            val actual = "{}"
            JsonDiff.diff(expected, actual) shouldNot beNull()
        }

        test("JsonDiff#JsonDiff with null values - pass") {
            val expected = """{"a":null}"""
            val actual = """{"a": null}"""
            JsonDiff.diff(expected, actual) should beNull()
        }

        test("JsonDiff#JsonDiff with unicode escape - pass") {
            val expected = """{"t1": "24\u00B0F"}"""
            val actual = """{"t1": "24°F"}"""
            JsonDiff.diff(expected, actual) should beNull()
        }

        test("JsonDiff#toSortedString is sorted") {
            val before = mapper.readValue<JsonNode>("""{"a":1,"c":3,"b":2}""")
            val expected = """{"a":1,"b":2,"c":3}"""
            JsonDiff.toSortedString(before) shouldBeEqual expected
        }

        test("JsonDiff#toSortedString doesn't sort arrays") {
            val before = mapper.readValue<JsonNode>("""["b","a"]""")
            val expected = """["b","a"]"""
            JsonDiff.toSortedString(before) shouldBeEqual expected
        }

        test("JsonDiff#toSortedString includes null values") {
            val before = mapper.readValue<JsonNode>("""{"a":null}""")
            val expected = """{"a":null}"""
            JsonDiff.toSortedString(before) shouldBeEqual expected
        }
    }
}
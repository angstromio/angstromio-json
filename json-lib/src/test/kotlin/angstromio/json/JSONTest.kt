package angstromio.json

import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import java.io.ByteArrayInputStream
import java.io.File

class JSONTest : TestWithFileResources, FunSpec() {

    override val _folderName = ThreadLocal<File>()

    init {
        test("JSON#parse 1") {
            when (val map = JSON.parse<Map<String, Int>>("""{"a": 1, "b": 2}""")) {
                null -> fail("")
                else -> map shouldBeEqual mapOf(Pair("a", 1), Pair("b", 2))
            }
        }

        test("JSON#parse 2") {
            when (val result = JSON.parse<List<String>>("""["a", "b", "c"]""")) {
                null -> fail("")
                else -> result shouldBeEqual listOf("a", "b", "c")
            }
        }

        test("JSON#parse 3") {
            when (val foo = JSON.parse<FooClass>("""{"id": "abcd1234"}""")) {
                null -> fail("")
                else -> foo.id shouldBeEqual ("abcd1234")
            }
        }

        test("JSON#parse 4") {
            val inputStream = ByteArrayInputStream("""{"id": "abcd1234"}""".toByteArray(Charsets.UTF_8))
            inputStream.use { ins ->
                when (val foo = JSON.parse<FooClass>(ins)) {
                    null -> fail("")
                    else -> foo.id shouldBeEqual "abcd1234"
                }
            }
        }

        test("JSON#parse ") {
            withTempFolder {
                val file: File =
                    writeStringToFile(folderName(), "test-file", ".json", """{"id": "999999999"}""")

                when (val foo = JSON.parse<FooClass>(file)) {
                    null -> fail("")
                    else -> foo.id shouldBeEqual "999999999"
                }
            }
        }

        test("JSON#write 1") {
            JSON.write(mapOf(Pair("a", 1), Pair("b", 2))) shouldBeEqual """{"a":1,"b":2}"""
        }

        test("JSON#write 2") {
            JSON.write(listOf("a", "b", "c")) shouldBeEqual """["a","b","c"]"""
        }

        test("JSON#write 3") {
            JSON.write(FooClass("abcd1234")) shouldBeEqual """{"id":"abcd1234"}"""
        }

        test("JSON#prettyPrint 1") {
            JSON.prettyPrint(mapOf(Pair("a", 1), Pair("b", 2))) shouldBeEqual """{
  "a" : 1,
  "b" : 2
}""".trimMargin()
        }

        test("JSON#prettyPrint 2") {
            JSON.prettyPrint(listOf("a", "b", "c")) shouldBeEqual """[
  "a",
  "b",
  "c"
]""".trimMargin()
        }

        test("JSON#prettyPrint 3") {
            JSON.prettyPrint(FooClass("abcd1234")) shouldBeEqual """{
  "id" : "abcd1234"
}""".trimMargin()
        }

        test("JSON.Resource#parse resource") {
            when (val foo = JSON.Resource.parse<FooClass>("/test.json")) {
                null -> fail("")
                else -> foo.id shouldBeEqual "55555555"
            }
        }
    }
}
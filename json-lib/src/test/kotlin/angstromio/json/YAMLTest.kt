package angstromio.json

import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import java.io.ByteArrayInputStream
import java.io.File

class YAMLTest : TestWithFileResources, FunSpec() {

    override val _folderName = ThreadLocal<File>()

    init {
        test("YAML#readValue 1") {
            when (val map = YAML.readValue<Map<String, Int>>("""
        |---
        |a: 1
        |b: 2
        |c: 3""".trimMargin())) {
                null -> fail("")
                else -> map shouldBeEqual mapOf(Pair("a", 1), Pair("b", 2), Pair("c", 3))
            }
        }

        test("YAML#readValue 2") {
            // without quotes
            when (val list = YAML.readValue<List<String>>("""
        |---
        |- a
        |- b
        |- c""".trimMargin())) {
                null -> fail("")
                else -> list shouldBeEqual listOf("a", "b", "c")
            }
            // with quotes
            when (val list = YAML.readValue<List<String>>("""
                              |---
                              |- "a"
                              |- "b"
                              |- "c"""".trimMargin())) {
                null -> fail("")
                else -> list shouldBeEqual listOf("a", "b", "c")
            }
        }

        test("YAML#readValue 3") {
            // without quotes
            when (val foo = YAML.readValue<TestClasses.FooClass>("""
                           |---
                           |id: abcde1234""".trimMargin())) {
                null -> fail("")
                else -> foo.id shouldBeEqual "abcde1234"
            }
            // with quotes
            when (val foo = YAML.readValue<TestClasses.FooClass>("""
                           |---
                           |id: "abcde1234"""".trimMargin())) {
                null -> fail("")
                else -> foo.id shouldBeEqual "abcde1234"
            }
        }
        

        test("YAML#readValue 6") {
            val inputStream = ByteArrayInputStream("""{"id": "abcd1234"}""".toByteArray(Charsets.UTF_8))
            try {
                when (val foo = YAML.readValue<TestClasses.FooClass>(inputStream)) {
                    null -> fail("")
                    else -> foo.id shouldBeEqual "abcd1234"
                }
            } finally {
                inputStream.close()
            }
        }

        test("YAML#readValue 7") {
            withTempFolder {
                // without quotes
                val file1: File =
                    writeStringToFile(
                        folderName(),
                        "test-file-quotes",
                        ".yaml",
                        """|---
                            |id: 999999999""".trimMargin())
                when (val foo = YAML.readValue<TestClasses.FooClass>(file1)) {
                    null -> fail("")
                    else -> foo.id shouldBeEqual "999999999"
                }
                // with quotes
                val file2: File =
                    writeStringToFile(
                        folderName(),
                        "test-file-noquotes",
                        ".yaml",
                        """|---
             |id: "999999999"""".trimMargin())
                when (val foo = YAML.readValue<TestClasses.FooClass>(file2)) {
                    null -> fail("")
                    else -> foo.id shouldBeEqual "999999999"
                }
            }
        }

        test("YAML#write 1") {
            YAML.write(mapOf(Pair("a", 1), Pair("b", 2))) shouldBeEqual """|---
        |a: 1
        |b: 2
        |""".trimMargin()
        }

        test("YAML#write 2") {
            YAML.write(listOf("a", "b", "c")) shouldBeEqual """|---
        |- "a"
        |- "b"
        |- "c"
        |""".trimMargin()
        }

        test("YAML#write 3") {
            YAML.write(TestClasses.FooClass("abcd1234"))shouldBeEqual """|---
        |id: "abcd1234"
        |""".trimMargin()
        }

        test("YAML.Resource#readValue resource") {
            when (val foo = YAML.Resource.readValue<TestClasses.FooClass>("/test.yml")) {
                null -> fail("")
                else -> foo.id shouldBeEqual "55555555"
            }
        }
    }
}
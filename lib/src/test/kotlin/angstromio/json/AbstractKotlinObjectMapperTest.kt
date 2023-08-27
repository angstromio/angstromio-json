package angstromio.json

import io.kotest.core.spec.style.FunSpec

abstract class AbstractKotlinObjectMapperTest : FunSpec() {
    abstract val mapper: KotlinObjectMapper

    inline fun <reified T: Any> parse(string: String): T =
        mapper.parse<T>(string)

    fun generate(any: Any): String =
        mapper.writeValueAsString(any)
}
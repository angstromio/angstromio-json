package angstromio.json

import angstromio.util.io.ClasspathResource

/**
 * Uses an instance of a [KotlinObjectMapper] configured to not perform any type of validation.
 * Inspired by [scala.util.parsing.json.JSON](https://www.scala-lang.org/api/2.12.6/scala-parser-combinators/scala/util/parsing/json/JSON$.html).
 */
object YAML : AbstractSimpleMapper() {

    override val underlying: KotlinObjectMapper =
        KotlinObjectMapper.builder().withNoValidation().yamlObjectMapper()

    object Resource {

        /**
         * Simple utility to load a YAML resource from the classpath and parse contents into an Option[T] type.
         *
         * @note `name` resolution to locate the resource is governed by [[java.lang.Class#getResourceAsStream]]
         */
        inline fun <reified T : Any> parse(name: String): T? =
            when (val inputStream = ClasspathResource.load(name)) {
                null -> null
                else ->
                    try {
                        YAML.parse<T>(inputStream)
                    } finally {
                        inputStream.close()
                    }
            }
    }
}
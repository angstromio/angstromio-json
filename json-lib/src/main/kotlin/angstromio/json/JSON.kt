package angstromio.json

import angstromio.util.io.ClasspathResource
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Uses an instance of a [ObjectMapper] configured to not perform any type of validation.
 * Inspired by [scala.util.parsing.json.JSON](https://www.scala-lang.org/api/2.12.6/scala-parser-combinators/scala/util/parsing/json/JSON$.html).
 */
object JSON : AbstractSimpleMapper() {

    override val underlying: ObjectMapper =
        ObjectMapper().defaultMapper(enableValidation = false)

    object Resource {

        /**
         * Simple utility to load a JSON resource from the classpath and parse contents into an T? type.
         *
         * @note `name` resolution to locate the resource is governed by [[java.lang.Class#getResourceAsStream]]
         */
        inline fun <reified T : Any> readValue(name: String): T? =
            when (val inputStream = ClasspathResource.load(name)) {
                null -> null
                else ->
                    inputStream.use { s ->
                        JSON.readValue<T>(s)
                    }
            }
    }
}
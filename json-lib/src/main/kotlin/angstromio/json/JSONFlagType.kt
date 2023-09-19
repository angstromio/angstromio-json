package angstromio.json

import angstromio.flags.FlagType
import angstromio.util.io.ClasspathResource
import com.fasterxml.jackson.databind.ObjectMapper
class JSONFlagType<T : Any>(
    private val mapper: ObjectMapper,
    private val clazz: Class<T>
) : FlagType<T>(true) {

    companion object {
        inline operator fun <reified T : Any> invoke(mapper: ObjectMapper, clazz: Class<T>): JSONFlagType<T> =
            JSONFlagType(mapper, clazz)

        inline operator fun <reified T : Any> invoke(mapper: ObjectMapper): JSONFlagType<T> =
            JSONFlagType(mapper, T::class.java)

        inline operator fun <reified T : Any> invoke(): JSONFlagType<T> = JSONFlagType(JSON.mapper, T::class.java)
    }

    override val description: kotlin.String
        get() {
            return "{ Value should be a valid JSON blob of point to a file with text that can be parsed as a valid JSON blob }"
        }

    override fun convert(value: kotlin.String, name: kotlin.String): T {
        return if (value.startsWith("file://")) {
            // read from classpath resource
            val inputStream = ClasspathResource.load(value.substring(7))
            inputStream.use { ist ->
                mapper.readValue(ist, clazz)
            }
        } else mapper.readValue(value, clazz)
    }
}
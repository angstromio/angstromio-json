package angstromio.json

import angstromio.util.control.NonFatal
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.io.InputStream

abstract class AbstractSimpleMapper {

    internal abstract val underlying: ObjectMapper

    val mapper: ObjectMapper by lazy { underlying }

    /** Simple utility to readValue a JSON string into an T? type. */
    inline fun <reified T : Any> readValue(input: String): T? =
        NonFatal.tryOrNull<T> { this.mapper.readValue<T>(input) }

    /**
     * Simple utility to readValue a JSON [InputStream] into an T? type.
     * @note the caller is responsible for managing the lifecycle of the given [InputStream].
     */
    inline fun <reified T : Any> readValue(input: InputStream): T? =
        NonFatal.tryOrNull<T> { this.mapper.readValue<T>(input) }

    /**
     * Simple utility to load a JSON file and readValue contents into an T? type.
     *
     * @note the caller is responsible for managing the lifecycle of the given [File].
     */
    inline fun <reified T : Any> readValue(f: File): T? =
        NonFatal.tryOrNull<T> { this.mapper.readValue<T>(f) }

    /** Simple utility to write a value as a JSON encoded String. */
    fun write(any: Any): String = this.mapper.writeValueAsString(any)

    /** Simple utility to pretty print a JSON encoded String from the given instance. */
    fun prettyPrint(any: Any): String = this.mapper.writeValueAsString(any, prettyPrint = true)
}
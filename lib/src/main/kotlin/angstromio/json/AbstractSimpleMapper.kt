package angstromio.json

import angstromio.util.control.NonFatal
import java.io.File
import java.io.InputStream

abstract class AbstractSimpleMapper {

    internal abstract val underlying: KotlinObjectMapper

    fun mapper(): KotlinObjectMapper = underlying

    /** Simple utility to parse a JSON string into an T? type. */
    inline fun <reified T : Any> parse(input: String): T? =
        NonFatal.tryOrNull<T> { this.mapper().parse<T>(input) }

    /**
     * Simple utility to parse a JSON [InputStream] into an T? type.
     * @note the caller is responsible for managing the lifecycle of the given [InputStream].
     */
    inline fun <reified T : Any> parse(input: InputStream): T? =
        NonFatal.tryOrNull<T> { this.mapper().parse<T>(input) }

    /**
     * Simple utility to load a JSON file and parse contents into an T? type.
     *
     * @note the caller is responsible for managing the lifecycle of the given [File].
     */
    inline fun <reified T : Any> parse(f: File): T? =
        NonFatal.tryOrNull<T> { this.mapper().parse<T>(f) }

    /** Simple utility to write a value as a JSON encoded String. */
    fun write(any: Any): String = this.mapper().writeValueAsString(any)

    /** Simple utility to pretty print a JSON encoded String from the given instance. */
    fun prettyPrint(any: Any): String = this.mapper().writeValueAsString(any, prettyPrint = true)
}
package angstromio.json.exceptions

import com.fasterxml.jackson.databind.JsonMappingException

class DataClassMappingException(
    private val filedMappingExceptions: Set<DataClassFieldMappingException> = emptySet()
): JsonMappingException(null, "") {

    /**
     * The collection of [DataClassFieldMappingException] instances which make up this
     * [DataClassMappingException]. This collection is intended to be purposely exhaustive in that
     * it specifies all errors encountered in mapping JSON content to a data class.
     */
    val errors: List<DataClassFieldMappingException> = filedMappingExceptions.sortedBy { it.message }

    // helpers for formatting the exception message
    private val errorsSize = errors.size
    private val messagePreambleString =
        if (errorsSize == 1) "An error was " else "$errorsSize errors "
    private val errorsString =
        if (errorsSize == 1) "Error: " else "Errors: "
    private val errorsSeparatorString = "\n\t        "

    /**
     * Formats a human-readable message which includes the underlying [DataClassMappingException] messages.
     *
     * ==Example==
     * Multiple errors:
     * {{{
     *   2 errors encountered during deserialization.
     * 	     Errors: angstromio.json.exceptions.DataClassMappingException: data: must not be empty
     * 	             angstromio.json.exceptions.DataClassMappingException: number: must be greater than or equal to 5
     * }}}
     * Single error:
     * {{{
     *   An error was encountered during deserialization.
     *       Error: angstromio.json.exceptions.DataClassMappingException: data: must not be empty
     * }}}
     */
    override val message: String
        get() = "${messagePreambleString}encountered during deserialization.\n\t$errorsString" +
                errors.joinToString(errorsSeparatorString)
}
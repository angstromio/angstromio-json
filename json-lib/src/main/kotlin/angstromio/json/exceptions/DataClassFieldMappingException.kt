package angstromio.json.exceptions

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException

data class DataClassFieldMappingException(
    val path: PropertyPath?,
    val reason: Reason
) : JsonMappingException(null, reason.message) {

    sealed interface Detail {
        data object Unspecified : Detail
        data object RequiredFieldMissing : Detail
    }

    data class ValidationError(
        val violation: Any,
        val location: Location,
        val payload: Any?
    ) : Detail {
            sealed interface Location {
                data object Field : Location
                data object Method : Location
            }
    }

    data class JsonProcessingError(val cause: JsonProcessingException): Detail
    data class ThrowableError(val message: String, val cause: Throwable): Detail

    data class Reason(
        val message: String?,
        val detail: Detail = Detail.Unspecified
    )

    data class PropertyPath(val names: List<String>) {
        companion object {
            val Empty = PropertyPath(emptyList())
            private const val FIELD_SEPARATOR = "."
            fun leaf(name: String): PropertyPath = Empty.withParent(name)
        }

        fun withParent(name: String): PropertyPath = copy(names = listOf(name) + names)

        fun isEmpty(): Boolean = names.isEmpty()
        fun prettyString(): String = names.joinToString(FIELD_SEPARATOR)
    }

    override val message: String?
        get() =
            if (path == null || path.isEmpty()) {
                reason.message
            } else {
                "${path.prettyString()}: ${reason.message}"
            }

    fun withPropertyPath(path: PropertyPath): DataClassFieldMappingException = this.copy(path = path)

    fun scoped(fieldName: String): DataClassFieldMappingException = copy(path = path?.withParent(fieldName))
}
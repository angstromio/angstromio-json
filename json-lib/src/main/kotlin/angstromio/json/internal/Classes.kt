package angstromio.json.internal

internal object Classes {

    /**
     * Convert 'getFoo' or 'setFoo' to 'foo'
     */
    fun String.asFieldName(): String = if (this.startsWith("get") || this.startsWith("set")) {
        this.substring(3).replaceFirstChar { it.lowercase() }
    } else this

    fun isNotAssignableFrom(clazz: Class<*>?, other: Class<*>): Boolean =
        clazz != null && !isAssignableFrom(other, clazz)

    fun isAssignableFrom(fromType: Class<*>, toType: Class<*>?): Boolean {
        return if (toType == null) {
            false
        } else {
            Types.wrapperType(toType).isAssignableFrom(Types.wrapperType(fromType))
        }
    }
}
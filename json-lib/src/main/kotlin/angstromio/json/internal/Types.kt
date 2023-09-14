package angstromio.json.internal

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.type.ArrayType
import com.fasterxml.jackson.databind.type.TypeBindings
import com.fasterxml.jackson.databind.type.TypeFactory
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable

internal object Types {

    // Marker for "nullable" values -- allows passing non-null types until the instantiation of the data class
    data object Null

    /* Primitive type to boxed primitive class */
    fun wrapperType(clazz: Class<*>): Class<*> = when (clazz) {
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        Character.TYPE -> java.lang.Character::class.java
        Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        Void.TYPE -> java.lang.Void::class.java
        else -> clazz
    }

    fun javaType(
        typeFactory: TypeFactory,
        clazz: Class<*>
    ): JavaType {
        val asBoxedType = wrapperType(clazz)

        return if (asBoxedType.typeParameters.isEmpty() || asBoxedType.isEnum) {
            typeFactory.constructType(asBoxedType)
        } else if (Map::class.java.isAssignableFrom(asBoxedType)) {
            typeFactory.constructMapLikeType(
                asBoxedType,
                javaType(typeFactory, asBoxedType.typeParameters.first().genericDeclaration),
                javaType(typeFactory, asBoxedType.typeParameters.last().genericDeclaration)
            )
        } else if (Collection::class.java.isAssignableFrom(asBoxedType)) {
            typeFactory.constructCollectionLikeType(
                asBoxedType, javaType(typeFactory, asBoxedType.typeParameters.first().genericDeclaration)
            )
        } else if (asBoxedType.isArray) {
            // need to special-case array creation
            ArrayType.construct(
                javaType(typeFactory, asBoxedType.typeParameters.first().genericDeclaration),
                TypeBindings.create(
                    java.util.ArrayList::class.java, // we hardcode the type to `java.util.ArrayList` to properly support Array creation
                    javaType(typeFactory, asBoxedType.typeParameters.first().genericDeclaration)
                )
            )
        } else {
            typeFactory.constructParametricType(
                asBoxedType,
                *javaTypes(
                    typeFactory,
                    asBoxedType.typeParameters.map { it.genericDeclaration }.toTypedArray()
                ).toTypedArray()
            )
        }
    }

    fun javaTypes(
        typeFactory: TypeFactory,
        parameterTypes: Array<Class<*>>
    ): List<JavaType> = parameterTypes.map { javaType(typeFactory, it) }

    fun javaType(
        typeFactory: TypeFactory,
        clazz: Class<*>,
        typeParameters: List<JavaType>
    ): JavaType {
        val asBoxedType = wrapperType(clazz)

        return if (asBoxedType.typeParameters.isEmpty() || clazz.isEnum) {
            typeParameters.first()
        } else if (Map::class.java.isAssignableFrom(asBoxedType)) {
            typeFactory.constructMapLikeType(
                asBoxedType, typeParameters.first(), typeParameters.last()
            )
        } else if (Collection::class.java.isAssignableFrom(asBoxedType)) {
            typeFactory.constructCollectionLikeType(
                asBoxedType, typeParameters.first()
            )
        } else if (asBoxedType.isArray) {
            // need to special-case array creation
            // we hardcode the type to `java.util.ArrayList` to properly support Array creation
            ArrayType.construct(
                javaType(typeFactory, asBoxedType.typeParameters.first().genericDeclaration, typeParameters),
                TypeBindings.create(java.util.ArrayList::class.java, typeParameters.first())
            )
        } else {
            typeFactory.constructParametricType(asBoxedType, *typeParameters.toTypedArray())
        }
    }

    fun <T : Any> isValueClass(clazz: Class<T>): Boolean {
        return clazz.declaredAnnotations.any {
            it.annotationClass.qualifiedName == "kotlin.jvm.JvmInline"
        }
    }

    /**
     * If the given [java.lang.reflect.Type] is parameterized, return an Array of the
     * type parameter names. E.g., `Map[T, U]` returns, `Array("T", "U")`.
     *
     * The use case is when we are trying to match the given type's type parameters to
     * a set of bindings that are stored keyed by the type name, e.g. if the given type
     * is a `Map[K, V]` we want to be able to look up the binding for the key `K` at runtime
     * during reflection operations e.g., if the K type is bound to a String we want to
     * be able to use that when further processing this type.
     */
    fun parameterizedTypeNames(type: Type): Array<String> = when (type) {
        is ParameterizedType ->
            type.actualTypeArguments.map { scrubTypeName(it.typeName) }.toTypedArray()

        is TypeVariable<*> ->
            arrayOf(type.typeName)

        is Class<*> ->
            type.getTypeParameters().map { scrubTypeName(it.typeName) }.toTypedArray()

        else -> {
            emptyArray()
        }
    }

    private fun scrubTypeName(name: String): String =
        when {
            name.startsWith("? extends") -> name.substring(9).trim()
            else -> name
        }
}
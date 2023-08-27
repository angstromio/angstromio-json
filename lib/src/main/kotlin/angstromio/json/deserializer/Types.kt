package angstromio.json.deserializer

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.type.ArrayType
import com.fasterxml.jackson.databind.type.TypeBindings
import com.fasterxml.jackson.databind.type.TypeFactory
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure

object Types {
    private const val MAX_DEPTH = 100 // try to prevent a stack overflow due to cycles
    private const val DEFAULT_LOOKUP_CACHE_SIZE: Long = 128
    // simple memoization of the default instance lookup for a KClass<*>
    private val DefaultInstanceLookupCache: Cache<KClass<*>, Any> =
        Caffeine
            .newBuilder()
            .maximumSize(DEFAULT_LOOKUP_CACHE_SIZE)
            .build<KClass<*>, Any>()
 
    internal fun wrapperType(clazz: Class<*>): Class<*> =
        when (clazz.name) {
            "java.lang.Byte" -> java.lang.Byte::class.java
            "java.lang.Short" -> java.lang.Short::class.java
            "java.lang.Character" -> java.lang.Character::class.java
            "java.lang.Integer" -> java.lang.Integer::class.java
            "java.lang.Long" -> java.lang.Long::class.java
            "java.lang.Float" -> java.lang.Float::class.java
            "java.lang.Double" -> java.lang.Double::class.java
            "java.lang.Boolean" -> java.lang.Boolean::class.java
            "java.lang.Void" -> java.lang.Void::class.java
            else -> clazz
        }

    @Suppress("UNCHECKED_CAST")
    internal fun getDefaultFunctionForParameter(clazz: KClass<*>, index: Int): (() -> Any?)? {
        val constructor = clazz.primaryConstructor
        val parameter = (clazz.primaryConstructor?.parameters ?: emptyList())[index]
        return if (parameter.isOptional) {
            // we memoize the default instance creation
            val defaultInstance = DefaultInstanceLookupCache.get(clazz) {
                findDefaultInstance(constructor)
                    ?: error("Error constructing default instance for class ${clazz.qualifiedName}")
            }
            val property = (defaultInstance::class as KClass<Any>).memberProperties.first { m ->
                m.name == parameter.name
            }
            return { property.get(defaultInstance) }
        } else null
    }

    private fun findDefaultInstance(constructor: KFunction<*>?): Any? {
        val args = constructor?.parameters
            ?.filterNot { it.isOptional }
            ?.associate { it to defaultValue(it) }
        return if (args != null) {
            constructor.callBy(args)
        } else null
    }

    private fun defaultValue(parameter: KParameter): Any {
        val clazzifier = parameter.type.classifier
        return clazzifier?.let {
            getDefaultValue(it, 0)
        } ?: throw IllegalArgumentException("No KClassifier found for parameter type `${parameter.type}`")
    }

    private fun getDefaultValue(clazzifier: KClassifier, cycle: Int = 0): Any? {
        if (cycle > MAX_DEPTH) throw IllegalArgumentException("Too many cycles detected, there is likely a cyclical object relationship. Aborting.")
        return when (clazzifier) {
            Byte::class -> 0
            Short::class -> 0
            Int::class -> 0
            Long::class -> 0L
            Float::class -> 0.0f
            Double::class -> 0.0
            Char::class -> ""
            String::class -> ""
            Boolean::class -> false
            List::class -> emptyList<Any>()
            Set::class -> emptySet<Any>()
            Array::class -> emptyArray<Any>()
            Sequence::class -> emptySequence<Any>()
            Map::class -> emptyMap<Any, Any>()
            Any::class -> Any()
            else -> {
                val clazz = Class.forName(clazzifier.starProjectedType.javaType.typeName).kotlin
                val constructor = clazz.primaryConstructor!!
                constructor.javaConstructor!!.newInstance(*constructor.parameters.map { p ->
                        getDefaultValue(p.type.classifier!!, (cycle + 1))
                    }.toTypedArray()
                )
            }
        }
    }

    fun parameterizedTypeNames(type: Type): Array<String> =
        when(type) {
            is ParameterizedType ->
                type.actualTypeArguments.map { it.typeName }.toTypedArray()
             is TypeVariable<*> ->
                arrayOf(type.typeName)
            is Class<*> ->
                type.getTypeParameters().map { it.typeName }.toTypedArray()
            else -> {
                emptyArray()
            }
    }

    fun javaType(
        typeFactory: TypeFactory,
        kotlinType: KType?
    ): JavaType {
        if (kotlinType == null) throw IllegalArgumentException("")
        val clazzType: Class<*> = kotlinType.jvmErasure.java
        return if (clazzType.typeParameters.isEmpty() || clazzType.isEnum) {
            typeFactory.constructType(clazzType)
        } else if (Collection::class.java.isAssignableFrom(clazzType)) {
            if (Map::class.java.isAssignableFrom(clazzType)) {
                typeFactory.constructMapLikeType(
                    clazzType,
                    javaType(typeFactory, kotlinType.arguments.first().type),
                    javaType(typeFactory, kotlinType.arguments.last().type)
                )
            } else if (clazzType.isArray) {
                // need to special-case array creation
                ArrayType.construct(
                    javaType(typeFactory, kotlinType.arguments.first().type),
                    TypeBindings
                        .create(
                            java.util.ArrayList::class.java, // we hardcode the type to `java.util.ArrayList` to properly support Array creation
                            javaType(typeFactory, kotlinType.arguments.first().type)
                        )
                )
            } else {
                typeFactory.constructCollectionLikeType(
                    clazzType,
                    javaType(typeFactory, kotlinType.arguments.first().type)
                )
            }
        } else {
            typeFactory.constructParametricType(
                clazzType,
                *javaTypes(typeFactory, kotlinType.arguments.map { it.type }).toTypedArray()
            )
        }
    }

    fun javaType(
        typeFactory: TypeFactory,
        kotlinType: KType?,
        typeParameters: Array<JavaType>
    ): JavaType {
        if (kotlinType == null) throw IllegalArgumentException("")
        val clazzType: Class<*> = kotlinType.jvmErasure.java
        return if (clazzType.typeParameters.isEmpty() || clazzType.isEnum) {
            typeParameters.first()
        } else if (Collection::class.java.isAssignableFrom(clazzType)) {
            if (Map::class.java.isAssignableFrom(clazzType)) {
                typeFactory.constructMapLikeType(
                    clazzType,
                    typeParameters.first(),
                    typeParameters.last()
                )
            } else if (clazzType.isArray) {
                // need to special-case array creation
                // we hardcode the type to `java.util.ArrayList` to properly support Array creation
                ArrayType.construct(
                    javaType(typeFactory, kotlinType.arguments.first().type, typeParameters),
                    TypeBindings.create(java.util.ArrayList::class.java, typeParameters.first())
                )
            } else {
                typeFactory.constructCollectionLikeType(
                    clazzType,
                    typeParameters.first()
                )
            }
        } else {
            typeFactory.constructParametricType(clazzType, *typeParameters)
        }
    }

    private fun javaTypes(
        typeFactory: TypeFactory,
        kotlinTypes: List<KType?>
    ): List<JavaType> = kotlinTypes.map { javaType(typeFactory, it) }
}
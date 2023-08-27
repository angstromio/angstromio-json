package angstromio.json.deserializer

import angstromio.json.deserializer.DataClassBeanProperty.Companion.newAnnotatedParameter
import angstromio.json.exceptions.DataClassFieldMappingException
import angstromio.json.exceptions.DataClassMappingException
import angstromio.util.control.NonFatal.isNonFatal
import angstromio.util.extensions.getConstructorAnnotations
import angstromio.util.reflect.Annotations
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyName
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.introspect.AnnotatedConstructor
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod
import com.fasterxml.jackson.databind.introspect.AnnotatedWithParams
import com.fasterxml.jackson.databind.introspect.AnnotationMap
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.fasterxml.jackson.databind.introspect.TypeResolutionContext
import com.fasterxml.jackson.databind.util.SimpleBeanPropertyDefinition
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.jvmErasure

internal class DataClassDeserializer<T : Any>(
    jt: JavaType?,
    cfg: DeserializationConfig?,
    bd: BeanDescription?,
    validator: Any?
) : JsonDeserializer<Any>() {
    private val javaType: JavaType = jt!!
    private val config: DeserializationConfig = cfg!!
    private val beanDescription: BeanDescription = bd!!

    init {
        // nested class inside another class is not supported, e.g., we do not support
        // use of creators for non-static inner classes,
        assert(!beanDescription.isNonStaticInnerClass) { "Non-static inner case classes are not supported." }
    }

    private val clazz: Class<out Any> = javaType.rawClass
    private val kClazz: KClass<out Any> by lazy { clazz::kotlin.get() }
    private val mixinClazz: KClass<*>? = config.findMixInClassFor(clazz)?.let {
        if (it.isPrimitive) null
        else it.kotlin
    }
    private val clazzAnnotations: List<Annotation> =
        when (mixinClazz) {
            null -> kClazz.annotations
            else -> kClazz.annotations + mixinClazz.annotations
        }
    private val dataClazzCreator: DataClassCreator = getDataClassCreator()

    // all class annotations -- including inherited annotations for fields
    // use the carried type in order to find the appropriate constructor by the
    // unresolved types -- e.g., before we resolve generic types to their bound type
    private val allAnnotations: Map<String, List<Annotation>> =
        kClazz.getConstructorAnnotations(dataClazzCreator.propertyDefinitions.map { it.type.rawClass.kotlin })

    // support for reading annotations from Jackson Mix-ins
    // see: https://github.com/FasterXML/jackson-docs/wiki/JacksonMixInAnnotations
    private val allMixinAnnotations: Map<String, List<Annotation>> =
        mixinClazz?.memberFunctions?.associate { it.name to it.annotations } ?: emptyMap()

    // optimized lookup of bean property definition annotations
    private fun getBeanPropertyDefinitionAnnotations(beanPropertyDefinition: BeanPropertyDefinition): List<Annotation> {
        return if (beanPropertyDefinition.primaryMember.allAnnotations.size() > 0) {
            beanPropertyDefinition.primaryMember.allAnnotations.annotations().toList()
        } else emptyList()
    }

    // Field name to list of parsed annotations. Jackson only tracks JacksonAnnotations
    // in the BeanPropertyDefinition AnnotatedMembers, and we want to track all class annotations by field.
    // Annotations are keyed by parameter name because the logic collapses annotations from the
    // inheritance hierarchy where the discriminator is member name.
    private val fieldAnnotations: Map<String, List<Annotation>> = getFieldAnnotations()
    private fun getFieldAnnotations(): Map<String, List<Annotation>> {
        val fieldBeanPropertyDefinitions: List<BeanPropertyDefinition> =
            dataClazzCreator.propertyDefinitions.map { it.beanPropertyDefinition }
        val collectedFieldAnnotations = hashMapOf<String, List<Annotation>>()
        var index = 0
        while (index < fieldBeanPropertyDefinitions.size) {
            val fieldBeanPropertyDefinition = fieldBeanPropertyDefinitions[index]
            val fieldName = fieldBeanPropertyDefinition.internalName
            // in many cases we will have the field annotations in the `allAnnotations` Map which
            // scans for annotations from the class definition being deserialized, however in some cases
            // we are dealing with an annotated field from a static or secondary constructor
            // and thus the annotations may not exist in the `allAnnotations` Map, so we default to
            // any carried bean property definition annotations which includes annotation information
            // for any static or secondary constructor.
            val annotations: List<Annotation> =
                allAnnotations.getOrElse(fieldName) { getBeanPropertyDefinitionAnnotations(fieldBeanPropertyDefinition) } +
                        allMixinAnnotations.getOrElse(fieldName) { emptyList() }
            if (annotations.isNotEmpty()) collectedFieldAnnotations[fieldName] = annotations
            index += 1
        }

        return collectedFieldAnnotations
    }

    private val propertyNamingStrategy: PropertyNamingStrategy? by lazy {
        when (val jsonNaming = Annotations.findAnnotation<JsonNaming>(clazzAnnotations)) {
            null -> config.propertyNamingStrategy
            else -> jsonNaming.value.java.getDeclaredConstructor().newInstance()
        }
    }

    private val fields: Array<DataClassField> = DataClassField.createFields(
        kClazz,
        dataClazzCreator.executable,
        dataClazzCreator.propertyDefinitions,
        fieldAnnotations,
        propertyNamingStrategy
    )

    private val numConstructorArgs by lazy { fields.size }
    private val isWrapperClass: Boolean by lazy { isValueClass(this.javaClass) }
    private val firstFieldName by lazy { fields.first().name }

    override fun isCachable(): Boolean = true

    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): Any {
        return if (isWrapperClass) {
            deserializeWrapperClass(p ?: throw IllegalStateException(), ctxt ?: throw IllegalStateException())
        } else {
            deserializeNonWrapperClass(p ?: throw IllegalStateException(), ctxt ?: throw IllegalStateException())
        }
    }

    private fun deserializeWrapperClass(jsonParser: JsonParser, context: DeserializationContext): Any {
        if (jsonParser.currentToken.isStructStart) {
            try {
                context.handleUnexpectedToken(
                    clazz,
                    jsonParser.currentToken(),
                    jsonParser,
                    "Unable to deserialize wrapped value from a json object"
                )
            } catch (e: Exception) {
                if (isNonFatal(e)) {
                    // wrap in a JsonMappingException
                    throw JsonMappingException.from(jsonParser, e.message)
                }
            }
        }

        val jsonNode = context.nodeFactory.objectNode()
        jsonNode.put(firstFieldName, jsonParser.text)
        return deserialize(jsonParser, context, jsonNode)
    }

    private fun deserializeNonWrapperClass(jsonParser: JsonParser, context: DeserializationContext): Any {
        incrementParserToFirstField(jsonParser, context)
        val jsonNode = jsonParser.readValueAsTree<JsonNode>()
        return deserialize(jsonParser, context, jsonNode)
    }

    private fun deserialize(
        jsonParser: JsonParser, context: DeserializationContext, jsonNode: JsonNode
    ): Any {
        val jsonFieldNames: List<String> = jsonNode.fieldNames().asSequence().toList()
        val dataClassFieldNames: List<String> = fields.map { it.name }

        handleUnknownFields(jsonParser, context, jsonFieldNames, dataClassFieldNames)
        val (values, parseErrors) = parseConstructorValues(jsonParser, context, jsonNode)

//        // run field validations
//        val validationErrors = executeFieldValidations(
//            validator,
//            caseClazzCreator.executableValidationDescription,
//            config,
//            values,
//            fields)

        if (parseErrors.isNotEmpty()) throw DataClassMappingException(parseErrors.toSet())

        return newInstance(values)
    }

    private fun getDataClassCreator(): DataClassCreator {
        val fromCompanion: AnnotatedMethod? =
            beanDescription.factoryMethods.find { it.hasAnnotation(JsonCreator::class.java) }
        val fromClazz: AnnotatedConstructor? =
            beanDescription.constructors.find { it.hasAnnotation(JsonCreator::class.java) }
        return when {
            fromCompanion != null ->
                DataClassCreator(
                    fromCompanion.annotated,
                    getBeanPropertyDefinitions(
                        fromCompanion.annotated.parameters,
                        fromCompanion,
                        fromCompanion = true/*,
                        validator.map(_.describeExecutable(fromCompanion.getAnnotated, mixinClazz)),
                        validator.map(_.describeMethods(clazz)*/
                    )
                )
            else ->
                // check clazz
                if (fromClazz != null) {
                    DataClassCreator(
                        fromClazz.annotated,
                        getBeanPropertyDefinitions(fromClazz.annotated.parameters, fromClazz)
                        /*,
                        validator.map(_.describeExecutable(fromClazz.annotated, mixinClazz)),
                        validator.map(_.describeMethods(clazz))*/
                    )
                } else {
                    // try to use what Jackson thinks is the default -- however Jackson does not
                    // seem to correctly track an empty default constructor for case classes, nor
                    // multiple un-annotated, and we have no way to pick a proper constructor, so we bail
                    val constructor: AnnotatedConstructor =
                        beanDescription.classInfo.defaultConstructor ?: findAnnotatedConstructor(beanDescription)
                    DataClassCreator(
                        constructor.annotated,
                        getBeanPropertyDefinitions(constructor.annotated.parameters, constructor)/*,
                        validator.map(_.describeExecutable(constructor.annotated, mixinClazz)),
                        validator.map(_.describeMethods(clazz)*/
                    )
                }
        }
    }

    private fun findAnnotatedConstructor(beanDescription: BeanDescription): AnnotatedConstructor {
        val constructors = beanDescription.classInfo.constructors
        assert(constructors.size == 1) { "Multiple data class constructors not supported" }
//        return annotateConstructor(constructors.first(), clazzAnnotations.toList())
        return constructors.first()
    }

    private fun annotateConstructor(
        constructor: KFunction<Class<*>>, annotations: List<Annotation>
    ): AnnotatedConstructor {
        val paramAnnotationMaps: List<AnnotationMap> =
            constructor.parameters.map { it.annotations }.map { parameterAnnotations ->
                    val parameterAnnotationMap = AnnotationMap()
                    parameterAnnotations.map { parameterAnnotationMap.add(it) }
                    parameterAnnotationMap
                }

        val annotationMap = AnnotationMap()
        annotations.map { annotationMap.add(it) }
        return AnnotatedConstructor(
            TypeResolutionContext.Basic(config.typeFactory, javaType.bindings),
            constructor.javaConstructor,
            annotationMap,
            paramAnnotationMaps.toTypedArray()
        )
    }

    /** Return all "unknown" properties sent in the incoming JSON */
    private fun unknownProperties(
        context: DeserializationContext, jsonFieldNames: List<String>, dataClassFieldNames: List<String>
    ): List<String> {
        val ignoreAnnotation = Annotations.findAnnotation<JsonIgnoreProperties>(clazzAnnotations)
        // if there is a JsonIgnoreProperties annotation on the class, it should prevail
        val nonIgnoredFields: List<String> = when {
            ignoreAnnotation != null && !ignoreAnnotation.ignoreUnknown -> jsonFieldNames.minus(ignoreAnnotation.value.toSet())

            ignoreAnnotation == null && context.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) -> jsonFieldNames

            else -> emptyList()
        }

        // if we have any non ignored field, return the difference
        return if (nonIgnoredFields.isNotEmpty()) {
            nonIgnoredFields.minus(dataClassFieldNames.toSet())
        } else emptyList()
    }

    /** Throws a [DataClassMappingException] with a set of [DataClassFieldMappingException] per unknown field */
    private fun handleUnknownFields(
        jsonParser: JsonParser,
        context: DeserializationContext,
        jsonFieldNames: List<String>,
        dataClassFieldNames: List<String>
    ) {
        // handles more or less incoming fields in the JSON than are defined in the data class
        val unknownFields: List<String> = unknownProperties(context, jsonFieldNames, dataClassFieldNames)
        if (unknownFields.isNotEmpty()) {
            val errors = unknownFields.map { field ->
                val unrecognizedPropertyException =
                    UnrecognizedPropertyException.from(jsonParser, clazz, field, dataClassFieldNames)
                DataClassFieldMappingException(
                    DataClassFieldMappingException.PropertyPath.Empty, DataClassFieldMappingException.Reason(
                        message = unrecognizedPropertyException.message
                    )
                )
            }
            throw DataClassMappingException(errors.toSet())
        }
    }

    private fun incrementParserToFirstField(
        jsonParser: JsonParser, context: DeserializationContext
    ) {
        if (jsonParser.currentToken == JsonToken.START_OBJECT) {
            jsonParser.nextToken()
        }
        if (jsonParser.currentToken != JsonToken.FIELD_NAME && jsonParser.currentToken != JsonToken.END_OBJECT) {
            try {
                context.handleUnexpectedToken(clazz, jsonParser)
            } catch (e: Exception) {
                if (isNonFatal(e)) {
                    when (e) {
                        is JsonProcessingException -> {
                            // don't include source info since it's often blank.
                            e.clearLocation()
                            throw JsonParseException(jsonParser, e.message)
                        }

                        else -> throw JsonParseException(jsonParser, e.message)
                    }
                }
            }
        }
    }

    private fun parseConstructorValues(
        jsonParser: JsonParser,
        context: DeserializationContext,
        jsonNode: JsonNode
    ): Pair<List<Any?>, List<DataClassFieldMappingException>> {/* Mutable Fields */
        var constructorValuesIdx = 0
        val errors = mutableListOf<DataClassFieldMappingException>()
        val constructorValues = mutableListOf<Any?>()
        while (constructorValuesIdx < numConstructorArgs) {
            val field = fields[constructorValuesIdx]
            try {
                constructorValues.add(field.parse(context, jsonParser.codec, jsonNode))
            } catch (e: DataClassFieldMappingException) {
                if (e.path == null) {
                    // fill in missing path detail
                    addException(
                        field,
                        e.withPropertyPath(DataClassFieldMappingException.PropertyPath.leaf(field.name)),
                        constructorValues,
                        constructorValuesIdx,
                        errors
                    )
                } else {
                    addException(
                        field, e, constructorValues, constructorValuesIdx, errors
                    )
                }
            } catch (e: InvalidFormatException) {
                addException(
                    field, DataClassFieldMappingException(
                        DataClassFieldMappingException.PropertyPath.leaf(field.name),
                        DataClassFieldMappingException.Reason(
                            "'${e.value}' is not a valid ${Types.wrapperType(e.targetType).getSimpleName()}${validValuesString(e)}", DataClassFieldMappingException.JsonProcessingError(e)
                        )
                    ), constructorValues, constructorValuesIdx, errors
                )
            } catch (e: MismatchedInputException) {
                addException(
                    field, DataClassFieldMappingException(
                        DataClassFieldMappingException.PropertyPath.leaf(field.name),
                        DataClassFieldMappingException.Reason(
                            "'${jsonNode.asText("")}' is not a valid ${Types.wrapperType(e.targetType).getSimpleName()}${validValuesString(e)}", DataClassFieldMappingException.JsonProcessingError(e)
                        )
                    ), constructorValues, constructorValuesIdx, errors
                )
            } catch (e: DataClassMappingException) {
                constructorValues.add(field.missingValue)
                errors + e.errors.map { it.scoped(field.name) }
            } catch (e: JsonProcessingException) {
                // don't include source info since it's often blank. Consider adding e.getCause.getMessage
                e.clearLocation()
                addException(
                    field, DataClassFieldMappingException(
                        DataClassFieldMappingException.PropertyPath.leaf(field.name),
                        DataClassFieldMappingException.Reason(
                            e.message, DataClassFieldMappingException.JsonProcessingError(e)
                        )
                    ), constructorValues, constructorValuesIdx, errors
                )
//            } catch (e: java.util.NoSuchElementException) {
//                if isScalaEnumerationType(field) =>
//                // Scala enumeration mapping issue
//                addException(
//                    field,
//                    DataClassFieldMappingException(
//                        DataClassFieldMappingException.PropertyPath.leaf(field.name),
//                        DataClassFieldMappingException.Reason(e.getMessage)
//                    ),
//                    constructorValues,
//                    constructorValuesIdx,
//                    errors
//                )
//            } catch (e: InjectableValuesException) {
//                // we rethrow, to prevent leaking internal injection detail in the "errors" array
//                throw e
            } catch (e: Exception) {
                if (isNonFatal(e))
                //log.error("Unexpected exception parsing field: $field", e)
                    throw e
            }
            constructorValuesIdx += 1
        }


        return Pair(constructorValues.toList(), errors.toList())
    }

    /** Add the given exception to the given array buffer of errors while also adding a missing value field to the given array */
    private fun addException(
        field: DataClassField,
        e: DataClassFieldMappingException,
        constructorValues: MutableList<Any?>,
        index: Int,
        errors: MutableList<DataClassFieldMappingException>
    ) {
        constructorValues[index] = field.missingValue
        errors += e
    }

    private fun validValuesString(e: MismatchedInputException): String =
        if (e.targetType != null && e.targetType.isEnum) {
            " with valid values: " + e.targetType.enumConstants.joinToString(", ")
        } else {
            ""
        }

    private fun newInstance(constructorValues: List<Any?>): Any {
        val obj = try {
            instantiate(constructorValues)
        } catch (e: Throwable) {
            when (e) {
                is InvocationTargetException -> if (e.cause == null) throw e else throw e.cause!!

                is ExceptionInInitializerError -> if (e.cause == null) throw e else throw e.cause!!

                else -> throw e
            }
        }
//        val methodValidationErrors =
//            executeMethodValidations(
//                validator,
//                caseClazzCreator.executableValidationMethodDescriptions,
//                config,
//                obj
//            )
//        if (methodValidationErrors.nonEmpty)
//            throw DataClassMappingException(methodValidationErrors.toSet)

        return obj
    }

    private fun instantiate(constructorValues: List<Any?>): Any =
        when (val executable = dataClazzCreator.executable) {
            is Method ->
                // if the creator is of type Method, we assume the need to invoke the companion object
                executable.invoke(kClazz.primaryConstructor, *constructorValues.toTypedArray())

            is Constructor<*> ->
                // otherwise simply invoke the constructor
                executable.newInstance(*constructorValues.toTypedArray())
        }

    /* in order to deal with parameterized types we create a JavaType here and carry it */
    private fun getBeanPropertyDefinitions(
        parameters: Array<Parameter>, annotatedWithParams: AnnotatedWithParams, fromCompanion: Boolean = false
    ): Array<PropertyDefinition> {
        val constructorParameters = if (fromCompanion) {
            val kFunction = kClazz.companionObject?.declaredFunctions?.find { it.hasAnnotation<JsonCreator>() }
            kFunction!!.parameters.filter { it.kind == KParameter.Kind.VALUE }
        } else {
            // need to find the description which carries the full type information
            kClazz.primaryConstructor!!.parameters
        }

        if (constructorParameters.size != parameters.size) throw IllegalArgumentException("")
        val propertyDefinitions = arrayOfNulls<PropertyDefinition>(parameters.size)
        for ((index, parameter) in parameters.withIndex()) {
            val constructorParamDescriptor = constructorParameters[index]
            val kotlinType: KType = constructorParamDescriptor.type

            val parameterJavaType =
                if (kotlinType.jvmErasure.java.typeParameters.isNotEmpty() &&
                    shouldFullyDefineParameterizedType(kotlinType,parameter)
                ) {
                    // what types are bound to the generic data class parameters
                    val boundTypeParameters: Array<JavaType> =
                        Types
                            .parameterizedTypeNames(parameter.getParameterizedType())
                            .map { javaType.bindings.findBoundType(it) }.toTypedArray()
                    Types
                        .javaType(config.typeFactory, kotlinType, boundTypeParameters)
                } else {
                    Types.javaType(config.typeFactory, kotlinType)
                }

            val annotatedParameter = newAnnotatedParameter(
                typeResolutionContext =
                    TypeResolutionContext.Basic(
                        config.typeFactory, javaType.bindings
                    ), // use the TypeBindings from the top-level JavaType, not the parameter JavaType
                owner = annotatedWithParams,
                annotations = annotatedWithParams.getParameterAnnotations(index),
                javaType = parameterJavaType,
                index = index
            )

            propertyDefinitions[index] =
                PropertyDefinition(
                    parameterJavaType,
                    SimpleBeanPropertyDefinition.construct(config, annotatedParameter, PropertyName(constructorParamDescriptor.name))
                )
        }
        return propertyDefinitions.map { it!! }.toTypedArray()
    }

    // if we need to attempt to fully specify the JavaType because it is generically types
    private fun shouldFullyDefineParameterizedType(
        kotlinType: KType, parameter: Parameter
    ): Boolean {
        // only need to fully specify if the type is parameterized, and it has more than one type arg
        // or its typeArg is also parameterized.
        fun isParameterized(reflectionType: Type): Boolean = when (reflectionType) {
            is ParameterizedType -> true
            is TypeVariable<*> -> true
            else -> false
        }

        val parameterizedType = parameter.getParameterizedType()

        return !kotlinType.javaClass.isPrimitive && (kotlinType.jvmErasure.typeParameters.isEmpty() || kotlinType.jvmErasure.typeParameters.first().javaClass.isAssignableFrom(
            Any::class.java
        )) && parameterizedType != parameter.getType() && isParameterized(parameterizedType)
    }

    companion object {
        val EmptyJsonParser: JsonParser = JsonFactory().createParser("")

        /* For supporting JsonCreator */
        data class DataClassCreator(
            val executable: Executable,
            val propertyDefinitions: Array<PropertyDefinition>/*,
            val executableValidationDescription: ExecutableDescriptor?,
            val executableValidationMethodDescriptions: Array<MethodDescriptor>?*/
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as DataClassCreator

                if (executable != other.executable) return false
                if (!propertyDefinitions.contentEquals(other.propertyDefinitions)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = executable.hashCode()
                result = 31 * result + propertyDefinitions.contentHashCode()
                return result
            }
        }

        data class PropertyDefinition(
            val type: JavaType, val beanPropertyDefinition: BeanPropertyDefinition
        )

        private fun applyPropertyNamingStrategy(
            config: DeserializationConfig, fieldName: String
        ): String = config.propertyNamingStrategy.nameForField(config, null, fieldName)

        fun <T : Any> isValueClass(clazz: Class<T>): Boolean {
            return clazz.declaredAnnotations.any {
                it.annotationClass.qualifiedName == "kotlin.jvm.JvmInline"
            }
        }
    }
}
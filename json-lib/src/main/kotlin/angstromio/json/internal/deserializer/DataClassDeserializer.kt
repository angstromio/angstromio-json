package angstromio.json.internal.deserializer

import angstromio.json.exceptions.DataClassFieldMappingException
import angstromio.json.exceptions.DataClassMappingException
import angstromio.json.internal.Classes.asFieldName
import angstromio.json.internal.Types
import angstromio.json.internal.deserializer.DataClassBeanProperty.Companion.newAnnotatedParameter
import angstromio.util.control.NonFatal.isNonFatal
import angstromio.util.extensions.Annotations.getConstructorAnnotations
import angstromio.util.extensions.Annotations.merge
import angstromio.util.extensions.Anys.isInstanceOf
import angstromio.util.extensions.Nulls.mapNotNull
import angstromio.util.reflect.Annotations
import angstromio.validation.DataClassValidator
import angstromio.validation.extensions.getDynamicPayload
import angstromio.validation.extensions.getLeafNode
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
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import com.fasterxml.jackson.databind.introspect.AnnotatedConstructor
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod
import com.fasterxml.jackson.databind.introspect.AnnotatedWithParams
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.fasterxml.jackson.databind.introspect.TypeResolutionContext
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TreeTraversingParser
import com.fasterxml.jackson.databind.type.ArrayType
import com.fasterxml.jackson.databind.type.TypeBindings
import com.fasterxml.jackson.databind.util.SimpleBeanPropertyDefinition
import jakarta.validation.ConstraintViolation
import jakarta.validation.Payload
import jakarta.validation.ValidationException
import jakarta.validation.Validator
import jakarta.validation.metadata.BeanDescriptor
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
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.kotlinFunction

@Suppress("UNCHECKED_CAST")
internal class DataClassDeserializer(
    private val javaType: JavaType,
    private val config: DeserializationConfig,
    beanDesc: BeanDescription?,
    private val validator: Validator?
) : JsonDeserializer<Any>() {
    // these should not actually be null
    private val beanDescription: BeanDescription = beanDesc!!

    init {
        // nested class inside another class is not supported, e.g., we do not support
        // use of creators for non-static inner classes,
        if (beanDescription.isNonStaticInnerClass) {
            throw InvalidDefinitionException.from(
                TreeTraversingParser(NullNode.instance), "Non-static inner data classes are not supported.", javaType
            )
        }
    }

    private val clazz: Class<out Any> = javaType.rawClass
    private val kClazz: KClass<out Any> by lazy { clazz::kotlin.get() }

    private val mixinClazz: Class<*>? = config.findMixInClassFor(clazz)
    private val clazzAnnotations: Array<Annotation> = when (mixinClazz) {
        null -> clazz.annotations
        else -> mixinClazz.annotations.merge(clazz.annotations)
    }
    private val creator: DataClassCreator = getDataClassCreator()

    // all class annotations -- including inherited annotations for fields
    // use the carried type in order to find the appropriate constructor by the
    // unresolved types -- e.g., before we resolve generic types to their bound type
    private val allAnnotations: Map<String, Array<Annotation>> = clazz.getConstructorAnnotations(creator.parameterTypes)

    // support for reading annotations from Jackson Mix-ins
    // see: https://github.com/FasterXML/jackson-docs/wiki/JacksonMixInAnnotations
    private val allMixinAnnotations: Map<String, Array<Annotation>> =
        mixinClazz?.declaredMethods?.associate { it.name.asFieldName() to it.annotations } ?: emptyMap()

    private fun getBeanPropertyDefinitionAnnotations(beanPropertyDefinition: BeanPropertyDefinition): Array<Annotation> {
        return if (beanPropertyDefinition.primaryMember.allAnnotations.size() > 0) {
            // TODO("can we optimize to not have the toList call here?")
            val list = beanPropertyDefinition.primaryMember.allAnnotations.annotations().toList()
            Array(list.size) { index -> list[index] }
        } else emptyArray()
    }

    // Field name to list of parsed annotations. Jackson only tracks JacksonAnnotations
    // in the BeanPropertyDefinition AnnotatedMembers, and we want to track all class annotations by field.
    // Annotations are keyed by parameter name because the logic collapses annotations from the
    // inheritance hierarchy where the discriminator is member name.
    private val fieldAnnotations: Map<String, Array<Annotation>> = getFieldAnnotations()
    private fun getFieldAnnotations(): Map<String, Array<Annotation>> {
        val fieldBeanPropertyDefinitions: List<BeanPropertyDefinition> =
            creator.propertyDefinitions.map { it.beanPropertyDefinition }
        val collectedFieldAnnotations = hashMapOf<String, Array<Annotation>>()
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
            val a: Array<Annotation> = allAnnotations.getOrElse(fieldName) {
                getBeanPropertyDefinitionAnnotations(fieldBeanPropertyDefinition)
            }
            val b: Array<Annotation> = allMixinAnnotations.getOrElse(fieldName) { emptyArray() }
            val annotations: Array<Annotation> = a.merge(b)
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

    private val fields: Array<DataClassField> =
        DataClassField.createFields(
            kClazz = kClazz,
            constructor = creator.executable,
            propertyDefinitions = creator.propertyDefinitions,
            fieldAnnotations = fieldAnnotations,
            namingStrategy = propertyNamingStrategy
        )

    private val numConstructorArgs by lazy { fields.size }
    private val isWrapperClass: Boolean by lazy { Types.isValueClass(this.javaClass) }
    private val firstFieldName by lazy { fields.first().name }

    override fun isCachable(): Boolean = true

    override fun deserialize(
        jsonParser: JsonParser,
        context: DeserializationContext
    ): Any? {
        return if (isWrapperClass) {
            deserializeWrapperClass(jsonParser, context)
        } else {
            deserializeNonWrapperClass(jsonParser , context)
        }
    }

    private fun deserializeWrapperClass(
        jsonParser: JsonParser,
        context: DeserializationContext
    ): Any? {
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

    private fun deserializeNonWrapperClass(
        jsonParser: JsonParser,
        context: DeserializationContext
    ): Any? {
        incrementParserToFirstField(jsonParser, context)
        val jsonNode = jsonParser.readValueAsTree<JsonNode>()
        return deserialize(jsonParser, context, jsonNode)
    }

    private fun deserialize(
        jsonParser: JsonParser,
        context: DeserializationContext,
        jsonNode: JsonNode
    ): Any? {
        val jsonFieldNames: List<String> = jsonNode.fieldNames().asSequence().toList()

        handleUnknownFields(jsonParser, context, jsonFieldNames, fields.map { it.name })
        val (values, parseErrors) = parseConstructorValues(jsonParser, context, jsonNode)

        // execute field validations
        val validationErrors =
            executeFieldValidations(
                validator = validator,
                config = config,
                kotlinFunction = creator.kotlinFunction,
                mixinClazz = mixinClazz,
                fieldNames = fields.map { it.name },
                parameterValues = fields.map { field -> values[field.parameter] },
                instance = creator.getInstanceParameter?.second
            )

        val errors = parseErrors + validationErrors
        if (errors.isNotEmpty()) throw DataClassMappingException(errors.toSet())

        try {
            return newInstance(jsonParser, fields, values)
        } catch (e: DataClassFieldMappingException) {
            throw DataClassMappingException(setOf(e))
        }
    }

    private fun getDataClassCreator(): DataClassCreator {
        val fromCompanion: AnnotatedMethod? =
            beanDescription.factoryMethods.find { it.allAnnotations.get(JsonCreator::class.java) != null }
        val fromClazz: AnnotatedConstructor? =
            beanDescription.constructors.find { it.allAnnotations.get(JsonCreator::class.java) != null }
        return when {
            fromCompanion != null -> {
                DataClassCreator(
                    kotlinFunction = fromCompanion.annotated.kotlinFunction,
                    parameters = fromCompanion.annotated.kotlinFunction?.parameters ?: emptyList(),
                    companion = beanDescription.beanClass.kotlin.companionObjectInstance,
                    executable = fromCompanion.annotated,
                    javaType = javaType,
                    propertyDefinitions = getBeanPropertyDefinitions(
                        constructionParameters = fromCompanion.annotated.kotlinFunction?.parameters,
                        parameters = fromCompanion.annotated.parameters,
                        annotatedWithParams = fromCompanion
                    ),
                    beanDescriptor = validator.mapNotNull { it.getConstraintsForClass(clazz) })
            }

            else ->
                // check clazz
                if (fromClazz != null) {
                    DataClassCreator(kotlinFunction = fromClazz.annotated.kotlinFunction,
                        parameters = fromClazz.annotated.kotlinFunction?.parameters ?: emptyList(),
                        companion = beanDescription.beanClass.kotlin.companionObjectInstance,
                        executable = fromClazz.annotated,
                        javaType = javaType,
                        propertyDefinitions = getBeanPropertyDefinitions(
                            constructionParameters = fromClazz.annotated.kotlinFunction?.parameters,
                            parameters = fromClazz.annotated.parameters,
                            annotatedWithParams = fromClazz
                        ),
                        beanDescriptor = validator.mapNotNull { it.getConstraintsForClass(clazz) })
                } else {
                    // try to use what Jackson thinks is the default
                    val constructor: AnnotatedConstructor = findAnnotatedConstructor(beanDescription)

                    // TODO("handle synthetics?")
                    DataClassCreator(kotlinFunction = constructor.annotated.kotlinFunction,
                        parameters = constructor.annotated.kotlinFunction?.parameters ?: emptyList(),
                        companion = beanDescription.beanClass.kotlin.companionObjectInstance,
                        executable = constructor.annotated,
                        javaType = javaType,
                        propertyDefinitions = getBeanPropertyDefinitions(
                            constructionParameters = constructor.annotated.kotlinFunction?.parameters,
                            parameters = constructor.annotated.parameters,
                            annotatedWithParams = constructor
                        ),
                        beanDescriptor = validator.mapNotNull { it.getConstraintsForClass(clazz) })
                }
        }
    }

    private fun findAnnotatedConstructor(beanDescription: BeanDescription): AnnotatedConstructor {
        val constructors = beanDescription.classInfo.constructors
        // https://github.com/FasterXML/jackson-module-kotlin#caveats
        // default jackson-module-kotlin ObjectMapper appears to allow this to work despite the stated
        // caveats around @JsonCreator usage but, we fail in order to not try to instantiate with wrong constructor.
        if (constructors.size > 1) throw InvalidDefinitionException.from(
            TreeTraversingParser(NullNode.instance),
            "Multiple constructors requires the use of the @JsonCreator annotation to specify the constructor to use for object instantiation.",
            javaType
        )
        return constructors.first()
    }

    /** Return all "unknown" properties sent in the incoming JSON */
    private fun unknownProperties(
        context: DeserializationContext, jsonFieldNames: List<String>, dataClassFieldNames: List<String>
    ): List<String> {
        val ignoreAnnotation = Annotations.findAnnotation<JsonIgnoreProperties>(clazzAnnotations)
        // if there is a JsonIgnoreProperties annotation on the class, it should prevail
        val nonIgnoredFields: List<String> = when {
            ignoreAnnotation != null && !ignoreAnnotation.ignoreUnknown ->
                jsonFieldNames.minus(ignoreAnnotation.value.toSet())

            ignoreAnnotation == null && context.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) ->
                jsonFieldNames

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
        jsonParser: JsonParser,
        context: DeserializationContext
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
    ): Pair<Map<KParameter, Any?>, List<DataClassFieldMappingException>> {
        var constructorValuesIdx = 0
        val errors = mutableListOf<DataClassFieldMappingException>()
        val constructorValues = mutableMapOf<KParameter, Any?>()//mutableListOf<Any?>()
        while (constructorValuesIdx < numConstructorArgs) {
            val field = fields[constructorValuesIdx]
            try {/*
                   Matrix:
                   ========================================
                   s: String            -> required          (value is required, cannot be null)
                   s: String = "foo"    -> optional          (value not required, value cannot be null)
                   s: String?           -> required nullable (value required, can be null)
                   s: String? = "foo"   -> optional nullable (value not required but if there, can be null)
                 */
                when (val value = field.parse(context, jsonParser.codec, jsonNode)) {
                    null -> {
                        // null is only acceptable when the field is nullable
                        if (field.isMarkedNullable) {
                            // now is it optional or required
                            if (field.isOptional) {
                                // do not include, optional nullable
                            } else {
                                // required
                                throwRequiredFieldException(field)
                            }
                        } else if (field.isOptional) {
                            // no value and field is optional, do not include
                        } else {
                            // required
                            throwRequiredFieldException(field)
                        }
                    }

                    Types.Null -> { // explicitly set null, should only happen for nullables and want to set the null value
                        if (field.isMarkedNullable) {
                            constructorValues[field.parameter] = null
                        } else if (field.isOptional) {
                            // optional but was given an explicitly set null, include the null
                            constructorValues[field.parameter] = null
                        } else {
                            // required
                            throwRequiredFieldException(field)
                        }
                    }

                    else -> // include field
                        constructorValues[field.parameter] = value//box(field, value)
                }
            } catch (e: DataClassFieldMappingException) {
                errors += if (e.path == null) {
                    // fill in missing path detail
                    constructorValues[field.parameter] = field.missingValue
                    e.withPropertyPath(DataClassFieldMappingException.PropertyPath.leaf(field.name))
                } else {
                    constructorValues[field.parameter] = field.missingValue
                    e
                }
            } catch (e: InvalidFormatException) {
                constructorValues[field.parameter] = field.missingValue
                errors += DataClassFieldMappingException(
                    DataClassFieldMappingException.PropertyPath.leaf(field.name), DataClassFieldMappingException.Reason(
                        "'${e.value}' is not a valid ${
                            Types.wrapperType(e.targetType).getSimpleName()
                        }${validValuesString(e)}", DataClassFieldMappingException.JsonProcessingError(e)
                    )
                )
            } catch (e: MismatchedInputException) {
                constructorValues[field.parameter] = field.missingValue
                errors += DataClassFieldMappingException(
                    DataClassFieldMappingException.PropertyPath.leaf(field.name), DataClassFieldMappingException.Reason(
                        "'${jsonNode.asText("")}' is not a valid ${
                            Types.wrapperType(e.targetType).getSimpleName()
                        }${validValuesString(e)}", DataClassFieldMappingException.JsonProcessingError(e)
                    )
                )
            } catch (e: DataClassMappingException) {
                constructorValues[field.parameter] = field.missingValue
                errors += e.errors.map { it.scoped(field.name) }
            } catch (e: JsonProcessingException) {
                // don't include source info since it's often blank. Consider adding e.getCause.getMessage
                e.clearLocation()
                constructorValues[field.parameter] = field.missingValue
                errors += DataClassFieldMappingException(
                    DataClassFieldMappingException.PropertyPath.leaf(field.name), DataClassFieldMappingException.Reason(
                        e.message, DataClassFieldMappingException.JsonProcessingError(e)
                    )
                )
            } catch (e: java.util.NoSuchElementException) {
                if (field.javaType.rawClass.isEnum) {
                    // enumeration mapping issue
                    constructorValues[field.parameter] = field.missingValue
                    errors += DataClassFieldMappingException(
                        DataClassFieldMappingException.PropertyPath.leaf(field.name),
                        DataClassFieldMappingException.Reason(e.message)
                    )
                } else throw e
            } catch (e: Exception) {
                if (isNonFatal(e)) throw e // TODO("log exception in error here")
            }
            constructorValuesIdx += 1
        }

        return Pair(constructorValues.toMap(), errors.toList())
    }

    private fun newInstance(
        jsonParser: JsonParser,
        fields: Array<DataClassField>,
        parameterValues: Map<KParameter, Any?>
    ): Any? {
        val values = if (creator.hasInstanceParameter) {
            mapOf(creator.getInstanceParameter!!) + parameterValues
        } else parameterValues

        val obj = try {
            creator.kotlinFunction!!.callBy(values)
        } catch (e: Throwable) {
            if (e.isInstanceOf<IllegalArgumentException>()) {
                // translate into DataClassFieldMappingException
                handleFieldRequired(fields, e)
            }
            val message = when (e) {
                is InvocationTargetException -> if (e.cause == null) e.message else e.cause!!.message

                is ExceptionInInitializerError -> if (e.cause == null) e.message else e.cause!!.message

                else -> e.message
            }
            throw ValueInstantiationException.from(jsonParser, message)
        }
        val postConstructValidationErrors =
            executePostConstructValidations(
                jsonParser = jsonParser,
                validator = validator,
                config = config,
                obj = obj
            )
        if (postConstructValidationErrors.isNotEmpty()) throw DataClassMappingException(postConstructValidationErrors.toSet())

        return obj
    }

    /* in order to deal with parameterized types we create a JavaType here and carry it */
    private fun getBeanPropertyDefinitions(
        constructionParameters: List<KParameter>?,
        parameters: Array<Parameter>,
        annotatedWithParams: AnnotatedWithParams
    ): Array<PropertyDefinition> {
        if (constructionParameters == null) {
            throw InvalidDefinitionException.from(
                EmptyJsonParser, "Unable to locate suitable constructor for class: ${clazz.name}", javaType
            )
        }
        val valueParameters = constructionParameters.filter { it.kind == KParameter.Kind.VALUE }

        val propertyDefinitions = mutableListOf<PropertyDefinition>()
        for ((index, parameter) in parameters.withIndex()) {
            val constructorParamDescriptor = valueParameters[index]
            val kotlinType: KType = constructorParamDescriptor.type

            val parameterJavaType =
                if (javaType.bindings.size() != 0 && shouldFullyDefineParameterizedType(kotlinType, parameter)) {
                    // what types are bound to the generic data class parameters
                    val boundTypeParameters: List<JavaType> =
                        Types.parameterizedTypeNames(parameter.getParameterizedType())
                            .map { javaType.bindings.findBoundType(it) }.filterNot { it == null }
                    Types.javaType(
                        typeFactory = config.typeFactory,
                        clazz = kotlinType.jvmErasure.java,
                        typeParameters = boundTypeParameters
                    )
                } else if (isParameterized(kotlinType.javaType)) {
                    val parameterTypes = kotlinType.arguments.map { it.type!!.jvmErasure.java }.toTypedArray()
                    Types.javaType(
                        typeFactory = config.typeFactory,
                        clazz = kotlinType.jvmErasure.java,
                        typeParameters = Types.javaTypes(config.typeFactory, parameterTypes)
                    )
                } else if (kotlinType.jvmErasure.java.isArray) {
                    val parameterTypes = kotlinType.arguments.map { it.type!!.jvmErasure.java }.toTypedArray()
                    val typeParameters = Types.javaTypes(config.typeFactory, parameterTypes)
                    ArrayType.construct(
                        Types.javaType(config.typeFactory, kotlinType.jvmErasure.java, typeParameters),
                        TypeBindings.create(java.util.ArrayList::class.java, typeParameters.first())
                    )
                } else {
                    Types.javaType(config.typeFactory, kotlinType.jvmErasure.java)
                }

            val annotatedParameter = newAnnotatedParameter(
                typeResolutionContext = TypeResolutionContext.Basic(
                    config.typeFactory, javaType.bindings
                ), // use the TypeBindings from the top-level JavaType, not the parameter JavaType
                owner = annotatedWithParams,
                annotations = annotatedWithParams.getParameterAnnotations(index),
                javaType = parameterJavaType,
                index = index
            )

            propertyDefinitions.add(
                PropertyDefinition(
                    parameterJavaType,
                    SimpleBeanPropertyDefinition.construct(
                        config,
                        annotatedParameter,
                        PropertyName(constructorParamDescriptor.name)
                    )
                )
            )
        }
        return Array(propertyDefinitions.size) { propertyDefinitions[it] }
    }

    companion object {
        val EmptyJsonParser: JsonParser = JsonFactory().createParser("")

        /* For supporting JsonCreator */
        data class DataClassCreator(
            val kotlinFunction: KFunction<*>?,
            val parameters: List<KParameter>,
            val companion: Any?,
            val executable: Executable,
            val javaType: JavaType,
            val propertyDefinitions: Array<PropertyDefinition>,
            val beanDescriptor: BeanDescriptor?
        ) {
            init {
                if (kotlinFunction == null) {
                    throw InvalidDefinitionException.from(
                        TreeTraversingParser(NullNode.instance),
                        "Unable to properly determine method of instantiation for class ${javaType}.",
                        javaType
                    )
                }
            }

            val hasInstanceParameter: Boolean = parameters.any { it.kind == KParameter.Kind.INSTANCE }

            val getInstanceParameter: Pair<KParameter, Any?>? by lazy {
                val instanceParameter = parameters.find { it.kind == KParameter.Kind.INSTANCE }
                when (instanceParameter) {
                    null -> null
                    else ->
                        Pair(instanceParameter, companion)
                }
            }

            val parameterTypes: Array<Class<*>> by lazy {
                when (executable) {
                    is Method -> executable.parameterTypes

                    is Constructor<*> -> executable.parameterTypes
                }
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as DataClassCreator

                if (executable != other.executable) return false
                if (!propertyDefinitions.contentEquals(other.propertyDefinitions)) return false
                if (beanDescriptor != other.beanDescriptor) return false

                return true
            }

            override fun hashCode(): Int {
                var result = executable.hashCode()
                result = 31 * result + propertyDefinitions.contentHashCode()
                result = 31 * result + (beanDescriptor?.hashCode() ?: 0)
                return result
            }
        }

        data class PropertyDefinition(
            val type: JavaType, val beanPropertyDefinition: BeanPropertyDefinition
        )

        private fun applyPropertyNamingStrategy(
            config: DeserializationConfig,
            fieldName: String
        ): String = config.propertyNamingStrategy.nameForField(config, null, fieldName)

        private fun executeFieldValidations(
            validator: Validator?,
            config: DeserializationConfig,
            kotlinFunction: KFunction<*>?,
            mixinClazz: Class<*>?,
            fieldNames: List<String>,
            parameterValues: List<Any?>,
            instance: Any?
        ): List<DataClassFieldMappingException> {
            val results = mutableListOf<DataClassFieldMappingException>()
            if (validator != null && kotlinFunction != null) {
                val violations = try {
                    val dataClassValidator = validator.unwrap(DataClassValidator::class.java)
                    val descriptor = dataClassValidator.getConstraintsForKotlinFunction(kotlinFunction as KFunction<Any>, mixinClazz = mixinClazz)
                    if (kotlinFunction.javaConstructor != null) {
                        dataClassValidator.validateConstructorParameters(
                            constructor = kotlinFunction.javaConstructor!!,
                            constructorDescriptor = descriptor,
                            fieldNames = fieldNames,
                            parameterValues = parameterValues.toTypedArray()
                        )
                    } else if (kotlinFunction.javaMethod != null) {
                        dataClassValidator.validateParameters(
                            method = kotlinFunction.javaMethod!!,
                            methodDescriptor = descriptor,
                            fieldNames = fieldNames,
                            parameterValues = parameterValues.toTypedArray()
                        )
                    } else emptySet()
                } catch (e: ValidationException) {
                    // DataClassValidator not supported, continue with given validator
                    if (kotlinFunction.javaConstructor != null) {
                        validator.forExecutables().validateConstructorParameters(
                            /* constructor      = */ kotlinFunction.javaConstructor!!,
                            /* parameterValues  = */ parameterValues.toTypedArray()
                        )
                    } else if (kotlinFunction.javaMethod != null) {
                        validator.forExecutables().validateParameters(
                            /* object           = */ instance,
                            /* method           = */ kotlinFunction.javaMethod!!,
                            /* parameterValues  = */ parameterValues.toTypedArray()
                        )
                    } else emptySet()
                }

                violations.map { violation ->
                    results.add(
                        DataClassFieldMappingException(
                            DataClassFieldMappingException.PropertyPath.leaf(
                                applyPropertyNamingStrategy(config, violation.propertyPath.getLeafNode().toString())
                            ), DataClassFieldMappingException.Reason(
                                message = violation.message,
                                detail = DataClassFieldMappingException.ValidationError(
                                    violation,
                                    DataClassFieldMappingException.ValidationError.Location.Field,
                                    violation.getDynamicPayload(Payload::class.java)
                                )
                            )
                        )
                    )
                }
            }
            return results.toList()
        }

        // We do not want to "leak" method names, so we only want to return the leaf node
        // if the ConstraintViolation PropertyPath size is greater than 1 element.
        private fun getPostConstructValidationViolationPropertyPath(
            config: DeserializationConfig, violation: ConstraintViolation<*>
        ): DataClassFieldMappingException.PropertyPath {
            val propertyPath = violation.propertyPath
            val iterator = propertyPath.iterator()
            return if (iterator.hasNext()) {
                iterator.next() // move the iterator
                if (iterator.hasNext()) { // has another element, return the leaf
                    DataClassFieldMappingException.PropertyPath.leaf(
                        applyPropertyNamingStrategy(config, propertyPath.getLeafNode().toString())
                    )
                } else { // does not have another element, return empty
                    DataClassFieldMappingException.PropertyPath.Empty
                }
            } else {
                DataClassFieldMappingException.PropertyPath.Empty
            }
        }

        // Validate all methods annotated with `@PostConstructValidation` defined in the deserialized data class.
        // This is called after the data class is created.
        internal fun executePostConstructValidations(
            jsonParser: JsonParser, validator: Validator?, config: DeserializationConfig, obj: Any?
        ): List<DataClassFieldMappingException> {
            return if (validator != null && obj != null) {
                try {
                    val dataClassValidator = validator.unwrap(DataClassValidator::class.java)
                    dataClassValidator.validatePostConstructValidationMethods(obj).map { violation ->
                        DataClassFieldMappingException(
                            getPostConstructValidationViolationPropertyPath(config, violation),
                            DataClassFieldMappingException.Reason(
                                message = violation.message, detail = DataClassFieldMappingException.ValidationError(
                                    violation,
                                    DataClassFieldMappingException.ValidationError.Location.Method,
                                    violation.getDynamicPayload(Payload::class.java)
                                )
                            )
                        )
                    }
                } catch (e: ValidationException) {
                    // PostConstructValidation is not supported
                    emptyList()
                } catch (e: InvocationTargetException) {
                    val message = if (e.cause != null) e.cause!!.message else e.message
                    throw ValueInstantiationException.from(jsonParser, message)
                }
            } else emptyList()
        }

        private fun isParameterized(reflectionType: Type): Boolean = when (reflectionType) {
            is ParameterizedType -> true
            is TypeVariable<*> -> true
            else -> false
        }

        // if we need to attempt to fully specify the JavaType because it is generically types
        private fun shouldFullyDefineParameterizedType(
            kotlinType: KType, parameter: Parameter
        ): Boolean {
            // only need to fully specify if the type is parameterized, and it has more than one type arg
            // or its typeArg is also parameterized.
            val parameterizedType = parameter.getParameterizedType()

            return !kotlinType.javaClass.isPrimitive && hasAnyTypeParameter(kotlinType) && parameterizedType != parameter.getType() && isParameterized(
                parameterizedType
            )
        }

        private fun hasAnyTypeParameter(kotlinType: KType): Boolean =
            kotlinType.jvmErasure.typeParameters.isEmpty() || kotlinType.jvmErasure.typeParameters.first().starProjectedType.jvmErasure.java.isAssignableFrom(
                Any::class.java
            )

        private val requiredFieldExceptionRegex = """parameter #([0-9]+)""".toRegex()
        private val requiredFieldExceptionSecondPassRegex = """([0-9]+)""".toRegex()
        private const val REQUIRED_FIELD_EXCEPTION_TEXT = "No argument provided for a required parameter: parameter #"
        private fun handleFieldRequired(fields: Array<DataClassField>, e: Throwable) {
            val message: String = e.message ?: ""
            if (message.startsWith(REQUIRED_FIELD_EXCEPTION_TEXT)) {
                val firstPass = requiredFieldExceptionRegex.find(message)!!.groupValues.first()
                val index = requiredFieldExceptionSecondPassRegex.find(firstPass)!!.groupValues.first().toInt()
                throwRequiredFieldException(fields[index], required = true)
            } else throw e
        }

        private fun throwRequiredFieldException(field: DataClassField, required: Boolean = false) {
            val (fieldInfoAttributeType, fieldInfoAttributeName) = field.getFieldInfo(
                field.name, field.annotations.toList()
            )
            when (field.isIgnored) {
                true -> when (required) {
                    true -> throw DataClassFieldMappingException(
                        DataClassFieldMappingException.PropertyPath.leaf(fieldInfoAttributeName),
                        DataClassFieldMappingException.Reason(
                            "ignored $fieldInfoAttributeType has no default value specified",
                            DataClassFieldMappingException.Detail.RequiredFieldMissing
                        )
                    )

                    false -> Unit // do nothing
                }

                false -> throw DataClassFieldMappingException(
                    DataClassFieldMappingException.PropertyPath.leaf(fieldInfoAttributeName),
                    DataClassFieldMappingException.Reason(
                        "$fieldInfoAttributeType is required", DataClassFieldMappingException.Detail.RequiredFieldMissing
                    )
                )
            }
        }

        private fun validValuesString(e: MismatchedInputException): String =
            if (e.targetType != null && e.targetType.isEnum) {
                " with valid values: " + e.targetType.enumConstants.joinToString(", ")
            } else {
                ""
            }
    }
}
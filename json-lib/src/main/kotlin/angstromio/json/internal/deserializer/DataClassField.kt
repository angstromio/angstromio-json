package angstromio.json.internal.deserializer

import angstromio.json.internal.Classes
import angstromio.json.internal.Types
import angstromio.json.internal.deserializer.DataClassBeanProperty.Companion.newBeanProperty
import angstromio.util.extensions.Annotations.merge
import angstromio.util.extensions.KClasses.getConstructor
import angstromio.util.extensions.Lists.head
import angstromio.util.extensions.Lists.tail
import angstromio.util.extensions.Strings.toCamelCase
import angstromio.util.reflect.Annotations
import com.fasterxml.jackson.annotation.JacksonInject
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreType
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonView
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.ObjectCodec
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationContextAccessor
import com.fasterxml.jackson.databind.InjectableValues
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TreeTraversingParser
import com.fasterxml.jackson.databind.util.ClassUtil
import java.lang.reflect.Executable
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.isSubclassOf

internal class DataClassField(
    val parameter: KParameter,
    val name: String, // note: this may be different from the parameter name due to usage of a @JsonProperty, or @JsonAlias
    val javaType: JavaType,
    val isMarkedNullable: Boolean,
    val annotations: Array<Annotation>,
    val beanPropertyDefinition: BeanPropertyDefinition
) {
    val isOptional = parameter.isOptional
    val isIgnored: Boolean by lazy {
        // lazy as it uses a field defined further down
        val jsonIgnoreAnnotation = Annotations.findAnnotation<JsonIgnore>(annotations)
        val jsonIgnoreTypeAnnotation = Annotations.findAnnotation<JsonIgnoreType>(clazzAnnotations)
        jsonIgnoreAnnotation != null || jsonIgnoreTypeAnnotation != null
    }
    private val index = parameter.index
    private val isString = javaType.hasRawClass(String::class.java)
    private val clazzAnnotations: List<Annotation> = javaType.rawClass.kotlin.annotations
    private val jsonDeserializer: JsonDeserialize? =
        Annotations
            .findAnnotation<JsonDeserialize>(annotations) ?: Annotations
                .findAnnotation<JsonDeserialize>(clazzAnnotations)

    val missingValue: Any? by lazy {
        if (javaType.isPrimitive) {
            ClassUtil.defaultValue(javaType.rawClass)
        } else {
            null
        }
    }

    tailrec fun getFieldInfo(
        fieldName: String,
        annotations: List<Annotation>?
    ): Pair<String, String> {
        return if (annotations.isNullOrEmpty()) {
            Pair("field", fieldName)
        } else {
            extractFieldInfo(fieldName, annotations.head()) ?: getFieldInfo(fieldName, annotations.tail())
        }
    }

    fun parse(
        context: DeserializationContext,
        codec: ObjectCodec,
        objectJsonNode: JsonNode
    ): Any? {
        val injectableValues: InjectableValues? = DeserializationContextAccessor(context).injectableValues()
        val forProperty: DataClassBeanProperty = beanProperty(context)

        return when {
            injectableValues != null -> {
                try {
                    val injectableValue = context.findInjectableValue(
                        /* valueId = */ forProperty.valueObj?.id,
                        /* forProperty = */ forProperty.property,
                        /* beanInstance = */ null
                    )
                    injectableValue ?: Types.Null // if a null is explicitly emitted as the injectable value, convert to a Types.Null
                } catch (e: IllegalArgumentException) {
                    findValue(context, codec, objectJsonNode, forProperty.property)
                }
            }

            else ->
                findValue(context, codec, objectJsonNode, forProperty.property)
        }
    }

    private fun findValue(
        context: DeserializationContext,
        codec: ObjectCodec,
        objectJsonNode: JsonNode,
        beanProperty: BeanProperty
    ): Any? {
        // current active view (if any)
        val activeJsonView: Class<*>? = context.activeView
        // current @JsonView annotation (if any)
        val found = Annotations.findAnnotation<JsonView>(annotations)
        val fieldJsonViews: List<Class<out Any>> = found?.value?.toList()?.map { it.java } ?: emptyList<Class<Any>>()
        // context has an active view *and* the field is annotated
        return when {
            activeJsonView != null && fieldJsonViews.isNotEmpty() ->
                if (fieldJsonViews.contains(activeJsonView)) {
                    // active view is in the list of views from the annotation
                    parse(context, codec, objectJsonNode, beanProperty)
                } else {
                    getNullable(explicitNull = objectJsonNode::class.isSubclassOf(NullNode::class))
                }

            else -> {
                // no active view proceed as normal
                parse(context, codec, objectJsonNode, beanProperty)
            }
        }
    }

    private fun beanProperty(
        context: DeserializationContext,
        optionalJavaType: JavaType? = null
    ): DataClassBeanProperty =
        newBeanProperty(
            context = context,
            javaType = javaType,
            optionalJavaType = optionalJavaType,
            annotatedParameter = beanPropertyDefinition.constructorParameter,
            annotations = annotations.toList(),
            name = name,
            index = index
        )

    private fun parse(
        context: DeserializationContext,
        codec: ObjectCodec,
        objectJsonNode: JsonNode,
        forProperty: BeanProperty
    ): Any? {
        val fieldJsonNode = objectJsonNode.get(name)
        return if (!isIgnored && fieldJsonNode != null) {
            // not ignored and a value was passed in the JSON
            val resolved = resolveWithDeserializerAnnotation(context, codec, fieldJsonNode, forProperty)
            when {
                resolved != null -> {
                    resolved
                }

                else -> {
                    // wasn't handled by another resolved deserializer
                    parseFieldValue(context, codec, fieldJsonNode, forProperty, null)
                }
            }
        } else getNullable(explicitNull = fieldJsonNode != null)
    }

    private fun resolveWithDeserializerAnnotation(
        context: DeserializationContext,
        fieldCodec: ObjectCodec,
        fieldJsonNode: JsonNode,
        forProperty: BeanProperty
    ): Any? {
        return if (jsonDeserializer == null) null
        else {
            val annotationClazz by lazy { jsonDeserializer::class.java }
            val usingValue by lazy { jsonDeserializer.using }
            val contentAsValue by lazy { jsonDeserializer.contentAs }
            if (usingValue.java != JsonDeserializer.None::class.java &&
                Classes.isAssignableFrom(usingValue.java, JsonDeserializer::class.java)) {
                // specifies a deserializer to use. we don't want to run any processing of the field
                // node value here as the field is specified to be deserialized by some other deserializer
                when (val deserializer =
                    context.deserializerInstance(beanPropertyDefinition.primaryMember, usingValue.java)) {
                    null ->
                        context.handleInstantiationProblem(
                            javaType.rawClass,
                            usingValue.java.toString(),
                            JsonMappingException.from(
                                context,
                                "Unable to locate/create deserializer specified by: ${annotationClazz.name}(using = $usingValue)"
                            )
                        )

                    else -> {
                        val treeTraversingParser = TreeTraversingParser(fieldJsonNode, fieldCodec)
                        treeTraversingParser.use {
                            // advance the parser to the next token for deserialization
                            it.nextToken()
                            deserializer.deserialize(it, context)
                        }
                    }
                }
            } else if (Classes.isNotAssignableFrom(contentAsValue.java, Void::class.java)) {
                // there is a @JsonDeserialize annotation, but it merely states to deserialize as a specific type
                parseFieldValue(
                    context,
                    fieldCodec,
                    fieldJsonNode,
                    forProperty,
                    contentAsValue.java
                )
            } else {
                context.handleInstantiationProblem(
                    javaType.rawClass,
                    usingValue.java.toString(),
                    JsonMappingException.from(
                        context,
                        "Unable to locate/create deserializer specified by: ${annotationClazz.name}(using = $usingValue)"
                    )
                )
            }
        }
    }

    private fun parseFieldValue(
        context: DeserializationContext,
        fieldCodec: ObjectCodec,
        fieldJsonNode: JsonNode,
        forProperty: BeanProperty,
        subTypeClazz: Class<*>?
    ): Any? {
        return if (fieldJsonNode.isNull) {
            // the passed JSON value is a 'null' value
            getNullable()
        } else if (isString) {
            // if this is a String type, do not try to use a JsonParser and simply return the node as text
            if (fieldJsonNode.isValueNode) fieldJsonNode.asText()
            else fieldJsonNode.toString()
        } else {
            val treeTraversingParser = TreeTraversingParser(fieldJsonNode, fieldCodec)
            treeTraversingParser.use {
                // advance the parser to the next token for deserialization
                it.nextToken()
                assertNotNull(
                    context,
                    fieldJsonNode,
                    parseFieldValue(context, fieldCodec, it, forProperty, subTypeClazz)
                )
            }
        }
    }

    private fun parseFieldValue(
        context: DeserializationContext,
        fieldCodec: ObjectCodec,
        jsonParser: JsonParser,
        forProperty: BeanProperty,
        subTypeClazz: Class<*>?
    ): Any? {
        val resolvedType = resolveSubType(context, forProperty.type, subTypeClazz)
        val ann = Annotations.findAnnotation<JsonTypeInfo>(clazzAnnotations)
        return when {
            ann != null ->
                // for polymorphic types we cannot contextualize
                // thus we go back to the field codec to read
                fieldCodec.readValue(jsonParser, resolvedType)

            else ->
                if (resolvedType.isContainerType) {
                    // nor container types -- trying to contextualize on a container type leads to poor performance
                    // thus we go back to the field codec to read
                    fieldCodec.readValue(jsonParser, resolvedType)
                } else {
                    // contextualization for all others
                    context.readPropertyValue(jsonParser, forProperty, resolvedType)
                }
        }
    }

    private fun getNullable(explicitNull: Boolean = true): Any? {
        return if (isMarkedNullable && explicitNull) {
            Types.Null
        } else {
            null
        }
    }

    // Annotations annotated with `@JacksonInject` represent values that are injected
    // into a constructed data class from somewhere other than the incoming JSON,
    // e.g., with Jackson InjectableValues. When we are parsing the structure of the
    // data class, we want to capture information about these annotations for proper
    // error reporting on the fields annotated. The annotation name is used instead
    // of the generic classifier of "field", and the annotation#value() is used
    // instead of the data class field name being marshalled into.
    private fun extractFieldInfo(
        fieldName: String,
        annotation: Annotation?
    ): Pair<String, String>? {
        return if (annotation != null && Annotations.isAnnotationPresent<JacksonInject>(annotation)) {
            val name: String =
                Annotations.getValueIfAnnotatedWith<JacksonInject>(annotation) ?: fieldName
            Pair(annotation.annotationClass.simpleName.toCamelCase()!!, name)
        } else null
    }

    companion object {
        fun createFields(
            kClazz: KClass<*>,
            constructor: Executable,
            propertyDefinitions: Array<DataClassDeserializer.Companion.PropertyDefinition>,
            fieldAnnotations: Map<String, Array<Annotation>>,
            namingStrategy: PropertyNamingStrategy?
        ): Array<DataClassField> {
            /* DataClassFields MUST be returned in constructor/method parameter invocation order */
            // we could choose to use the size of the property definitions here, but since they are
            // computed there is the possibility it is incorrect, and thus we want to ensure we have
            // as many definitions as there are parameters defined for the given constructor.
            val kFunctionParameters = kClazz
                .getConstructor(constructor.parameterTypes.map { it.kotlin })!!
                .parameters.filter { it.kind == KParameter.Kind.VALUE }
            return Array(kFunctionParameters.size) { index ->
                val kParameter = kFunctionParameters[index]
                val propertyDefinition = propertyDefinitions[index]
                // we look up annotations by the field name as that is how they are keyed
                if (propertyDefinition.beanPropertyDefinition.name.isEmpty())
                    throw InvalidDefinitionException.from(
                        TreeTraversingParser(NullNode.instance),
                        "Unable to properly determine JSON property name for clazz ${kClazz.qualifiedName} with PropertyNamingStrategy $namingStrategy with parameter name ${kParameter.name}",
                        propertyDefinition.beanPropertyDefinition.primaryType
                    )

                val annotations: Array<Annotation> =
                    when (val foundAnnotations = fieldAnnotations[propertyDefinition.beanPropertyDefinition.name]) {
                        null ->
                            kParameter.annotations.toTypedArray()
                        else ->
                            kParameter.annotations.toTypedArray().merge(foundAnnotations)
                    }

                DataClassField(
                    parameter = kParameter,
                    name = jsonNameForField(
                        namingStrategy,
                        annotations,
                        propertyDefinition.beanPropertyDefinition.name
                    ),
                    javaType = propertyDefinition.type,
                    isMarkedNullable = kParameter.type.isMarkedNullable,
                    annotations = annotations,
                    beanPropertyDefinition = propertyDefinition.beanPropertyDefinition
                )
            }
        }

        private fun jsonNameForField(
            namingStrategy: PropertyNamingStrategy?,
            annotations: Array<Annotation>,
            name: String
        ): String {
            val jsonProperty = Annotations.findAnnotation<JsonProperty>(annotations)
            return when {
                jsonProperty != null && jsonProperty.value.isNotEmpty() ->
                    jsonProperty.value

                else -> {
                    val decodedName = decode(name)
                    // apply json naming strategy (e.g. snake_case)
                    namingStrategy?.nameForField(
                        /* config = */ null,
                        /* field = */ null,
                        /* defaultName = */ decodedName
                    ) ?: name
                }
            }
        }

        private fun assertNotNull(
            context: DeserializationContext,
            field: JsonNode,
            value: Any?
        ): Any {
            return if (value == null) {
                throw JsonMappingException.from(context, "error parsing '" + field.asText() + "'")
            } else {
                when (value) {
                    is Iterable<*> ->
                        assertNotNull(context, value)

                    is Array<*> ->
                        assertNotNull(context, value.toList())
                }
                value
            }
        }

        private fun assertNotNull(
            context: DeserializationContext,
            iterable: Iterable<*>
        ) {
            if (iterable.any { it == null }) {
                throw JsonMappingException.from(
                    context,
                    "Literal null values are not allowed as json array elements."
                )
            }
        }

        // decode unicode escaped field names
        private fun decode(s: String): String =
            s.toByteArray(Charsets.ISO_8859_1).decodeToString()

        private fun resolveSubType(
            context: DeserializationContext,
            baseType: JavaType,
            subClazz: Class<*>?
        ): JavaType = when {
            subClazz != null && Classes.isNotAssignableFrom(subClazz, baseType.rawClass) ->
                context.resolveSubType(baseType, subClazz.name)

            else -> baseType
        }
    }
}
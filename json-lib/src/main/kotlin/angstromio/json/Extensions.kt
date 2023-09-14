package angstromio.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectMapperCopier
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactoryBuilder
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import java.io.OutputStream

private val ArrayElementsOnNewLinesPrettyPrinter: DefaultPrettyPrinter =
    DefaultPrettyPrinter().withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)

fun beanDeserializerModule(initializer: BeanDeserializerModule.Builder.() -> Unit = {}): BeanDeserializerModule {
    val builder = BeanDeserializerModule.Builder()
    builder.initializer()
    return builder.build()
}

fun yamlObjectMapper(): ObjectMapper = JsonMapper(YAMLFactoryBuilder(YAMLFactory()).build())

fun yamlObjectMapper(underlying: ObjectMapper): ObjectMapper =
    underlying.makeCopy(YAMLFactoryBuilder(YAMLFactory()).build())

fun ObjectMapper.registerBeanDeserializerModule(): ObjectMapper = this.registerModule(beanDeserializerModule())

/**
 * An [ObjectMapper] with sensible defaults.
 * Property Naming Strategy:
 *  - [PropertyNamingStrategies.SNAKE_CASE]
 * Default Property Inclusion:
 *  - [JsonInclude.Include.NON_ABSENT]
 * Modules:
 *  - jackson-module-kotlin
 *  - angstromio bean deserializer module
 *  - JSR310 java time module
 * Serialization config:
 *  - disable [SerializationFeature.WRITE_DATES_AS_TIMESTAMPS]
 *  - disable [SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS]
 *  - enable  [SerializationFeature.WRITE_ENUMS_USING_TO_STRING]
 * Deserialization config:
 *  - disable [DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES]
 *  - enable  [DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES]
 *  - enable  [DeserializationFeature.READ_ENUMS_USING_TO_STRING]
 *  - enable  [DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY]
 * Mapper features:
 *  - enable  [MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES]
 *  [BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES](https://cowtowncoder.medium.com/on-jackson-cves-dont-panic-here-is-what-you-need-to-know-54cd0d6e8062)
 */
fun ObjectMapper.defaultMapper(enableValidation: Boolean = true): ObjectMapper {
    val m = jsonMapper {
        // modules
        addModule(kotlinModule())
        addModule(beanDeserializerModule { enableValidation(enableValidation) })
        addModule(JavaTimeModule())
        // serialization
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
        // deserialization
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING)
        enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        // mapper feature
        // Block use of a set of "unsafe" base types such as java.lang.Object
        // to prevent exploitation of Remote Code Execution (RCE) vulnerability
        // This line can be removed when this feature is enabled by default in Jackson 3
        enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES)
    }

    // property inclusion
    m.setDefaultPropertyInclusion(
        JsonInclude.Value.construct(
            JsonInclude.Include.NON_ABSENT,
            JsonInclude.Include.NON_ABSENT
        )
    )
    // property naming strategy
    m.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    return m
}

fun ObjectMapper.makeCopy(factory: JsonFactory): ObjectMapper =
    ObjectMapperCopier.makeCopy(this, factory)

fun ObjectMapper.makeCopy(): ObjectMapper =
    ObjectMapperCopier.makeCopy(this)

fun ObjectMapper.writeValue(any: Any, outputStream: OutputStream) =
    this.writeValue(outputStream, any)

fun ObjectMapper.writeValueAsString(value: Any, prettyPrint: Boolean): String {
    val prettyObjectWriter = this.writer(ArrayElementsOnNewLinesPrettyPrinter)
    return when {
        prettyPrint -> {
            if (String::class.java.isAssignableFrom(value.javaClass)) {
                val jsonNode = this.readValue(value as String, JsonNode::class.java)
                prettyObjectWriter.writeValueAsString(jsonNode)
            } else prettyObjectWriter.writeValueAsString(value)
        }

        else -> this.writeValueAsString(value)
    }
}

fun ObjectMapper.toYamlObjectMapper(): ObjectMapper {
    return when (this.factory) {
        is YAMLFactory -> { // correct
            this.makeCopy()
        }

        else -> // incorrect
            throw IllegalArgumentException("This mapper is not properly configured with a YAMLFactory")
    }
}

fun ObjectMapper.toCamelCaseObjectMapper(): ObjectMapper {
    val objectMapperCopy = this.makeCopy()
    objectMapperCopy.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
    return objectMapperCopy
}

fun ObjectMapper.toSnakeCaseMapper(): ObjectMapper {
    val objectMapperCopy = this.makeCopy()
    objectMapperCopy.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    return objectMapperCopy
}

inline fun <reified T : Any> ObjectMapper.readerFor(): ObjectReader =
    this.readerFor(jacksonTypeRef<T>())

inline fun <reified T> ObjectMapper.readValue(jsonNode: JsonNode): T = this.convertValue<T>(jsonNode)
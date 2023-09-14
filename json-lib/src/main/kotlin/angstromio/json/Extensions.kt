package angstromio.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectMapperCopier
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactoryBuilder
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
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
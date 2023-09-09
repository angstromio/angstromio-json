package angstromio.json

import angstromio.validation.DataClassValidator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TSFBuilder
import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.copyFn
import com.fasterxml.jackson.databind.copyWithFn
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactoryBuilder
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.DataInput
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.net.URL
import kotlin.reflect.KClass

class KotlinObjectMapper private constructor(val underlying: ObjectMapper) {

    private val prettyObjectWriter: ObjectWriter by lazy {
        underlying.writer(ArrayElementsOnNewLinesPrettyPrinter)
    }

    inline fun <reified T: Any> reader(): ObjectReader = underlying.readerFor(T::class.java)

    inline fun <reified T: Any> parse(p: JsonParser): T = underlying.readValue(p, T::class.java)

    fun <T: Any> parse(p: JsonParser, clazz: KClass<T>): T = underlying.readValue(p, clazz.java)

    inline fun <reified T: Any> parse(f: File): T = underlying.readValue(f, T::class.java)

    fun <T: Any> parse(f: File, clazz: KClass<T>): T = underlying.readValue(f, clazz.java)

    inline fun <reified T: Any> parse(u: URL): T = underlying.readValue(u, T::class.java)

    fun <T: Any> parse(u: URL, clazz: KClass<T>): T = underlying.readValue(u, clazz.java)

    inline fun <reified T: Any> parse(s: String): T = underlying.readValue(s, T::class.java)

    fun <T: Any> parse(s: String, clazz: KClass<T>): T = underlying.readValue(s, clazz.java)

    inline fun <reified T: Any> parse(r: Reader): T = underlying.readValue(r, T::class.java)

    fun <T: Any> parse(r: Reader, clazz: KClass<T>): T = underlying.readValue(r, clazz.java)

    inline fun <reified T: Any> parse(i: InputStream): T = underlying.readValue(i, T::class.java)

    fun <T: Any> parse(i: InputStream, clazz: KClass<T>): T = underlying.readValue(i, clazz.java)

    inline fun <reified T: Any> parse(b: Array<Byte>): T = underlying.readValue(b.toByteArray(), T::class.java)

    fun <T: Any> parse(b: Array<Byte>, clazz: KClass<T>): T = underlying.readValue(b.toByteArray(), clazz.java)

    inline fun <reified T: Any> parse(d: DataInput): T = underlying.readValue(d, T::class.java)

    fun <T: Any> parse(d: DataInput, clazz: KClass<T>): T = underlying.readValue(d, clazz.java)

    /** toValueType should be a T */
    inline fun <reified T: Any> convert(from: Any, toValueType: JavaType): Any {
        return try {
            underlying.convertValue<T>(from, toValueType)
        } catch (e: IllegalArgumentException) {
            throw handleIllegalArgumentException(e)
        }
    }

    inline fun <reified T: Any> convert(from: Any): T {
        return try {
            underlying.convertValue<T>(from)
        } catch (e: IllegalArgumentException) {
            throw handleIllegalArgumentException(e)
        }
    }

    fun writeValue(any: Any, outputStream: OutputStream) = underlying.writeValue(outputStream, any)

    fun writeValueAsBytes(any: Any): Array<Byte> = underlying.writeValueAsBytes(any).toTypedArray()

    fun writeValueAsString(any: Any, prettyPrint: Boolean = false): String =
        when {
            prettyPrint -> {
                if (String::class.java.isAssignableFrom(any.javaClass)) {
                    val jsonNode = underlying.readValue(any as String, JsonNode::class.java)
                    prettyObjectWriter.writeValueAsString(jsonNode)
                } else prettyObjectWriter.writeValueAsString(any)
            }
            else ->
                underlying.writeValueAsString(any)
        }

    data class Builder(
        val propertyNamingStrategy: PropertyNamingStrategy = DEFAULT_PROPERTY_NAMING_STRATEGY,
        val numbersAsStrings: Boolean = DEFAULT_NUMBERS_AS_STRINGS,
        val serializationInclude: JsonInclude.Include = DEFAULT_SERIALIZATION_INCLUDE,
        val serializationConfig: Map<SerializationFeature, Boolean> = DEFAULT_SERIALIZATION_CONFIG,
        val deserializationConfig: Map<DeserializationFeature, Boolean> = DEFAULT_DESERIALIZATION_CONFIG,
        val defaultJacksonModules: List<Module> = DEFAULT_JACKSON_MODULES,
        val validator: DataClassValidator? = DEFAULT_VALIDATOR,
        val additionalJacksonModules: List<Module> = DEFAULT_ADDITIONAL_JACKSON_MODULES,
        val additionalMapperConfigurationFns: List<(ObjectMapper) -> Unit> = emptyList(),
        val validation: Boolean = DEFAULT_ENABLE_VALIDATION
    ) {

        /* Public */

        /** Create a new [KotlinObjectMapper] from this [Builder]. */
        fun objectMapper(): KotlinObjectMapper = KotlinObjectMapper(jacksonKotlinObjectMapper())

        /** Create a new [KotlinObjectMapper] from this [Builder] using the given [[JsonFactory]]. */
        fun <F: JsonFactory> objectMapper(factory: F): KotlinObjectMapper =
            KotlinObjectMapper(jacksonKotlinObjectMapper(factory))

        /**
         * Create a new [KotlinObjectMapper] explicitly configured to serialize and deserialize
         * YAML from this [Builder].
         *
         * @note the used [[PropertyNamingStrategy]] is defined by the current [Builder] configuration.
         */
        fun yamlObjectMapper(): KotlinObjectMapper =
            KotlinObjectMapper(configureJacksonKotlinObjectMapper(YAMLFactoryBuilder(YAMLFactory())))

        /**
         * Creates a new [KotlinObjectMapper] explicitly configured with
         * [[PropertyNamingStrategies.LOWER_CAMEL_CASE]] as a `PropertyNamingStrategy`.
         */
        fun camelCaseObjectMapper(): KotlinObjectMapper =
            camelCaseObjectMapper(jacksonKotlinObjectMapper())

        /**
         * Creates a new [KotlinObjectMapper] explicitly configured with
         * [[PropertyNamingStrategies.SNAKE_CASE]] as a `PropertyNamingStrategy`.
         */
        fun snakeCaseObjectMapper(): KotlinObjectMapper =
            snakeCaseObjectMapper(jacksonKotlinObjectMapper())


        /* Builder Methods */

        /**
         * Configure a [PropertyNamingStrategy] for this [Builder].
         * @note the default is [PropertyNamingStrategies.SNAKE_CASE]
         * @see [KotlinObjectMapper.DEFAULT_PROPERTY_NAMING_STRATEGY]
         */
        fun withPropertyNamingStrategy(propertyNamingStrategy: PropertyNamingStrategy): Builder =
            this.copy(propertyNamingStrategy = propertyNamingStrategy)

        /**
         * Enable the [JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS] for this [Builder].
         * @note the default is false.
         */
        fun withNumbersAsStrings(numbersAsStrings: Boolean): Builder =
            this.copy(numbersAsStrings = numbersAsStrings)

        /**
         * Configure a [JsonInclude.Include] for serialization for this [Builder].
         * @note the default is [JsonInclude.Include.NON_ABSENT]
         * @see [KotlinObjectMapper.DEFAULT_SERIALIZATION_INCLUDE]
         */
        fun withSerializationInclude(serializationInclude: JsonInclude.Include): Builder =
            this.copy(serializationInclude = serializationInclude)

        /**
         * Set the serialization configuration for this [Builder] as a `Map` of `SerializationFeature`
         * to `Boolean` (enabled).
         * @note the default is described by [KotlinObjectMapper.DEFAULT_SERIALIZATION_CONFIG].
         * @see [KotlinObjectMapper.DEFAULT_SERIALIZATION_CONFIG]
         */
        fun withSerializationConfig(serializationConfig: Map<SerializationFeature, Boolean>): Builder =
            this.copy(serializationConfig = serializationConfig)

        /**
         * Set the deserialization configuration for this [Builder] as a `Map` of `DeserializationFeature`
         * to `Boolean` (enabled).
         * @note this overwrites the default deserialization configuration of this [Builder].
         * @note the default is described by [KotlinObjectMapper.DEFAULT_DESERIALIZATION_CONFIG].
         * @see [KotlinObjectMapper.DEFAULT_DESERIALIZATION_CONFIG]
         */
        fun withDeserializationConfig(deserializationConfig: Map<DeserializationFeature, Boolean>): Builder =
            this.copy(deserializationConfig = deserializationConfig)

        /**
         * Configure a "DataClassValidator" for this [Builder]
         * @see [KotlinObjectMapper.DEFAULT_VALIDATOR]
         *
         * @note If you pass `withNoValidation` to the builder all validations will be
         *       bypassed, regardless of the `withValidator` configuration.
         */
        fun withValidator(validator: DataClassValidator): Builder =
            this.copy(validator = validator)

        /**
         * Configure the list of additional Jackson [Module]s for this [Builder].
         * @note this will overwrite (not append) the list additional Jackson [Module]s of this [Builder].
         */
        fun withAdditionalJacksonModules(additionalJacksonModules: List<Module>): Builder =
            this.copy(additionalJacksonModules = additionalJacksonModules)

        /**
         * Configure additional [ObjectMapper] functionality for the underlying mapper of this [Builder].
         * @note this will overwrite any previously set function.
         */
        fun withAdditionalMapperConfigurationFn(mapperFn: (ObjectMapper) -> Unit): Builder =
            this.copy(additionalMapperConfigurationFns = this.additionalMapperConfigurationFns + mapperFn)

        /** Method to allow changing of the default Jackson Modules  */
        internal fun withDefaultJacksonModules(defaultJacksonModules: List<Module>): Builder = 
            this.copy(defaultJacksonModules = defaultJacksonModules)

        /**
         * Disable validation during data class deserialization
         *
         * @see [KotlinObjectMapper.DEFAULT_ENABLE_VALIDATION]
         * @note If you pass `withNoValidation` to the builder all validations will be
         *       bypassed, regardless of the `withValidator` configuration.
         */
        fun withNoValidation(): Builder = this.copy(validation = false)

        private fun defaultMapperConfiguration(mapper: ObjectMapper) {
            /* Serialization Config */
            mapper.setDefaultPropertyInclusion(
                JsonInclude.Value.construct(serializationInclude, serializationInclude))
            for ((feature, state) in serializationConfig) {
                mapper.configure(feature, state)
            }

            /* Deserialization Config */
            for ((feature, state) in deserializationConfig) {
                mapper.configure(feature, state)
            }
        }

        /** Order is important: default + data class module + any additional */
        private fun jacksonModules(): List<Module> {
            val dataClassDeserializerModule = if (this.validation) {
                DataClassDeserializerModule(this.validator)
            } else DataClassDeserializerModule(null)
            return this.defaultJacksonModules + listOf(dataClassDeserializerModule) + this.additionalJacksonModules
        }

        private fun jacksonKotlinObjectMapper(): ObjectMapper =
            configureJacksonKotlinObjectMapper(JsonFactoryBuilder())

        private fun <F: JsonFactory> jacksonKotlinObjectMapper(jsonFactory: F): ObjectMapper =
            configureJacksonKotlinObjectMapper(jsonFactory)

        private fun <F: JsonFactory, B: TSFBuilder<F, B>> configureJacksonKotlinObjectMapper(builder: TSFBuilder<F, B>): ObjectMapper =
            configureJacksonKotlinObjectMapper(builder.build())

        private fun configureJacksonKotlinObjectMapper(factory: JsonFactory): ObjectMapper =
            configureJacksonKotlinObjectMapper(jacksonObjectMapper().copyWithFn(factory))

        fun configureJacksonKotlinObjectMapper(underlying: ObjectMapper): ObjectMapper {
            if (this.numbersAsStrings) {
                underlying.enable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS.mappedFeature())
            }

            this.defaultMapperConfiguration(underlying)
            this.additionalMapperConfigurationFns.forEach { it.invoke(underlying) }

            underlying.setPropertyNamingStrategy(this.propertyNamingStrategy)
            // Block use of a set of "unsafe" base types such as java.lang.Object
            // to prevent exploitation of Remote Code Execution (RCE) vulnerability
            // This line can be removed when this feature is enabled by default in Jackson 3
            underlying.enable(MapperFeature.BLOCK_UNSAFE_POLYMORPHIC_BASE_TYPES)

            this.jacksonModules().forEach { underlying.registerModule(it) }

            return underlying
        }
    }

    /**
     * Method for registering a module that can extend functionality provided by this mapper; for
     * example, by adding providers for custom serializers and deserializers.
     *
     * @note this mutates the [underlying] [com.fasterxml.jackson.databind.ObjectMapper] of
     *       this [KotlinObjectMapper].
     *
     * @param module [[com.fasterxml.jackson.databind.Module]] to register.
     */
    fun registerModule(module: Module): ObjectMapper =
        underlying.registerModule(module)

    companion object {
        /** The default 'DataClassValidator' for a [KotlinObjectMapper] */
        private val DEFAULT_VALIDATOR: DataClassValidator = DataClassValidator()

        /** The default [JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS] setting */
        private const val DEFAULT_NUMBERS_AS_STRINGS: Boolean = false

        /** Framework modules need to be added 'last' so they can override existing ser/des */
        private val DEFAULT_JACKSON_MODULES: List<Module> = emptyList()

        /** The default [PropertyNamingStrategy] for a [KotlinObjectMapper] */
        private val DEFAULT_PROPERTY_NAMING_STRATEGY: PropertyNamingStrategy =
            PropertyNamingStrategies.SNAKE_CASE

        /** The default [JsonInclude.Include] for serialization for a [KotlinObjectMapper] */
        private val DEFAULT_SERIALIZATION_INCLUDE: JsonInclude.Include =
            JsonInclude.Include.NON_ABSENT

        /** The default configuration for serialization as a `Map[SerializationFeature, Boolean]` */
        private val DEFAULT_SERIALIZATION_CONFIG: Map<SerializationFeature, Boolean> =
            mapOf(
                Pair(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false),
                Pair(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true)
            )

        /** The default configuration for deserialization as a `Map[DeserializationFeature, Boolean]` */
        private val DEFAULT_DESERIALIZATION_CONFIG: Map<DeserializationFeature, Boolean> =
            mapOf(
                Pair(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true),
                Pair(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
                Pair(DeserializationFeature.READ_ENUMS_USING_TO_STRING, true),
                Pair(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, true)
            )

        /** The default for configuring additional modules on the underlying [ObjectMapper] */
        private val DEFAULT_ADDITIONAL_JACKSON_MODULES: List<Module> = emptyList()

        /** The default setting to enable validation during data class deserialization */
        private const val DEFAULT_ENABLE_VALIDATION: Boolean = true

        private val ArrayElementsOnNewLinesPrettyPrinter: DefaultPrettyPrinter =
            DefaultPrettyPrinter().withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)

        /**
         *
         * Build a new instance of a [KotlinObjectMapper].
         *
         * For example,
         * {{{
         *   KotlinObjectMapper.builder()
         *    .withPropertyNamingStrategy(new PropertyNamingStrategies.UpperCamelCaseStrategy)
         *    .withNumbersAsStrings(true)
         *    .withAdditionalJacksonModules(...)
         *    .objectMapper
         * }}}
         *
         * or
         *
         * {{{
         *   val builder =
         *    KotlinObjectMapper.builder()
         *      .withPropertyNamingStrategy(new PropertyNamingStrategies.UpperCamelCaseStrategy)
         *      .withNumbersAsStrings(true)
         *      .withAdditionalJacksonModules(...)
         *
         *   val mapper = builder().objectMapper()
         *   val camelCaseMapper = builder().camelCaseObjectMapper()
         * }}}
         *
         */
        fun builder(): Builder = Builder()

        operator fun invoke(): KotlinObjectMapper = builder().objectMapper()

        operator fun invoke(underlying: ObjectMapper): KotlinObjectMapper =
            KotlinObjectMapper(builder().configureJacksonKotlinObjectMapper(underlying))

        /**
         * Utility to create a new [KotlinObjectMapper] which simply wraps the given
         * [ObjectMapper].
         *
         * @note the `underlying` mapper is not mutated to produce the new [KotlinObjectMapper]
         */
        fun objectMapper(underlying: ObjectMapper): KotlinObjectMapper {
            val objectMapperCopy = underlying.copyFn()
            return KotlinObjectMapper(objectMapperCopy)
        }

        /**
         * Utility to create a new [KotlinObjectMapper] explicitly configured to serialize and deserialize
         * YAML using the given [ObjectMapper]. The resultant mapper [PropertyNamingStrategy]
         * will be that configured on the `underlying` mapper.
         *
         * @note the `underlying` mapper is copied (not mutated) to produce the new [KotlinObjectMapper]
         *       to negotiate YAML serialization and deserialization.
         */
        fun yamlObjectMapper(underlying: ObjectMapper): KotlinObjectMapper =
            when (underlying.factory) {
                is YAMLFactory -> { // correct
                    val objectMapperCopy = underlying.copyFn()
                    KotlinObjectMapper(objectMapperCopy)
                }
                else -> // incorrect
                    throw IllegalArgumentException("The underlying mapper is not properly configured with a YAMLFactory")
            }

        /**
         * Utility to create a new [KotlinObjectMapper] explicitly configured with
         * [PropertyNamingStrategies.LOWER_CAMEL_CASE] as a `PropertyNamingStrategy` wrapping the
         * given [ObjectMapper].
         *
         * @note the `underlying` mapper is copied (not mutated) to produce the new [KotlinObjectMapper]]
         *       with a [PropertyNamingStrategies.LOWER_CAMEL_CASE] PropertyNamingStrategy.
         */
        fun camelCaseObjectMapper(underlying: ObjectMapper): KotlinObjectMapper {
            val objectMapperCopy = underlying.copyFn()
            objectMapperCopy.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
            return KotlinObjectMapper(objectMapperCopy)
        }

        /**
         * Utility to create a new [KotlinObjectMapper] explicitly configured with
         * [PropertyNamingStrategies.SNAKE_CASE] as a `PropertyNamingStrategy` wrapping the
         * given [ObjectMapper].
         *
         * @note the `underlying` mapper is copied (not mutated) to produce the new [KotlinObjectMapper]
         *       with a [PropertyNamingStrategies.SNAKE_CASE] PropertyNamingStrategy.
         */
        fun snakeCaseObjectMapper(underlying: ObjectMapper): KotlinObjectMapper {
            val objectMapperCopy = underlying.copyFn()
            objectMapperCopy.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            return KotlinObjectMapper(objectMapperCopy)
        }

        @PublishedApi
        internal fun handleIllegalArgumentException(e: IllegalArgumentException): Throwable {
            return when (val cause = e.cause) {
                null -> e
                else -> cause
            }
        }
    }
}
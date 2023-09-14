package angstromio.json

import angstromio.json.internal.deserializer.DataClassDeserializerBase
import angstromio.validation.DataClassValidator
import com.fasterxml.jackson.core.json.PackageVersion
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import jakarta.validation.Validator

class BeanDeserializerModule(private val validator: Validator?) :
    SimpleModule(BeanDeserializerModule::class.java.name, PackageVersion.VERSION) {

    private constructor(builder: Builder) : this(builder.validator)

    override fun setupModule(context: SetupContext) {
        super.setupModule(context)
        context.addDeserializers(DataClassDeserializerBase(validator))
    }

    class Builder(
        val validator: Validator? = DEFAULT_VALIDATOR,
        val validation: Boolean = DEFAULT_ENABLE_VALIDATION
    ) {
        companion object {
            /** The default 'Validator' for a [ObjectMapper] */
            private val DEFAULT_VALIDATOR: Validator = DataClassValidator()

            /** The default setting to enable validation during data class deserialization */
            private const val DEFAULT_ENABLE_VALIDATION: Boolean = true
        }

        fun withValidator(validator: Validator): Builder =
            Builder(
                validator = validator,
                validation = this.validation
            )

        fun disableValidation(): Builder =
            Builder(
                validator = null,
                validation = false
            )

        fun build(): BeanDeserializerModule = BeanDeserializerModule(this)
    }
}
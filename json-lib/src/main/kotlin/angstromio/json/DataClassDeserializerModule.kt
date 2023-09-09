package angstromio.json

import angstromio.json.deserializer.DataClassDeserializerBase
import angstromio.validation.DataClassValidator
import com.fasterxml.jackson.core.json.PackageVersion
import com.fasterxml.jackson.databind.module.SimpleModule
import jakarta.validation.Validator

class DataClassDeserializerModule(private val validator: Validator?) : SimpleModule(DataClassDeserializerModule::class.java.name, PackageVersion.VERSION) {

    override fun setupModule(context: SetupContext) {
        super.setupModule(context)
        context.addDeserializers(DataClassDeserializerBase(validator))
    }
}
package angstromio.json

import angstromio.json.deserializer.DataClassDeserializerBase
import com.fasterxml.jackson.core.json.PackageVersion
import com.fasterxml.jackson.databind.module.SimpleModule

class DataClassDeserializerModule(val dataClassValidator: Any?) : SimpleModule(DataClassDeserializerModule::class.java.name, PackageVersion.VERSION) {

    override fun setupModule(context: SetupContext) {
        super.setupModule(context)
        context.addDeserializers(DataClassDeserializerBase(dataClassValidator))
    }
}
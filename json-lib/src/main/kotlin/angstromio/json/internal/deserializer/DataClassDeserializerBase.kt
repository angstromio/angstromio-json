package angstromio.json.internal.deserializer

import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.deser.Deserializers
import jakarta.validation.Validator

internal class DataClassDeserializerBase(private val validator: Validator?) : Deserializers.Base() {

    override fun findBeanDeserializer(
        type: JavaType,
        deserializationConfig: DeserializationConfig,
        beanDescription: BeanDescription?
    ): DataClassDeserializer? =
        when {
            type.rawClass.kotlin.isData ->
                DataClassDeserializer(
                    javaType = type,
                    config = deserializationConfig,
                    beanDesc = beanDescription,
                    validator = validator
                )

            else -> null
        }
}
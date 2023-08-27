package angstromio.json.deserializer

import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.DeserializationConfig
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.deser.Deserializers

internal class DataClassDeserializerBase(private val dataClassValidator: Any?): Deserializers.Base() {

    override fun findBeanDeserializer(
        javaType: JavaType?,
        deserializationConfig: DeserializationConfig?,
        beanDescription: BeanDescription?
    ): JsonDeserializer<*>? {
        return if (javaType == null || !javaType.rawClass.kotlin.isData) { null } else {
            DataClassDeserializer<Any>(javaType, deserializationConfig, beanDescription, dataClassValidator)
        }
    }
}
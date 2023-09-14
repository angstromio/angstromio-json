package angstromio.json.internal.deserializer

import com.fasterxml.jackson.annotation.JacksonAnnotation
import com.fasterxml.jackson.annotation.JacksonInject
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.PropertyName
import com.fasterxml.jackson.databind.deser.impl.ValueInjector
import com.fasterxml.jackson.databind.introspect.AnnotationMap
import com.fasterxml.jackson.databind.introspect.AnnotatedMember
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter
import com.fasterxml.jackson.databind.introspect.AnnotatedWithParams
import com.fasterxml.jackson.databind.introspect.TypeResolutionContext

internal data class DataClassBeanProperty(
    val valueObj: JacksonInject.Value?,
    val property: BeanProperty
) {
    companion object {

        fun newBeanProperty(
            context: DeserializationContext,
            javaType: JavaType?,
            optionalJavaType: JavaType?,
            annotatedParameter: AnnotatedParameter,
            annotations: List<Annotation>,
            name: String,
            index: Int
        ): DataClassBeanProperty {
            // to support deserialization with JsonFormat and other Jackson annotations on fields,
            // the Jackson annotations must be carried in the mutator of the ValueInjector.
            val jacksonAnnotations = AnnotationMap()
            val contextAnnotationsMap = AnnotationMap()
            annotations.forEach { annotation ->
                if (annotation.javaClass.isAnnotationPresent(JacksonAnnotation::class.java)) {
                    jacksonAnnotations.add(annotation)
                } else {
                    contextAnnotationsMap.add(annotation)
                }
            }

            val mutator: AnnotatedMember = when {
                optionalJavaType != null ->
                    newAnnotatedParameter(
                        context,
                        annotatedParameter,
                        AnnotationMap.merge(jacksonAnnotations, contextAnnotationsMap),
                        optionalJavaType,
                        index)
                else ->
                    newAnnotatedParameter(
                        context,
                        annotatedParameter,
                        AnnotationMap.merge(jacksonAnnotations, contextAnnotationsMap),
                        javaType,
                        index)
            }

            val jacksonInjectValue = context.annotationIntrospector.findInjectableValue(mutator)
            val beanProperty: BeanProperty =
                ValueInjector(
                    /* propName           = */ PropertyName(name),
                    /* type               = */ optionalJavaType ?: javaType,
                    /* contextAnnotations = */ contextAnnotationsMap, // see [databind#1835]
                    /* mutator            = */ mutator,
                    /* valueId            = */ jacksonInjectValue?.id
                )

            return DataClassBeanProperty(valueObj = jacksonInjectValue, property = beanProperty)
        }

        fun newAnnotatedParameter(
            context: DeserializationContext,
            base: AnnotatedParameter?,
            annotations: AnnotationMap,
            javaType: JavaType?,
            index: Int
        ): AnnotatedMember =
            newAnnotatedParameter(
                TypeResolutionContext.Basic(context.typeFactory, javaType?.bindings),
                base?.owner,
                annotations,
                javaType,
                index
            )

        fun newAnnotatedParameter(
            typeResolutionContext: TypeResolutionContext,
            owner: AnnotatedWithParams?,
            annotations: AnnotationMap,
            javaType: JavaType?,
            index: Int
        ): AnnotatedMember =
            AnnotatedParameter(
                /* owner       = */ owner,
                /* type        = */ javaType,
                /* typeContext = */ typeResolutionContext,
                /* annotations = */ annotations,
                /* index       = */ index
            )
    }
}
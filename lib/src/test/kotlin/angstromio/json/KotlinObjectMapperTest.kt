package angstromio.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.kotest.matchers.equals.shouldBeEqual
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KotlinObjectMapperTest : AbstractKotlinObjectMapperTest() {
    class MixInAnnotationsModule: SimpleModule() {
        init {
            setMixInAnnotation(Point::class.java, PointMixin::class.java)
            setMixInAnnotation(ClassShouldUseKebabCaseFromMixin::class.java, KebabCaseMixin::class.java)
        }
    }

    override val mapper: KotlinObjectMapper by lazy {
        val m = KotlinObjectMapper()
        m.registerModule(MixInAnnotationsModule())
        m
    }

    init {
        test("constructors") {
            // new mapper with defaults
            assertNotNull(KotlinObjectMapper())
            assertNotNull(KotlinObjectMapper.invoke())

            // augments the given mapper with the defaults
            assertNotNull(KotlinObjectMapper(mapper.underlying))
            assertNotNull(KotlinObjectMapper.invoke(mapper.underlying))

            // copies the underlying Jackson object mapper
            assertNotNull(KotlinObjectMapper.objectMapper(mapper.underlying))
            // underlying mapper needs to have a JsonFactory of type YAMLFactory
            assertThrows<IllegalArgumentException> {
                KotlinObjectMapper.yamlObjectMapper(mapper.underlying)
            }
            assertNotNull(
                KotlinObjectMapper.yamlObjectMapper(KotlinObjectMapper.builder().yamlObjectMapper().underlying))
            assertNotNull(KotlinObjectMapper.camelCaseObjectMapper(mapper.underlying))
            assertNotNull(KotlinObjectMapper.snakeCaseObjectMapper(mapper.underlying))
        }

        test("builder constructors") {
            assertNotNull(KotlinObjectMapper.builder().objectMapper())
            assertNotNull(KotlinObjectMapper.builder().objectMapper(JsonFactory()))
            assertNotNull(KotlinObjectMapper.builder().objectMapper(YAMLFactory()))

            assertNotNull(KotlinObjectMapper.builder().yamlObjectMapper())
            assertNotNull(KotlinObjectMapper.builder().camelCaseObjectMapper())
            assertNotNull(KotlinObjectMapper.builder().snakeCaseObjectMapper())
        }


        test("deserialization#test @JsonNaming") {
            val json =
                """
                |{
                |  "please-use-kebab-case": true
                |}
                |""".trimMargin()

            parse<ClassWithKebabCase>(json) shouldBeEqual ClassWithKebabCase(true)

            val snakeCaseMapper = KotlinObjectMapper.snakeCaseObjectMapper(mapper.underlying)
            snakeCaseMapper.parse<ClassWithKebabCase>(json) shouldBeEqual ClassWithKebabCase(true)

            val camelCaseMapper = KotlinObjectMapper.camelCaseObjectMapper(mapper.underlying)
            camelCaseMapper.parse<ClassWithKebabCase>(json) shouldBeEqual ClassWithKebabCase(true)
        }

        test("deserialization#mixin annotations") {
            val points = Points(first = Point(1, 1), second = Point(4, 5))
            val json = """{"first": { "x": 1, "y": 1 }, "second": { "x": 4, "y": 5 }}"""

            parse<Points>(json) shouldBeEqual points
            generate(points) shouldBeEqual """{"first":{"x":1,"y":1},"second":{"x":4,"y":5}}"""
        }
    }
}
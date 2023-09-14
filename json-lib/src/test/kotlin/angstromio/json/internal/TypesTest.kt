package angstromio.json.internal

import angstromio.json.CarMake
import angstromio.json.TestClasses
import angstromio.json.Things
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

class TypesTest : FunSpec() {

    init {

        test("Types#wrapperType") {
            val byte = Types.wrapperType(java.lang.Byte.TYPE)
            byte shouldNot beNull()
            java.lang.Byte::class.javaObjectType shouldBeEqual byte

            val short = Types.wrapperType(java.lang.Short.TYPE)
            short shouldNot beNull()
            java.lang.Short::class.javaObjectType shouldBeEqual short

            val char = Types.wrapperType(Character.TYPE)
            char shouldNot beNull()
            java.lang.Character::class.javaObjectType shouldBeEqual char

            val int = Types.wrapperType(Integer.TYPE)
            int shouldNot beNull()
            java.lang.Integer::class.javaObjectType shouldBeEqual int

            val long = Types.wrapperType(java.lang.Long.TYPE)
            long shouldNot beNull()
            java.lang.Long::class.javaObjectType shouldBeEqual long

            val float = Types.wrapperType(java.lang.Float.TYPE)
            float shouldNot beNull()
            java.lang.Float::class.javaObjectType shouldBeEqual float

            val double = Types.wrapperType(java.lang.Double.TYPE)
            double shouldNot beNull()
            java.lang.Double::class.javaObjectType shouldBeEqual double

            val bool = Types.wrapperType(java.lang.Boolean.TYPE)
            bool shouldNot beNull()
            java.lang.Boolean::class.javaObjectType shouldBeEqual bool

            val voidT = Types.wrapperType(Void.TYPE)
            voidT shouldNot beNull()
            java.lang.Void::class.javaObjectType shouldBeEqual voidT

            val string = Types.wrapperType(java.lang.String::class.java)
            string shouldNot beNull()
            java.lang.String::class.java shouldBeEqual string
        }

        test("is value class") {
            Types.isValueClass(TestClasses.TestIdStringWrapper::class.java) should be(true)
        }

        test("regex") {
            val message = "No argument provided for a required parameter: parameter #0 ignored of fun `<init>`(angstromio.json.TestClasses.IgnoreMe, kotlin.String, kotlin.String): angstromio.json.TestClasses.ContainsAnIgnoreTypeNoDefault"
            val regex = """parameter #([0-9]+)""".toRegex()
            val secondPassRegex = """([0-9]+)""".toRegex()
            val firstPass = regex.find(message)!!.groupValues.first()
            secondPassRegex.find(firstPass)!!.groupValues.first().toInt() should be(0)
        }
    }
}
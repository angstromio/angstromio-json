package angstromio.json.deserializer

import angstromio.json.WithDefaults
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.be
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldNot

class TypesTest : FunSpec({

    test("Types#wrapperType") {
        val byte = Types.wrapperType(java.lang.Byte.TYPE)
        byte shouldNot beNull()
        java.lang.Byte::class.javaPrimitiveType!! shouldBeEqual byte

        val short = Types.wrapperType(java.lang.Short.TYPE)
        short shouldNot beNull()
        java.lang.Short::class.javaPrimitiveType!! shouldBeEqual short

        val char = Types.wrapperType(java.lang.Character.TYPE)
        char shouldNot beNull()
        java.lang.Character::class.javaPrimitiveType!! shouldBeEqual char

        val int = Types.wrapperType(java.lang.Integer.TYPE)
        int shouldNot beNull()
        java.lang.Integer::class.javaPrimitiveType!! shouldBeEqual int

        val long = Types.wrapperType(java.lang.Long.TYPE)
        long shouldNot beNull()
        java.lang.Long::class.javaPrimitiveType!! shouldBeEqual long

        val float = Types.wrapperType(java.lang.Float.TYPE)
        float shouldNot beNull()
        java.lang.Float::class.javaPrimitiveType!! shouldBeEqual float

        val double = Types.wrapperType(java.lang.Double.TYPE)
        double shouldNot beNull()
        java.lang.Double::class.javaPrimitiveType!! shouldBeEqual double

        val bool = Types.wrapperType(java.lang.Boolean.TYPE)
        bool shouldNot beNull()
        java.lang.Boolean::class.javaPrimitiveType!! shouldBeEqual bool

        val voidT = Types.wrapperType(java.lang.Void.TYPE)
        voidT shouldNot beNull()
        java.lang.Void::class.javaPrimitiveType!! shouldBeEqual voidT

        val string = Types.wrapperType(java.lang.String::class.java)
        string shouldNot beNull()
        java.lang.String::class.java shouldBeEqual string
    }

    test("Types#defaultInstance") {
        val first = Types.getDefaultFunctionForParameter(WithDefaults::class, 0)
        first shouldNot beNull()
        first!!.invoke() should be("Hello")

        val second = Types.getDefaultFunctionForParameter(WithDefaults::class, 1)
        second shouldNot beNull()
        second!!.invoke() should be("World")
    }
})
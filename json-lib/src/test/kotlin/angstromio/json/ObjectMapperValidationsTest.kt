package angstromio.json

import io.kotest.core.spec.style.FunSpec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ObjectMapperValidationsTest : FunSpec() {

    companion object {
        private val now: LocalDateTime =
            LocalDateTime.parse("2015-04-09T05:17:15Z", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        private val ownershipEnd = now.plusYears(15)
        private val warrantyEnd = now.plusYears(5)
        private val BASE_CAR: TestClasses.Car = TestClasses.Car(
            id = 1,
            make = CarMake.FORD,
            model = "Model-T",
            year = 2000,
            owners = emptyList(),
            numDoors = 2,
            manual = true,
            ownershipStart = now,
            ownershipEnd = ownershipEnd,
            warrantyStart = now,
            warrantyEnd = warrantyEnd,
            passengers = emptyList<TestClasses.Person>()
        )
    }
    init {}
}
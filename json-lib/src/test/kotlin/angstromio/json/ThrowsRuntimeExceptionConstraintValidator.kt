package angstromio.json

import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

class ThrowsRuntimeExceptionConstraintValidator : ConstraintValidator<ThrowsRuntimeExceptionConstraint, Any> {
    override fun isValid(value: Any?, context: ConstraintValidatorContext?): Boolean {
        throw RuntimeException("validator foo error")
    }
}
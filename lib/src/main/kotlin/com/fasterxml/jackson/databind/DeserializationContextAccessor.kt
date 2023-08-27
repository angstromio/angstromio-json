package com.fasterxml.jackson.databind

class DeserializationContextAccessor(private val context: DeserializationContext) {

    fun injectableValues(): InjectableValues? {
        return context._injectableValues
    }
}
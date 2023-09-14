package com.fasterxml.jackson.databind

internal class DeserializationContextAccessor(private val context: DeserializationContext) {

    fun injectableValues(): InjectableValues? {
        return context._injectableValues
    }
}
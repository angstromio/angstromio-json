@file:kotlin.jvm.JvmMultifileClass

package com.fasterxml.jackson.databind

import com.fasterxml.jackson.core.JsonFactory

fun ObjectMapper.copyWithFn(factory: JsonFactory): ObjectMapper =
    ObjectMapperCopier.copyWithFn(this, factory)

fun ObjectMapper.copyFn(): ObjectMapper =
    ObjectMapperCopier.copyFn(this)

object ObjectMapperCopier {

    /** to get around JsonMapper not implementing a `copyWith` override */
    fun copyWithFn(objectMapper: ObjectMapper, factory: JsonFactory): ObjectMapper =
        ObjectMapper(objectMapper, factory)

    fun copyFn(objectMapper: ObjectMapper): ObjectMapper = objectMapper.copy()
}
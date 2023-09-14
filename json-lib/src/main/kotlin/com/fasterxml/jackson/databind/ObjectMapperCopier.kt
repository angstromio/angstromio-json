@file:kotlin.jvm.JvmMultifileClass

package com.fasterxml.jackson.databind

import com.fasterxml.jackson.core.JsonFactory

internal object ObjectMapperCopier {

    /** to get around JsonMapper not implementing a `copyWith` override */
    fun makeCopy(objectMapper: ObjectMapper, factory: JsonFactory): ObjectMapper =
        ObjectMapper(objectMapper, factory)

    fun makeCopy(objectMapper: ObjectMapper): ObjectMapper = objectMapper.copy()
}
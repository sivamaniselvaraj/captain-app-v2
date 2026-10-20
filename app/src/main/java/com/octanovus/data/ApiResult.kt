package com.octanovus.data

sealed interface ApiResult<out T> {
    data class Ok<out T>(val value: T) : ApiResult<T>
    data class Err(val message: String, val code: Int? = null) : ApiResult<Nothing>
}

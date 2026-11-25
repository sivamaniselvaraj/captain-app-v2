package com.octanovus.data

sealed interface ApiResult<out T> {
    data class Ok<out T>(val value: T) : ApiResult<T>
    data class Err(
        val message: String,
        val code: Int? = null,
        /** The desktop's own error code, for example WRONG_PIN, PIN_WEAK or PIN_REQUIRED. */
        val apiCode: String? = null,
        val retryAfterSeconds: Int? = null,
        val attemptsLeft: Int? = null,
    ) : ApiResult<Nothing>
}

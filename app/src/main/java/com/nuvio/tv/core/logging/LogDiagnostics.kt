package com.nuvio.tv.core.logging

// Runtime identifiers, URLs and response bodies can contain login/stream credentials.
fun String?.rawForLog(): String = if (this == null) "(null)" else "[redacted]"
fun String?.urlForLog(): String = if (this == null) "(null)" else "[redacted URL]"
fun String?.bodySnippetForLog(maxLength: Int = Int.MAX_VALUE): String =
    if (this == null) "(null)" else "[redacted body]"

fun Throwable.diagnosticSummary(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && parts.size < 6) {
        parts.add(current.javaClass.simpleName.ifBlank { current.javaClass.name })
        current = current.cause
    }
    return parts.joinToString(" <- ")
}

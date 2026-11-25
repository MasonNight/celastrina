package dev.celastrina.core.source

import dev.celastrina.core.port.HttpResponse
import dev.celastrina.core.source.LookupOutcome.Failure.Reason

/**
 * Why a Source's HTTP [HttpResponse] can't be used, as a Failure reason, or null when it succeeded. Every adapter that
 * reads a Source (or an Extension Repository) over HTTP maps statuses the same way: 401 and 403 need a login, 429 is
 * rate limited, 502 to 504 (a gateway or proxy in front of a service that's down) are unreachable, and anything else is
 * a Source error. An adapter that reads a status as a normal answer, such as a 404 for "nothing here", checks it first.
 */
internal fun HttpResponse.failureReason(): Reason? = when {
    isSuccessful -> null
    status == 401 || status == 403 -> Reason.AuthRequired
    status == 429 -> Reason.RateLimited
    status in 502..504 -> Reason.Unreachable
    else -> Reason.SourceError
}

package dev.celastrina.core.catalogue

/** The Catalogue couldn't be read. [reason] says why, so screens can say something useful. */
class CatalogueUnavailable(val reason: Reason, cause: Throwable? = null) :
    Exception("Catalogue unavailable: $reason", cause) {

    enum class Reason {
        /** TMDB couldn't be reached at all. */
        Unreachable,

        /** TMDB refused the key. */
        KeyRejected,

        /** TMDB answered, but not with something usable. */
        ServiceError,
    }
}

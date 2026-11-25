package dev.celastrina.core.source

import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.port.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Asks the Household's Sources, given in the Household's order, for Streams of a Title. */
class StreamLookup(
    private val sources: List<Source>,
    private val logger: Logger,
) {
    /**
     * Asks every Source that can match [title], all at once, and emits each one's answer as it arrives.
     * Sources whose Kind can't match the Title are skipped. A Source that doesn't answer within its Kind's
     * [SourceKind.lookupTimeout] is unreachable. Streams Celastrina won't play are dropped and logged.
     */
    fun lookUp(title: TitleReference): Flow<SourceAnswer> = channelFlow {
        matching(title).forEach { source ->
            launch { send(SourceAnswer(source, ask(source, title))) }
        }
    }

    /**
     * The same Lookup as [lookUp], told as where it has got to: first every Source asked still looking, then once more
     * after each answer. The last one emitted [Lookup.isFinished].
     */
    fun watch(title: TitleReference): Flow<Lookup> = flow {
        var lookup = Lookup(matching(title).map { SourceLookup(it, outcome = null) })
        emit(lookup)
        lookUp(title).collect { answer ->
            lookup = Lookup(lookup.sources.map { if (it.source === answer.source) SourceLookup(it.source, answer.outcome) else it })
            emit(lookup)
        }
    }

    private fun matching(title: TitleReference) = sources.filter { it.kind.canMatch(title) }

    private suspend fun ask(source: Source, title: TitleReference): LookupOutcome {
        val outcome = try {
            withTimeoutOrNull(source.kind.lookupTimeout) { source.lookUp(title) }
                ?: return LookupOutcome.Failure(LookupOutcome.Failure.Reason.Unreachable).also {
                    logger.info("Source ${source.id} didn't answer within ${source.kind.lookupTimeout} looking up ${title.title}")
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.info("Source ${source.id} failed looking up ${title.title}: $e")
            return LookupOutcome.Failure(LookupOutcome.Failure.Reason.SourceError)
        }
        if (outcome !is LookupOutcome.Streams) return outcome
        val (playable, dropped) = outcome.streams.partition { it.unplayableBecause == null }
        dropped.forEach { logger.info("Dropped Stream ${it.id} from Source ${source.id}: ${it.unplayableBecause}") }
        return if (playable.isEmpty()) LookupOutcome.NotAvailable else LookupOutcome.Streams(playable)
    }
}

/** One Source's answer to a lookup. */
data class SourceAnswer(val source: Source, val outcome: LookupOutcome)

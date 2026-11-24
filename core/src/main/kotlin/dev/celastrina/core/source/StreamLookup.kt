package dev.celastrina.core.source

import dev.celastrina.core.catalogue.TitleReference
import dev.celastrina.core.port.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Asks the Household's Sources for Streams of a Title. */
class StreamLookup(
    private val sources: List<Source>,
    private val logger: Logger,
) {
    /**
     * Asks every Source that can match [title], all at once, and emits each one's answer as it arrives.
     * Sources whose Kind can't match the Title are skipped. Streams Celastrina won't play are dropped and logged.
     */
    fun lookUp(title: TitleReference): Flow<SourceAnswer> = channelFlow {
        sources.filter { it.kind.canMatch(title) }.forEach { source ->
            launch { send(SourceAnswer(source, ask(source, title))) }
        }
    }

    private suspend fun ask(source: Source, title: TitleReference): LookupOutcome {
        val outcome = try {
            source.lookUp(title)
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

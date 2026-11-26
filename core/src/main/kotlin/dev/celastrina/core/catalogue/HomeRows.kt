package dev.celastrina.core.catalogue

/** Where a [Row] goes on from: which [HomeRow] it is, and each TMDB list it merges with what's still unshown. */
internal class RowRest(val homeRow: HomeRow, val lists: List<RowList>)

/** One TMDB list feeding a row: the Titles fetched but not shown yet, and the next page to fetch, if any. */
internal data class RowList(val source: ListSource, val waiting: List<Listed>, val nextPage: Int?)

/** A TMDB list endpoint and the Title kind it lists. */
internal class ListSource(val path: String, val kind: Title.Kind, val query: List<Pair<String, String>> = emptyList())

/** A Title as a TMDB list gave it, with its popularity for merging lists. */
internal class Listed(val title: Title, val popularity: Double)

/** Builds home's rows from TMDB lists, a page of Titles at a time. [fetch] reads one page of a list. */
internal class HomeRows(private val fetch: suspend (ListSource, page: Int) -> ListPage) {

    /** One page of a list; [fromOfflineCache] when it came from the cache because TMDB couldn't be reached. */
    class ListPage(val titles: List<Listed>, val nextPage: Int?, val fromOfflineCache: Boolean = false)

    suspend fun first(homeRow: HomeRow): Row =
        fill(homeRow, emptyList(), sourcesOf(homeRow).map { RowList(it, waiting = emptyList(), nextPage = 1) })

    suspend fun more(row: Row): Row {
        val rest = row.rest ?: return row
        return fill(rest.homeRow, row.titles, rest.lists, row.fromOfflineCache)
    }

    /**
     * Adds up to a page of Titles to [shown]. Each step takes the most popular Title waiting at the head of any list,
     * fetching a list's next page whenever it runs dry, so merged lists stay in popularity order across pages.
     */
    private suspend fun fill(homeRow: HomeRow, shown: List<Title>, startLists: List<RowList>, shownFromOfflineCache: Boolean = false): Row {
        var fromOfflineCache = shownFromOfflineCache
        val titles = shown.toMutableList()
        val seen = shown.mapTo(mutableSetOf()) { it.kind to it.tmdbId }
        val target = minOf(titles.size + PAGE_SIZE, MAX_TITLES)
        var lists = startLists
        while (titles.size < target) {
            lists = lists.map { list -> refill(homeRow, list) { fromOfflineCache = true } }
            val next = lists.indices.filter { lists[it].waiting.isNotEmpty() }
                .maxByOrNull { lists[it].waiting.first().popularity } ?: break
            val taken = lists[next].waiting.first().title
            lists = lists.toMutableList().also { it[next] = it[next].copy(waiting = it[next].waiting.drop(1)) }
            if (seen.add(taken.kind to taken.tmdbId)) titles += taken
        }
        val exhausted = lists.all { it.waiting.isEmpty() && it.nextPage == null }
        return Row(homeRow.label, titles, RowRest(homeRow, lists).takeUnless { exhausted || titles.size >= MAX_TITLES }, fromOfflineCache)
    }

    private suspend fun refill(homeRow: HomeRow, list: RowList, offline: () -> Unit): RowList {
        var refilled = list
        while (refilled.waiting.isEmpty() && refilled.nextPage != null) {
            val page = fetch(refilled.source, refilled.nextPage)
            if (page.fromOfflineCache) offline()
            refilled = refilled.copy(
                waiting = page.titles.filter { it.title.category == homeRow.category },
                nextPage = page.nextPage,
            )
        }
        return refilled
    }

    private val HomeRow.category
        get() = when (this) {
            HomeRow.Movies, HomeRow.Series -> null
            HomeRow.Anime -> Category.Anime
            HomeRow.Cartoons -> Category.Cartoons
        }

    private fun sourcesOf(homeRow: HomeRow): List<ListSource> = when (homeRow) {
        HomeRow.Movies -> listOf(ListSource("/trending/movie/week", Title.Kind.Movie))
        HomeRow.Series -> listOf(ListSource("/trending/tv/week", Title.Kind.Series))
        HomeRow.Anime -> animation(ANIMATION_QUERY + ("with_original_language" to JAPANESE))
        HomeRow.Cartoons -> animation(ANIMATION_QUERY) // TMDB can't exclude a language; Anime is filtered out on arrival.
    }

    private fun animation(query: List<Pair<String, String>>) = listOf(
        ListSource("/discover/movie", Title.Kind.Movie, query),
        ListSource("/discover/tv", Title.Kind.Series, query),
    )

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_TITLES = 100
        const val JAPANESE = "ja"
        const val ANIMATION_GENRE = 16
        private val ANIMATION_QUERY = listOf("with_genres" to ANIMATION_GENRE.toString(), "sort_by" to "popularity.desc")

        /** The Category a Title with TMDB genres [genreIds] and [originalLanguage] belongs to, if any. */
        fun categoryOf(genreIds: Collection<Int>, originalLanguage: String?): Category? = when {
            ANIMATION_GENRE !in genreIds -> null
            originalLanguage == JAPANESE -> Category.Anime
            else -> Category.Cartoons
        }
    }
}

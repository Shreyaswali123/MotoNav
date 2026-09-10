package com.example.data

/**
 * Normalizes place search queries to make them tolerant of user variations:
 * - Trims leading/trailing whitespace
 * - Collapses repeated spaces
 * - Strips or separates punctuation (e.g., "Tolan-Kere" -> "Tolan Kere", "K.L.E." -> "KLE")
 * - Handles CamelCase compound words (e.g., "TolanKere" -> "Tolan Kere")
 * - Provides lightweight common abbreviation and synonym expansions
 *   (e.g., "Tech" -> "Technological", "Hubli" -> "Hubballi", "Stn" -> "Station")
 */
object PlaceQueryNormalizer {

    private val COMMON_ABBREVIATIONS = mapOf(
        "tech" to "technological",
        "stn" to "station",
        "univ" to "university",
        "uni" to "university",
        "rd" to "road",
        "jn" to "junction",
        "junc" to "junction",
        "clg" to "college",
        "hubli" to "hubballi",
        "hwy" to "highway",
        "apt" to "airport",
        "hosp" to "hospital"
    )

    private val PHRASE_ALIASES = mapOf(
        "kle tech" to "KLE Technological University",
        "kle tech hubli" to "KLE Technological University Hubballi",
        "kle tech hubballi" to "KLE Technological University Hubballi",
        "tolankere" to "Tolan Kere",
        "tolan kere" to "Tolan Kere",
        "tolankere lake" to "Tolankere Lake Hubballi",
        "unkal" to "Unkal Lake Hubballi"
    )

    /**
     * Normalizes harmless variations without aggressively rewriting the query:
     * - Trims whitespace
     * - Handles acronym dots (e.g. K.L.E. -> KLE)
     * - Splits CamelCase compounds (e.g. TolanKere -> Tolan Kere)
     * - Replaces dashes/underscores/slashes/commas with single space
     * - Strips extraneous punctuation characters (!, ?, @, #, etc.)
     * - Collapses multiple spaces
     */
    fun normalize(rawQuery: String): String {
        var q = rawQuery.trim()
        if (q.isBlank()) return ""

        // Remove dots attached to letters (e.g. K.L.E. -> KLE, St. -> St)
        q = q.replace(Regex("(?<=[A-Za-z])\\."), "")

        // Split CamelCase compound words like TolanKere -> Tolan Kere
        q = q.replace(Regex("([a-z])([A-Z])"), "$1 $2")

        // Replace hyphens, underscores, slashes, commas with space
        q = q.replace(Regex("[-_/,]"), " ")

        // Remove unwanted punctuation characters
        q = q.replace(Regex("[!?@#$%*()\\[\\]{}\"';:]"), " ")

        // Handle specific compound names like "tolankere" -> "tolan kere"
        if (q.equals("tolankere", ignoreCase = true)) {
            q = "Tolan Kere"
        }

        // Collapse whitespace
        q = q.replace(Regex("\\s+"), " ").trim()

        return q
    }

    /**
     * Returns candidate queries starting with the primary normalized query,
     * followed by candidate alias/abbreviation expansions if applicable.
     */
    fun getCandidateQueries(rawQuery: String): List<String> {
        val normalized = normalize(rawQuery)
        if (normalized.isBlank()) return emptyList()

        val candidates = mutableListOf<String>()
        candidates.add(normalized)

        val lower = normalized.lowercase()
        val alias = PHRASE_ALIASES[lower]
        if (alias != null && !candidates.any { it.equals(alias, ignoreCase = true) }) {
            candidates.add(alias)
        }

        val tokens = normalized.split(" ")
        var hasExpansion = false
        val expandedTokens = tokens.map { token ->
            val exp = COMMON_ABBREVIATIONS[token.lowercase()]
            if (exp != null) {
                hasExpansion = true
                exp
            } else {
                token
            }
        }
        if (hasExpansion) {
            val expanded = expandedTokens.joinToString(" ")
            if (!candidates.any { it.equals(expanded, ignoreCase = true) }) {
                candidates.add(expanded)
            }
        }

        return candidates
    }

    /**
     * Returns a set of synonyms/equivalents for a given search token
     * to support fuzzy token matching against Nominatim results.
     */
    fun getSynonymsForToken(token: String): Set<String> {
        val t = token.lowercase().trim()
        val set = mutableSetOf(t)
        when (t) {
            "tech", "technological", "technology" -> set.addAll(listOf("tech", "technological", "technology"))
            "stn", "station" -> set.addAll(listOf("stn", "station", "railway"))
            "univ", "uni", "university" -> set.addAll(listOf("univ", "uni", "university"))
            "clg", "college" -> set.addAll(listOf("clg", "college"))
            "rd", "road" -> set.addAll(listOf("rd", "road"))
            "jn", "junc", "junction" -> set.addAll(listOf("jn", "junc", "junction"))
            "hubli", "hubballi" -> set.addAll(listOf("hubli", "hubballi"))
            "kere", "lake" -> set.addAll(listOf("kere", "lake", "tank"))
            "apt", "airport" -> set.addAll(listOf("apt", "airport", "aerodrome"))
            "hwy", "highway" -> set.addAll(listOf("hwy", "highway"))
        }
        return set
    }
}

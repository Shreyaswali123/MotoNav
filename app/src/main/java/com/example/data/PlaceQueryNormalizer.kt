package com.example.data

import java.text.Normalizer
import java.util.Locale

/**
 * Normalizes place search queries and generates bounded, deterministic query variants:
 * - Trims leading/trailing whitespace
 * - Performs Unicode decomposition and strips diacritics
 * - Normalizes punctuation (dashes, slashes, commas, dots to spaces or clean tokens)
 * - Lowercases with Locale.ROOT
 * - Collapses repeated whitespace
 * - Handles CamelCase compound words (e.g. "TolanKere" -> "tolan kere")
 * - Handles safe abbreviation expansions (e.g. "hosp" -> "hospital", "stn" -> "station")
 * - Generates high-value compound split/join variants for toponymic suffixes (e.g. "budanagudda" -> "budan gudda")
 * - Generates transliteration and phonetic variants (e.g. "siddharooda matta" -> "siddharuda math")
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

    // Common toponymic suffixes in Indian / regional place names
    private val GEOGRAPHIC_SUFFIXES = listOf(
        "gudda",   // hill
        "nagar",   // neighborhood/town
        "kere",    // lake/tank
        "halli",   // village
        "pura",    // town
        "pur",     // town
        "giri",    // peak
        "wadi",    // settlement
        "vadi",    // settlement
        "pete",    // market
        "pet",     // market
        "palya",   // locality
        "road",    // road
        "lake"     // lake
    )

    /**
     * Normalizes query text:
     * - Unicode normalization (NFD, strip diacritics)
     * - Trim and lowercase
     * - Removes acronym dots (e.g. K.L.E. -> kle)
     * - Splits CamelCase compounds (e.g. TolanKere -> tolan kere)
     * - Replaces punctuation with spaces
     * - Collapses multiple spaces
     */
    fun normalize(rawQuery: String): String {
        if (rawQuery.isBlank()) return ""

        // 1. Unicode normalization: decompose and strip diacritical marks
        val decomposed = Normalizer.normalize(rawQuery, Normalizer.Form.NFD)
        var q = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

        // 2. Remove dots attached to letters (e.g. K.L.E. -> KLE, St. -> St)
        q = q.replace(Regex("(?<=[A-Za-z])\\."), "")

        // 3. Split CamelCase compound words like TolanKere -> Tolan Kere
        q = q.replace(Regex("([a-z])([A-Z])"), "$1 $2")

        // 4. Replace punctuation, hyphens, underscores, slashes, commas with space
        q = q.replace(Regex("[-_/,]"), " ")
        q = q.replace(Regex("[!?@#$%*()\\[\\]{}\"';:]"), " ")

        // 5. Collapse whitespace
        q = q.replace(Regex("\\s+"), " ").trim()

        return q
    }

    /**
     * Expands common abbreviations in tokens safely (e.g. "kims hosp" -> "kims hospital").
     */
    fun expandAbbreviations(normalizedQuery: String): String {
        if (normalizedQuery.isBlank()) return ""
        val tokens = normalizedQuery.split(" ").filter { it.isNotBlank() }
        var modified = false
        val expanded = tokens.map { token ->
            val exp = COMMON_ABBREVIATIONS[token.lowercase(Locale.ROOT)]
            if (exp != null) {
                modified = true
                exp
            } else {
                token
            }
        }
        return if (modified) expanded.joinToString(" ") else normalizedQuery
    }

    /**
     * Generates a bounded, deterministic list of high-value query variants:
     * 1. Primary normalized query
     * 2. Abbreviation-expanded query
     * 3. Compound split / joined variant (e.g. "budanagudda" <-> "budan gudda", "vidyanagar" <-> "vidya nagar")
     * 4. Transliteration / suffix variant (e.g. "siddharooda matta" <-> "siddharuda math")
     *
     * Bounded to at most 4 variants total without combinatorial explosion.
     */
    fun generateVariants(rawQuery: String): List<String> {
        val normalized = normalize(rawQuery)
        if (normalized.isBlank()) return emptyList()

        val results = LinkedHashSet<String>()
        results.add(normalized)

        // 1. Abbreviation expansion
        val abbrevExpanded = expandAbbreviations(normalized)
        if (abbrevExpanded.isNotBlank() && abbrevExpanded != normalized) {
            results.add(abbrevExpanded)
        }

        // 2. Compound word splitting & joining
        val compoundVariants = generateCompoundVariants(normalized)
        for (variant in compoundVariants) {
            if (results.size >= 4) break
            results.add(variant)
        }

        // 3. Transliteration & suffix variations (oo <-> u, dh <-> d, matta/mata/matha <-> math)
        val translitVariants = generateTransliterationVariants(normalized)
        for (variant in translitVariants) {
            if (results.size >= 4) break
            results.add(variant)
        }

        // Also check if abbreviation expansion can be combined with top compound/transliteration
        if (abbrevExpanded != normalized) {
            val abbrevCompound = generateCompoundVariants(abbrevExpanded)
            for (v in abbrevCompound) {
                if (results.size >= 4) break
                results.add(v)
            }
        }

        return results.take(4).toList()
    }

    /**
     * Splits or joins compound words based on known geographic/toponymic suffixes:
     * - "budanagudda" -> "budan gudda", "budangudda"
     * - "budangudda" -> "budan gudda", "budanagudda"
     * - "budan gudda" -> "budanagudda", "budangudda"
     * - "vidyanagar" -> "vidya nagar"
     * - "vidya nagar" -> "vidyanagar"
     */
    private fun generateCompoundVariants(query: String): List<String> {
        val variants = mutableListOf<String>()
        val tokens = query.split(" ").filter { it.isNotBlank() }

        // Case A: Query has a single token that ends in a geographic suffix
        if (tokens.size == 1) {
            val token = tokens[0]
            for (suffix in GEOGRAPHIC_SUFFIXES) {
                if (token.length > suffix.length + 2 && token.endsWith(suffix)) {
                    val stem = token.substring(0, token.length - suffix.length)
                    // If stem ends in 'a' (connective vowel in compounds like budan-a-gudda or root like vidya-nagar)
                    if (stem.endsWith("a") && stem.length > 3) {
                        val baseStem = stem.dropLast(1)
                        variants.add("$stem $suffix")
                        variants.add("$baseStem $suffix")
                        variants.add("$baseStem$suffix")
                    } else {
                        variants.add("$stem $suffix")
                        // If base stem ends in a consonant, connective 'a' variant
                        if (!stem.endsWith("a") && !stem.endsWith("e") && !stem.endsWith("i") && !stem.endsWith("o") && !stem.endsWith("u")) {
                            variants.add("${stem}a$suffix")
                        }
                    }
                    break
                }
            }
        } else {
            // Case B: Query has multiple tokens, check if two adjacent tokens can be compounded
            for (i in 0 until tokens.size - 1) {
                val t1 = tokens[i]
                val t2 = tokens[i + 1]
                if (GEOGRAPHIC_SUFFIXES.contains(t2)) {
                    // direct compound
                    val joinedDirect = buildString {
                        tokens.forEachIndexed { idx, t ->
                            if (idx == i) append("$t1$t2 ")
                            else if (idx != i + 1) append("$t ")
                        }
                    }.trim()
                    variants.add(joinedDirect)

                    // connective 'a' compound (e.g. budan + gudda -> budanagudda)
                    if (!t1.endsWith("a") && !t1.endsWith("e") && !t1.endsWith("i") && !t1.endsWith("o") && !t1.endsWith("u")) {
                        val joinedConnective = buildString {
                            tokens.forEachIndexed { idx, t ->
                                if (idx == i) append("${t1}a$t2 ")
                                else if (idx != i + 1) append("$t ")
                            }
                        }.trim()
                        variants.add(joinedConnective)
                    }
                    break
                }
            }
        }

        return variants
    }

    /**
     * Generates transliteration variations:
     * - "oo" <-> "u" (e.g. "siddharooda" -> "siddharuda")
     * - "dh" <-> "d" (e.g. "siddharudha" -> "siddharuda")
     * - "matta" / "matha" / "mata" <-> "math"
     */
    private fun generateTransliterationVariants(query: String): List<String> {
        val variants = mutableListOf<String>()
        val tokens = query.split(" ").filter { it.isNotBlank() }

        var changed = false
        val transformedTokens = tokens.map { token ->
            var t = token
            when {
                t.contains("oo") -> {
                    t = t.replace("oo", "u")
                    changed = true
                }
                t.contains("u") && !t.contains("gudda") && !t.contains("hub") -> {
                    // Try oo variant only if token is long enough
                    if (t.length >= 6) {
                        t = t.replaceFirst("u", "oo")
                        changed = true
                    }
                }
            }

            if (t.contains("dh")) {
                t = t.replace("dh", "d")
                changed = true
            }

            when (t) {
                "matta", "mata", "matha" -> {
                    t = "math"
                    changed = true
                }
                "math" -> {
                    t = "matha"
                    changed = true
                }
            }
            t
        }

        if (changed) {
            variants.add(transformedTokens.joinToString(" "))
        }

        // Also check if any token was "siddharudha" / "siddharuda" / "siddharooda"
        if (query.contains("siddharooda") || query.contains("siddharudha") || query.contains("siddharuda")) {
            val canonical = query
                .replace("siddharooda", "siddharuda")
                .replace("siddharudha", "siddharuda")
                .replace("matta", "math")
                .replace("matha", "math")
                .replace("mata", "math")
            if (canonical != query && !variants.contains(canonical)) {
                variants.add(canonical)
            }
        }

        return variants
    }

    /**
     * Returns candidate queries starting with the primary normalized query,
     * followed by candidate alias/abbreviation expansions and variants.
     */
    fun getCandidateQueries(rawQuery: String): List<String> {
        val normalized = normalize(rawQuery)
        if (normalized.isBlank()) return emptyList()

        val candidates = LinkedHashSet<String>()
        candidates.add(normalized)

        val alias = PHRASE_ALIASES[normalized.lowercase(Locale.ROOT)]
        if (alias != null) {
            candidates.add(alias)
        }

        val variants = generateVariants(rawQuery)
        candidates.addAll(variants)

        return candidates.toList()
    }

    /**
     * Returns a set of synonyms/equivalents for a given search token
     * to support fuzzy token matching against provider results.
     */
    fun getSynonymsForToken(token: String): Set<String> {
        val t = token.lowercase(Locale.ROOT).trim()
        val set = mutableSetOf(t)
        when (t) {
            "tech", "technological", "technology" -> set.addAll(listOf("tech", "technological", "technology"))
            "stn", "station" -> set.addAll(listOf("stn", "station", "railway"))
            "univ", "uni", "university" -> set.addAll(listOf("univ", "uni", "university"))
            "clg", "college" -> set.addAll(listOf("clg", "college"))
            "rd", "road" -> set.addAll(listOf("rd", "road"))
            "jn", "junc", "junction" -> set.addAll(listOf("jn", "junc", "junction"))
            "hubli", "hubballi" -> set.addAll(listOf("hubli", "hubballi"))
            "kere", "lake", "tank" -> set.addAll(listOf("kere", "lake", "tank"))
            "gudda", "hill", "peak" -> set.addAll(listOf("gudda", "hill", "peak", "betta"))
            "apt", "airport" -> set.addAll(listOf("apt", "airport", "aerodrome"))
            "hwy", "highway" -> set.addAll(listOf("hwy", "highway"))
            "hosp", "hospital" -> set.addAll(listOf("hosp", "hospital", "clinic"))
            "math", "matha", "mata", "matta" -> set.addAll(listOf("math", "matha", "mata", "matta", "temple", "ashram"))
            "siddharuda", "siddharudha", "siddharooda", "siddharoodha" -> set.addAll(listOf("siddharuda", "siddharudha", "siddharooda", "siddharoodha"))
        }
        return set
    }
}

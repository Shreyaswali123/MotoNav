package com.example.data

import java.util.Locale

/**
 * Utility to rank, score, and evaluate place search candidates.
 * Combines:
 * 1. Text relevance (discriminative tokens, generic tokens, prefix matches, variant-query matches)
 * 2. Geographic relevance (proximity bonus and distance penalty when user location is available)
 * 3. Provider information (place type, importance)
 * 4. Local candidate bonus (saved/recents)
 *
 * Implements composite scoring for STRONG results without hardcoding arbitrary radius filters.
 */
object PlaceResultRanker {

    /**
     * Centralized score threshold for classifying a candidate as a "STRONG" result.
     * Tunable from unit tests or runtime configuration.
     */
    const val DEFAULT_STRONG_RESULT_THRESHOLD: Double = 55.0

    val GENERIC_TOKENS = setOf(
        // Honorifics and prefixes
        "shri", "sri", "dr", "saint", "st", "swami", "swamy", "lord",
        // Religious place descriptors
        "mata", "matha", "math", "matta", "temple", "mandir", "masjid", "mosque", "church", "ashram",
        // Facility / civic / transport types
        "hospital", "hosp", "clinic", "college", "clg", "university", "univ", "school", "institute",
        "station", "stn", "railway", "bus", "stand", "stop", "airport", "apt", "aerodrome",
        // Topological / roadway / geographic descriptors
        "circle", "cross", "road", "rd", "street", "st", "lane", "nagar", "layout", "colony",
        "halli", "pura", "pur", "giri", "gudda", "hill", "lake", "kere", "tank", "river",
        "bridge", "flyover", "gate", "junction", "jn", "junc", "bypass", "plaza", "toll",
        "bhavan", "complex", "center", "centre", "hotel", "restaurant", "cafe", "park", "garden", "market", "bazaar"
    )

    private val HIGH_PRIORITY_TYPES = setOf(
        "university", "college", "school", "station", "railway_station",
        "aerodrome", "airport", "bus_station", "hospital",
        "attraction", "monument", "lake", "water", "reservoir",
        "city", "town", "stadium", "marketplace", "viewpoint"
    )

    private val MEDIUM_PRIORITY_TYPES = setOf(
        "suburb", "neighbourhood", "village", "residential",
        "fuel", "parking", "hotel", "guest_house", "commercial"
    )

    /**
     * Inspects whether the query contains an explicit geographic qualifier (e.g. city, district,
     * state, region, or country) matching this candidate location's address or geographic hierarchy.
     *
     * Distinguishes:
     * A. Nearby / ambiguous / local generic searches (e.g. "gokul road", "kims hospital", "vidyanagar", "clock tower")
     *    where distant exact matches must not suppress fallback.
     * B. Explicit distant destination searches (e.g. "delhi airport", "bangalore station", "mumbai hospital", "goa beach")
     *    where the rider explicitly requested a remote destination.
     */
    fun hasExplicitGeographicQualifier(location: SearchLocation, rawQuery: String): Boolean {
        val cleanQuery = PlaceQueryNormalizer.normalize(rawQuery).lowercase(Locale.ROOT)
        val queryTokens = cleanQuery.split(" ").filter { it.isNotBlank() && !GENERIC_TOKENS.contains(it) }
        if (queryTokens.isEmpty()) return false

        val addressLower = location.address.lowercase(Locale.ROOT)
        val addressTokens = addressLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }.toSet()

        val nameLower = location.name.lowercase(Locale.ROOT)
        val nameTokens = nameLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }.toSet()

        return queryTokens.any { qToken ->
            val inAddress = addressTokens.contains(qToken) || addressTokens.any { it.startsWith(qToken) && qToken.length >= 4 }
            val inName = nameTokens.contains(qToken) || nameTokens.any { it.startsWith(qToken) && qToken.length >= 4 }
            // A token is an explicit geographic qualifier if it matches address components,
            // or if the query contains multiple tokens and at least one matches the address
            inAddress && (!inName || queryTokens.size >= 2)
        }
    }

    /**
     * Determines whether a candidate location qualifies as a "STRONG" result
     * based on its composite relevance score.
     *
     * Invariant: A distant candidate (> 150 km) cannot be classified as STRONG
     * for an ambiguous/generic query without explicit distant geographic qualification,
     * preventing distant candidates from suppressing Nominatim fallback while still
     * keeping them fully discoverable in the search candidate pool.
     */
    fun isStrongResult(
        location: SearchLocation,
        query: String,
        userLatitude: Double? = null,
        userLongitude: Double? = null,
        threshold: Double = DEFAULT_STRONG_RESULT_THRESHOLD
    ): Boolean {
        val score = calculateScore(location, query, userLatitude, userLongitude)
        if (score < threshold) return false

        // If user coordinates are provided and candidate is distant (> 150 km)
        if (userLatitude != null && userLongitude != null &&
            userLatitude.isFinite() && userLongitude.isFinite() &&
            location.latitude.isFinite() && location.longitude.isFinite()
        ) {
            val distKm = distanceBetweenKm(userLatitude, userLongitude, location.latitude, location.longitude)
            if (distKm > 150.0) {
                // A distant candidate is only "STRONG" (suppressing fallback) if the query
                // explicitly contains a geographic qualifier for that distant location
                // (e.g. "delhi airport", "bangalore airport", "goa airport").
                // For ambiguous/generic queries (e.g. "gokul road", "kims hospital", "vidyanagar"),
                // distant candidates must not automatically suppress fallback.
                return hasExplicitGeographicQualifier(location, query)
            }
        }

        return true
    }

    /**
     * Checks if a list of locations contains at least one STRONG result at the top.
     */
    fun hasStrongResult(
        locations: List<SearchLocation>,
        query: String,
        userLatitude: Double? = null,
        userLongitude: Double? = null,
        threshold: Double = DEFAULT_STRONG_RESULT_THRESHOLD
    ): Boolean {
        if (locations.isEmpty()) return false
        val top = locations.first()
        return isStrongResult(top, query, userLatitude, userLongitude, threshold)
    }

    /**
     * Ranks and sorts candidate locations based on composite relevance score.
     */
    fun rankResults(
        locations: List<SearchLocation>,
        query: String,
        userLatitude: Double? = null,
        userLongitude: Double? = null
    ): List<SearchLocation> {
        if (locations.size <= 1) return locations

        return locations.sortedWith(
            compareByDescending<SearchLocation> { loc ->
                calculateScore(loc, query, userLatitude, userLongitude)
            }.thenByDescending { it.importance }
        )
    }

    /**
     * Calculates the composite relevance score for a given candidate location.
     */
    fun calculateScore(
        location: SearchLocation,
        rawQuery: String,
        userLatitude: Double? = null,
        userLongitude: Double? = null
    ): Double {
        val cleanQuery = PlaceQueryNormalizer.normalize(rawQuery).lowercase()
        val queryTokens = cleanQuery.split(" ").filter { it.isNotBlank() }
        val querySynonyms = queryTokens.map { PlaceQueryNormalizer.getSynonymsForToken(it) }
        val queryVariants = PlaceQueryNormalizer.generateVariants(rawQuery)

        return calculateScoreInternal(
            location,
            cleanQuery,
            queryTokens,
            querySynonyms,
            queryVariants,
            userLatitude,
            userLongitude
        )
    }

    /**
     * Overload for backwards-compatibility with existing tests.
     */
    fun calculateScore(
        location: SearchLocation,
        cleanQuery: String,
        queryTokens: List<String>,
        querySynonyms: List<Set<String>>,
        userLatitude: Double?,
        userLongitude: Double?
    ): Double {
        val queryVariants = PlaceQueryNormalizer.generateVariants(cleanQuery)
        return calculateScoreInternal(
            location,
            cleanQuery,
            queryTokens,
            querySynonyms,
            queryVariants,
            userLatitude,
            userLongitude
        )
    }

    private fun calculateScoreInternal(
        location: SearchLocation,
        cleanQuery: String,
        queryTokens: List<String>,
        querySynonyms: List<Set<String>>,
        queryVariants: List<String>,
        userLatitude: Double?,
        userLongitude: Double?
    ): Double {
        var score = 0.0

        val nameLower = location.name.lowercase()
        val addressLower = location.address.lowercase()
        val nameTokens = nameLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
        val addressTokens = addressLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }

        // A. TEXT RELEVANCE
        // 1. Phrase matching
        if (nameLower == cleanQuery) {
            score += 70.0
        } else if (nameLower.contains(cleanQuery) || (cleanQuery.length >= 6 && cleanQuery.contains(nameLower))) {
            score += 40.0
        } else if (addressLower.contains(cleanQuery)) {
            score += 20.0
        }

        // 2. Query variant match (e.g. compound split "budan gudda" matching "Budan Gudda")
        val matchesVariant = queryVariants.any { variant ->
            val v = variant.lowercase()
            v != cleanQuery && (nameLower == v || nameLower.contains(v) || v.contains(nameLower))
        }
        if (matchesVariant) {
            score += 25.0
        }

        // 3. Discriminative vs Generic Token Matching
        var matchedDiscriminativeCount = 0
        var totalDiscriminativeTokens = 0
        var matchedGenericCount = 0

        for (i in queryTokens.indices) {
            val qToken = queryTokens[i]
            val synonyms = querySynonyms.getOrElse(i) { setOf(qToken) }
            val isDiscriminative = !GENERIC_TOKENS.contains(qToken)

            if (isDiscriminative) {
                totalDiscriminativeTokens++
            }

            val inNameExact = nameTokens.contains(qToken)
            val inNameSynonym = nameTokens.any { token -> synonyms.contains(token) }
            val inNamePrefix = nameTokens.any { token ->
                (token.startsWith(qToken) && qToken.length >= 3) ||
                (qToken.startsWith(token) && token.length >= 4)
            }

            val inAddrExact = addressTokens.contains(qToken)
            val inAddrSynonym = addressTokens.any { token -> synonyms.contains(token) }
            val inAddrPrefix = addressTokens.any { token ->
                (token.startsWith(qToken) && qToken.length >= 3) ||
                (qToken.startsWith(token) && token.length >= 4)
            }

            when {
                inNameExact -> {
                    score += if (isDiscriminative) 40.0 else 12.0
                    if (isDiscriminative) matchedDiscriminativeCount++ else matchedGenericCount++
                }
                inNameSynonym -> {
                    score += if (isDiscriminative) 35.0 else 10.0
                    if (isDiscriminative) matchedDiscriminativeCount++ else matchedGenericCount++
                }
                inNamePrefix -> {
                    score += if (isDiscriminative) 28.0 else 8.0
                    if (isDiscriminative) matchedDiscriminativeCount++ else matchedGenericCount++
                }
                inAddrExact -> {
                    score += if (isDiscriminative) 16.0 else 6.0
                    if (isDiscriminative) matchedDiscriminativeCount++ else matchedGenericCount++
                }
                inAddrSynonym -> {
                    score += if (isDiscriminative) 14.0 else 5.0
                    if (isDiscriminative) matchedDiscriminativeCount++ else matchedGenericCount++
                }
                inAddrPrefix -> {
                    score += if (isDiscriminative) 10.0 else 4.0
                    if (isDiscriminative) matchedDiscriminativeCount++ else matchedGenericCount++
                }
            }
        }

        // Bonus if all discriminative tokens matched
        if (totalDiscriminativeTokens > 0 && matchedDiscriminativeCount >= totalDiscriminativeTokens) {
            score += 20.0
        } else if (totalDiscriminativeTokens == 0 && matchedGenericCount == queryTokens.size && queryTokens.isNotEmpty()) {
            score += 15.0
        }

        // B. GEOGRAPHIC RELEVANCE (Proximity bonus & Distance penalty)
        if (userLatitude != null && userLongitude != null &&
            userLatitude.isFinite() && userLongitude.isFinite() &&
            location.latitude.isFinite() && location.longitude.isFinite()
        ) {
            val distKm = distanceBetweenKm(userLatitude, userLongitude, location.latitude, location.longitude)
            when {
                distKm < 10.0 -> score += 25.0
                distKm < 30.0 -> score += 18.0
                distKm < 60.0 -> score += 12.0
                distKm < 120.0 -> score += 6.0
            }

            // For ambiguous queries with distant matches, apply distance penalty.
            // But if the query explicitly targeted this distant region (e.g. "delhi airport", "bangalore station"),
            // do not penalize distance; the rider deliberately requested that distant location.
            if (distKm > 150.0) {
                if (hasExplicitGeographicQualifier(location, cleanQuery)) {
                    score += 10.0
                } else {
                    when {
                        distKm in 150.0..300.0 -> score -= 15.0
                        distKm in 300.0..600.0 -> score -= 30.0
                        distKm > 600.0 -> score -= 55.0
                    }
                }
            }
        }

        // C. PROVIDER INFORMATION
        // Nominatim / Photon importance signal (0.0 to 1.0)
        score += location.importance * 15.0

        // Place Type priority
        val typeLower = location.placeType.lowercase()
        if (HIGH_PRIORITY_TYPES.contains(typeLower)) {
            score += 8.0
        } else if (MEDIUM_PRIORITY_TYPES.contains(typeLower)) {
            score += 4.0
        }

        // D. LOCAL CANDIDATE BONUS
        // Enough to surface saved/recents quickly, but not overwhelming better remote candidates
        if (location.id.startsWith("local_") || location.id.startsWith("curated_") || location.id.startsWith("recent_")) {
            score += 15.0
        }

        return score
    }

    internal fun distanceBetweenKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return 6371.0 * c
    }
}

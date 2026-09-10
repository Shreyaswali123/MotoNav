package com.example.data

/**
 * Utility to rank and sort Nominatim candidate places based on:
 * - Query token relevance (exact, prefix, and synonym matches in place name & address)
 * - Nominatim importance signal
 * - Place type priority (major transportation, educational, civic landmarks vs minor points)
 * - Proximity if reference/current location coordinates are provided
 *
 * Preserves all valid results without aggressively filtering out candidates.
 */
object PlaceResultRanker {

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
     * Ranks and sorts candidate locations based on relevance signals.
     */
    fun rankResults(
        locations: List<SearchLocation>,
        query: String,
        userLatitude: Double? = null,
        userLongitude: Double? = null
    ): List<SearchLocation> {
        if (locations.size <= 1) return locations

        val cleanQuery = PlaceQueryNormalizer.normalize(query).lowercase()
        val queryTokens = cleanQuery.split(" ").filter { it.isNotBlank() }
        val querySynonyms = queryTokens.map { PlaceQueryNormalizer.getSynonymsForToken(it) }

        return locations.sortedWith(
            compareByDescending<SearchLocation> { loc ->
                calculateScore(loc, cleanQuery, queryTokens, querySynonyms, userLatitude, userLongitude)
            }.thenByDescending { it.importance }
        )
    }

    /**
     * Calculates a relevance score for a given candidate location.
     */
    fun calculateScore(
        location: SearchLocation,
        cleanQuery: String,
        queryTokens: List<String>,
        querySynonyms: List<Set<String>>,
        userLatitude: Double?,
        userLongitude: Double?
    ): Double {
        var score = 0.0

        val nameLower = location.name.lowercase()
        val addressLower = location.address.lowercase()
        val nameTokens = nameLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
        val addressTokens = addressLower.split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }

        // 1. Exact phrase match
        if (nameLower == cleanQuery) {
            score += 100.0
        } else if (nameLower.contains(cleanQuery)) {
            score += 50.0
        } else if (addressLower.contains(cleanQuery)) {
            score += 25.0
        }

        // 2. Token matches (exact, synonym, and prefix matches)
        var matchedTokensCount = 0
        for (i in queryTokens.indices) {
            val qToken = queryTokens[i]
            val synonyms = querySynonyms.getOrElse(i) { setOf(qToken) }

            val inNameExact = nameTokens.contains(qToken)
            val inNameSynonym = nameTokens.any { token -> synonyms.contains(token) }
            val inNamePrefix = nameTokens.any { it.startsWith(qToken) || qToken.startsWith(it) }

            val inAddrExact = addressTokens.contains(qToken)
            val inAddrSynonym = addressTokens.any { token -> synonyms.contains(token) }
            val inAddrPrefix = addressTokens.any { it.startsWith(qToken) || qToken.startsWith(it) }

            when {
                inNameExact -> {
                    score += 20.0
                    matchedTokensCount++
                }
                inNameSynonym -> {
                    score += 18.0
                    matchedTokensCount++
                }
                inNamePrefix -> {
                    score += 12.0
                    matchedTokensCount++
                }
                inAddrExact -> {
                    score += 10.0
                    matchedTokensCount++
                }
                inAddrSynonym -> {
                    score += 8.0
                    matchedTokensCount++
                }
                inAddrPrefix -> {
                    score += 6.0
                    matchedTokensCount++
                }
            }
        }

        // Bonus if all query tokens were matched somewhere in name or address
        if (queryTokens.isNotEmpty() && matchedTokensCount >= queryTokens.size) {
            score += 30.0
        }

        // 3. Nominatim Importance signal (importance is typically 0.0 to 1.0)
        score += location.importance * 15.0

        // 4. Place Type signal from Nominatim
        val typeLower = location.placeType.lowercase()
        if (HIGH_PRIORITY_TYPES.contains(typeLower)) {
            score += 8.0
        } else if (MEDIUM_PRIORITY_TYPES.contains(typeLower)) {
            score += 4.0
        }

        // 5. Proximity signal (if user location or start point is known)
        if (userLatitude != null && userLongitude != null &&
            userLatitude.isFinite() && userLongitude.isFinite() &&
            location.latitude.isFinite() && location.longitude.isFinite()
        ) {
            val distKm = distanceBetweenKm(userLatitude, userLongitude, location.latitude, location.longitude)
            if (distKm < 50.0) {
                score += (50.0 - distKm) / 5.0 // up to 10.0 points boost
            }
        }

        return score
    }

    private fun distanceBetweenKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return 6371.0 * c
    }
}

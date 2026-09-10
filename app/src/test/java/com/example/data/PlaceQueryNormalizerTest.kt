package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceQueryNormalizerTest {

    @Test
    fun testTrimsAndCollapsesSpaces() {
        val result = PlaceQueryNormalizer.normalize("   KLE    Technological    University   ")
        assertEquals("KLE Technological University", result)
    }

    @Test
    fun testPunctuationDifferencesAndAcronymDots() {
        assertEquals("KLE Tech", PlaceQueryNormalizer.normalize("K.L.E. Tech!"))
        assertEquals("Tolan Kere", PlaceQueryNormalizer.normalize("Tolan-Kere?"))
        assertEquals("KLE Tech Hubballi", PlaceQueryNormalizer.normalize("KLE Tech, Hubballi"))
    }

    @Test
    fun testCamelCaseSplitting() {
        assertEquals("Tolan Kere", PlaceQueryNormalizer.normalize("TolanKere"))
        assertEquals("Tolan Kere Hubli", PlaceQueryNormalizer.normalize("TolanKere Hubli"))
    }

    @Test
    fun testCandidateQueriesGeneration() {
        val candidates = PlaceQueryNormalizer.getCandidateQueries("KLE Tech")
        assertTrue("Primary query should be first candidate", candidates.isNotEmpty())
        assertEquals("KLE Tech", candidates[0])
        assertTrue(
            "Candidates should include KLE Technological University expansion",
            candidates.any { it.contains("KLE Technological University", ignoreCase = true) }
        )
    }

    @Test
    fun testSynonymsForToken() {
        val techSynonyms = PlaceQueryNormalizer.getSynonymsForToken("tech")
        assertTrue(techSynonyms.contains("technological"))
        assertTrue(techSynonyms.contains("technology"))

        val hubliSynonyms = PlaceQueryNormalizer.getSynonymsForToken("hubli")
        assertTrue(hubliSynonyms.contains("hubballi"))

        val kereSynonyms = PlaceQueryNormalizer.getSynonymsForToken("kere")
        assertTrue(kereSynonyms.contains("lake"))
    }

    @Test
    fun testRankingRanksRelevantCandidatesHigher() {
        val loc1 = SearchLocation(
            id = "1",
            name = "KLE Technological University",
            address = "Vidyanagar, Hubballi",
            latitude = 15.3690,
            longitude = 75.1236,
            importance = 0.7,
            placeType = "university"
        )
        val loc2 = SearchLocation(
            id = "2",
            name = "Tech Shop",
            address = "Random Street, Hubballi",
            latitude = 15.3500,
            longitude = 75.1300,
            importance = 0.1,
            placeType = "shop"
        )

        val ranked = PlaceResultRanker.rankResults(listOf(loc2, loc1), "KLE Tech")
        assertEquals("KLE Technological University should rank first", "KLE Technological University", ranked[0].name)
    }

    @Test
    fun testRankingPreservesAllCandidates() {
        val loc1 = SearchLocation(id = "1", name = "Place A", address = "Address A", latitude = 15.0, longitude = 75.0)
        val loc2 = SearchLocation(id = "2", name = "Place B", address = "Address B", latitude = 16.0, longitude = 76.0)
        val loc3 = SearchLocation(id = "3", name = "Place C", address = "Address C", latitude = 17.0, longitude = 77.0)

        val ranked = PlaceResultRanker.rankResults(listOf(loc1, loc2, loc3), "Query")
        assertEquals(3, ranked.size)
        assertTrue(ranked.contains(loc1))
        assertTrue(ranked.contains(loc2))
        assertTrue(ranked.contains(loc3))
    }
}

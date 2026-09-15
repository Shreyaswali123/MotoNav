package com.example.benchmark

object SearchBenchmarkDataset {

    const val HUBBALLI_CENTER_LAT = 15.3647
    const val HUBBALLI_CENTER_LON = 75.1240

    // Ground truth targets for Hubballi geography
    val TARGET_SIDDHAROODHA_MATH = ExpectedTarget(
        nameKeywords = listOf("siddharoodha", "siddharuda", "siddharudha", "math", "mutt"),
        latitude = 15.3358,
        longitude = 75.1186,
        maxAcceptableDistanceKm = 10.0
    )

    val TARGET_CHANDRAMOULESHWARA = ExpectedTarget(
        nameKeywords = listOf("chandramouleshwara", "chandramoulesvara", "chandramowleshwara", "temple"),
        latitude = 15.3787,
        longitude = 75.1189,
        maxAcceptableDistanceKm = 10.0
    )

    val TARGET_UNKAL_LAKE = ExpectedTarget(
        nameKeywords = listOf("unkal", "lake"),
        latitude = 15.3785,
        longitude = 75.1168,
        maxAcceptableDistanceKm = 10.0
    )

    val TARGET_KIMS_HOSPITAL = ExpectedTarget(
        nameKeywords = listOf("kims", "hospital"),
        latitude = 15.3614,
        longitude = 75.1326,
        maxAcceptableDistanceKm = 10.0
    )

    val TARGET_GOKUL_ROAD = ExpectedTarget(
        nameKeywords = listOf("gokul", "road"),
        latitude = 15.3541,
        longitude = 75.1308,
        maxAcceptableDistanceKm = 10.0
    )

    val TARGET_VIDYANAGAR = ExpectedTarget(
        nameKeywords = listOf("vidyanagar", "vidya nagar"),
        latitude = 15.3638,
        longitude = 75.1276,
        maxAcceptableDistanceKm = 10.0
    )

    val QUERIES: List<BenchmarkQueryCase> = listOf(
        // 1. Required Query 1: "shri siddharuda mata"
        BenchmarkQueryCase(
            id = "Q01",
            query = "shri siddharuda mata",
            category = "required_math",
            expectedTarget = TARGET_SIDDHAROODHA_MATH
        ),
        // 2. Required Query 2: "siddharuda mata"
        BenchmarkQueryCase(
            id = "Q02",
            query = "siddharuda mata",
            category = "required_math",
            expectedTarget = TARGET_SIDDHAROODHA_MATH
        ),
        // 3. Required Query 3: "siddharudha math"
        BenchmarkQueryCase(
            id = "Q03",
            query = "siddharudha math",
            category = "required_math",
            expectedTarget = TARGET_SIDDHAROODHA_MATH
        ),
        // 4. Required Query 4: "budan gudda"
        BenchmarkQueryCase(
            id = "Q04",
            query = "budan gudda",
            category = "required_budan",
            expectedTarget = null // No hardcoded unverified ground truth
        ),
        // 5. Common temple query
        BenchmarkQueryCase(
            id = "Q05",
            query = "chandramouleshwara temple",
            category = "temple",
            expectedTarget = TARGET_CHANDRAMOULESHWARA
        ),
        // 6. Landmark query
        BenchmarkQueryCase(
            id = "Q06",
            query = "unkal lake",
            category = "landmark",
            expectedTarget = TARGET_UNKAL_LAKE
        ),
        // 7. Hospital query
        BenchmarkQueryCase(
            id = "Q07",
            query = "kims hospital",
            category = "hospital",
            expectedTarget = TARGET_KIMS_HOSPITAL
        ),
        // 8. Road query
        BenchmarkQueryCase(
            id = "Q08",
            query = "gokul road",
            category = "road",
            expectedTarget = TARGET_GOKUL_ROAD
        ),
        // 9. Neighborhood/locality query
        BenchmarkQueryCase(
            id = "Q09",
            query = "vidyanagar",
            category = "locality",
            expectedTarget = TARGET_VIDYANAGAR
        ),
        // 10. Partial-name query
        BenchmarkQueryCase(
            id = "Q10",
            query = "siddharud",
            category = "partial_name",
            expectedTarget = TARGET_SIDDHAROODHA_MATH
        ),
        // 11. Deliberately misspelled query
        BenchmarkQueryCase(
            id = "Q11",
            query = "siddharooda matta",
            category = "misspelled",
            expectedTarget = TARGET_SIDDHAROODHA_MATH
        ),
        // 12. Mixed case and extra whitespace query
        BenchmarkQueryCase(
            id = "Q12",
            query = "  ShRi   SiDdhArUdA  mAtA  ",
            category = "whitespace_casing",
            expectedTarget = TARGET_SIDDHAROODHA_MATH
        ),
        // 13. Same-name-place query (tests geographic disambiguation)
        BenchmarkQueryCase(
            id = "Q13",
            query = "clock tower",
            category = "same_name_disambiguation",
            expectedTarget = null // Multi-city concept, tested for geographic bias differentiation
        )
    )
}

package com.example.benchmark

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchBenchmarkLiveTest {

    @Test
    fun executeLiveBenchmark() {
        val flagFile = java.io.File("/tmp/run_live_benchmark")
        val shouldRun = System.getProperty("runLiveBenchmark") == "true" ||
            System.getenv("RUN_LIVE_BENCHMARK") == "true" ||
            flagFile.exists()

        assumeTrue(
            "Live benchmark is skipped in default CI/unit tests to avoid public API rate limits. " +
                "Run with -DrunLiveBenchmark=true or create /tmp/run_live_benchmark to execute.",
            shouldRun
        )

        SearchBenchmarkLiveRunner.main(emptyArray())
    }
}

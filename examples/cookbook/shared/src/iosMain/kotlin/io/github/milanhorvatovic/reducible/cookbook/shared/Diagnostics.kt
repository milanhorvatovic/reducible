package io.github.milanhorvatovic.reducible.cookbook.shared

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * Forces a Kotlin/Native collection and reports what it did. Kotlin objects released by Swift
 * stay allocated — and keep their ObjC wrappers alive — until the next collection, which an
 * idle app may not run for a long time; a memory graph taken before one shows garbage as
 * retention. The returned line is meant for the debug log, so the effect of a collection is
 * visible without a profiler. Debug tooling only.
 */
@OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)
public fun collectGarbage(): String {
    GC.collect()
    val info = GC.lastGCInfo ?: return "Kotlin GC ran; no statistics available"
    val before = info.memoryUsageBefore.values.sumOf { usage -> usage.totalObjectsSizeBytes }
    val after = info.memoryUsageAfter.values.sumOf { usage -> usage.totalObjectsSizeBytes }
    val swept = info.sweepStatistics.values.sumOf { sweep -> sweep.sweptCount }
    val kept = info.sweepStatistics.values.sumOf { sweep -> sweep.keptCount }
    val millis = (info.endTimeNs - info.startTimeNs) / 1_000_000
    return "Kotlin GC #${info.epoch}: heap ${before / 1024} KB to ${after / 1024} KB, swept $swept objects, kept $kept, $millis ms"
}

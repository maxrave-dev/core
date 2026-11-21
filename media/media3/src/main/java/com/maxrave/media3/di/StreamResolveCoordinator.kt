package com.maxrave.media3.di

import com.maxrave.common.MERGING_DATA_TYPE
import kotlinx.coroutines.Job

/**
 * Coordinates in-flight blocking stream resolves in ResolvingDataSource / HlsMediaSource so
 * skipped or evicted players can cancel their abandoned network fetches immediately instead of
 * holding OkHttp's per-host dispatcher slots until the 20s timeout elapses.
 */
class StreamResolveCoordinator {
    private val lock = Any()
    private val activeJobs = HashMap<String, MutableSet<Job>>()

    fun register(
        rawId: String,
        job: Job,
    ): () -> Unit {
        val key = rawId.removePrefix(MERGING_DATA_TYPE.VIDEO)
        synchronized(lock) {
            activeJobs.getOrPut(key) { HashSet() }.add(job)
        }
        return {
            synchronized(lock) {
                val jobs = activeJobs[key]
                if (jobs != null) {
                    jobs.remove(job)
                    if (jobs.isEmpty()) {
                        activeJobs.remove(key)
                    }
                }
            }
        }
    }

    fun cancel(rawId: String) {
        val key = rawId.removePrefix(MERGING_DATA_TYPE.VIDEO)
        val toCancel =
            synchronized(lock) {
                activeJobs.remove(key)?.toList()
            } ?: return
        toCancel.forEach { it.cancel() }
    }
}

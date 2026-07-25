package com.marki19.domain.jam

/**
 * Jam queue and video IDs sometimes carry a URL-style prefix (e.g. a full path)
 * while other parts of the app use the bare ID. This is the ONE place that
 * strips it — every ID comparison in Jam code should go through this.
 */
fun String.cleanId(): String = substringAfterLast('/')

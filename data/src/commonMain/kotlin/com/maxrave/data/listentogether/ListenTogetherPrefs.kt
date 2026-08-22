package com.maxrave.data.listentogether

/**
 * DataStore keys for Listen Together.
 *
 * They live here rather than next to the settings screen because both sides need them: the screen
 * writes them, and the DI module that builds the client reads the server URL before any screen
 * exists. Two copies of these strings would drift silently — the setting would appear to save and
 * the client would keep using the default.
 */
object ListenTogetherPrefs {
    const val SERVER_URL = "lt_server_url"
    const val AUTO_APPROVE_JOINS = "lt_auto_approve_joins"
    const val AUTO_APPROVE_SUGGESTIONS = "lt_auto_approve_suggestions"
    const val FOLLOW_HOST_VOLUME = "lt_follow_host_volume"
    const val BLOCKLIST = "lt_blocklist"

    const val TRUE = "TRUE"
    const val FALSE = "FALSE"

    /** `\n` is the delimiter because it is the one character a username cannot contain. */
    const val BLOCKLIST_SEPARATOR = "\n"
}

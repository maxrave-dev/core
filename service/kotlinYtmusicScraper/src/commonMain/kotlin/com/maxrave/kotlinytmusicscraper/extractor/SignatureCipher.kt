package com.maxrave.kotlinytmusicscraper.extractor

import io.ktor.http.URLBuilder
import io.ktor.http.parseQueryString

/**
 * The pieces of one format's `signatureCipher` — the obfuscated signature, the query parameter
 * it must travel under once solved, the plain stream URL it belongs to, and that URL's own
 * throttling parameter (`n`), which has to be solved separately.
 *
 * A cipher is a query string of its own (`s=…&sp=sig&url=…`), so the parsing is the same one
 * `YouTube.getNParam` already does when it only needs the `n` out of a response.
 */
internal class SignatureCipherParts(
    val signature: String,
    val signatureParameter: String,
    val baseUrl: String,
    val nParameter: String?,
)

/**
 * Split a raw `signatureCipher` into its [SignatureCipherParts], or null when it does not parse
 * — a cipher without a signature or without its URL can never be turned into a stream URL, and
 * the caller treats that as "this candidate cannot be solved here".
 */
internal fun parseSignatureCipher(cipher: String): SignatureCipherParts? {
    val params = parseQueryString(cipher)
    val signature = params["s"] ?: return null
    val baseUrl = params["url"] ?: return null
    val nParameter =
        runCatching { URLBuilder(baseUrl).parameters["n"] }.getOrNull()
    return SignatureCipherParts(
        signature = signature,
        signatureParameter = params["sp"] ?: "sig",
        baseUrl = baseUrl,
        nParameter = nParameter,
    )
}

/**
 * The playable URL for a cipher whose challenges were solved: the base URL carrying the solved
 * signature under its own parameter name and, when the URL had one, the solved `n` in place of
 * the obfuscated one. Assembled the way `NewPipeUtils.getStreamUrl` assembles it for the old
 * PipePipe manager — `URLBuilder` encodes the values, so a signature is never spliced in raw.
 */
internal fun SignatureCipherParts.assembleUrl(
    decodedSignature: String,
    decodedNParameter: String?,
): String {
    val builder = URLBuilder(baseUrl)
    builder.parameters[signatureParameter] = decodedSignature
    if (decodedNParameter != null) {
        builder.parameters["n"] = decodedNParameter
    }
    return builder.toString()
}

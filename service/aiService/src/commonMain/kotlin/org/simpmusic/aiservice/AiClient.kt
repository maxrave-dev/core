package org.simpmusic.aiservice

import com.aallam.openai.api.exception.OpenAIAPIException
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.taste.TasteException
import com.maxrave.domain.data.model.taste.TasteInput
import com.maxrave.domain.data.model.taste.TasteReading
import kotlin.coroutines.cancellation.CancellationException

class AiClient {
    private var aiService: AiService? = null
    var host = AIHost.GEMINI
        set(value) {
            field = value
            rebuildAiService()
        }
    var apiKey: String? = null
        set(value) {
            field = value
            rebuildAiService()
        }
    var customModelId: String? = null
        set(value) {
            field = value
            rebuildAiService()
        }
    var customBaseUrl: String? = null
        set(value) {
            field = value
            rebuildAiService()
        }
    var customHeaders: Map<String, String>? = null
        set(value) {
            field = value
            rebuildAiService()
        }

    private fun rebuildAiService() {
        aiService =
            if (apiKey != null) {
                AiService(
                    aiHost = host,
                    apiKey = apiKey!!,
                    customModelId = customModelId,
                    customBaseUrl = customBaseUrl,
                    customHeaders = customHeaders,
                )
            } else {
                null
            }
    }

    suspend fun translateLyrics(
        inputLyrics: Lyrics,
        targetLanguage: String,
    ): Result<Lyrics> =
        runCatching {
            val result =
                aiService?.translateLyrics(inputLyrics, targetLanguage)
                    ?: throw IllegalStateException("AI service is not initialized. Please set host and apiKey.")

            // Validate: check that at least some lines were actually translated
            val originalWords = inputLyrics.lines?.map { it.words } ?: emptyList()
            val translatedWords = result.lines?.map { it.words } ?: emptyList()
            val unchangedCount = originalWords.zip(translatedWords).count { (orig, trans) -> orig == trans }
            val translatableCount = originalWords.count { it.trim().isNotEmpty() && it.trim() != "♫" }

            // Reject if >80% of translatable lines are unchanged (likely same language or translation failed)
            if (translatableCount > 0 && unchangedCount.toFloat() / translatableCount > 0.8f) {
                throw IllegalStateException("Translation failed or returned empty lyrics or same language.")
            }

            result
        }

    /**
     * A reading of the listener's taste.
     *
     * Every failure comes back as a [TasteException] sorted by what the user can do about it: a
     * refused key or model is fixed in Settings, everything else is worth another try. The layers
     * above never see this library's own exception types.
     */
    suspend fun describeTaste(input: TasteInput): Result<TasteReading> {
        val service = aiService ?: return Result.failure(TasteException(TasteException.Kind.NOT_CONFIGURED))
        return try {
            Result.success(service.describeTaste(input))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TasteException) {
            Result.failure(e)
        } catch (e: OpenAIAPIException) {
            val kind = if (e.statusCode in REJECTED_STATUS) TasteException.Kind.REJECTED else TasteException.Kind.TEMPORARY
            Result.failure(TasteException(kind, e.statusCode, e.error.detail?.message ?: e.message, e))
        } catch (e: Exception) {
            Result.failure(TasteException(TasteException.Kind.TEMPORARY, message = e.message, cause = e))
        }
    }

    private companion object {
        /** Bad request, bad key, no access, unknown model: retrying cannot help, Settings can. */
        val REJECTED_STATUS = setOf(400, 401, 403, 404)
    }
}
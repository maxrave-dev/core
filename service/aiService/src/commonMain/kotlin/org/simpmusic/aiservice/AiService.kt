package org.simpmusic.aiservice

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatResponseFormat
import com.aallam.openai.api.chat.JsonSchema
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.taste.TasteException
import com.maxrave.domain.data.model.taste.TasteInput
import com.maxrave.domain.data.model.taste.TasteReading
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.minutes

class AiService(
    private val aiHost: AIHost = AIHost.GEMINI,
    private val apiKey: String,
    private val customModelId: String? = null,
    private val customBaseUrl: String? = null,
    private val customHeaders: Map<String, String>? = null,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    private val openAI: OpenAI by lazy {
        val presetHost = aiHost.presetHost
        if (presetHost != null) {
            OpenAI(host = presetHost, token = apiKey, timeout = REQUEST_TIMEOUT)
        } else {
            val baseUrl = customBaseUrl ?: "https://api.openai.com/v1/"
            val config =
                OpenAIConfig(
                    token = apiKey,
                    timeout = REQUEST_TIMEOUT,
                    host = OpenAIHost(baseUrl = baseUrl),
                    headers = customHeaders ?: emptyMap(),
                )
            OpenAI(config)
        }
    }

    // A preset provider has no default model: Settings marks its model ID as required, and a
    // request without one is reported as setup the user still has to finish.
    private val model by lazy {
        if (!customModelId.isNullOrEmpty()) {
            ModelId(customModelId)
        } else {
            when (aiHost) {
                AIHost.GEMINI -> ModelId("gemini-2.0-flash")
                AIHost.OPENAI, AIHost.CUSTOM_OPENAI -> ModelId("gpt-4o")
                else -> throw TasteException(TasteException.Kind.NOT_CONFIGURED, message = "No model ID set for ${aiHost.name}")
            }
        }
    }

    /**
     * The provider's model IDs, sorted, from its OpenAI-style `GET /models`. Throws when the provider
     * has no such endpoint or answers in another shape; Settings then asks for the ID by hand.
     * Gemini names its models `models/gemini-…` while requests use the bare ID, so the prefix goes.
     */
    suspend fun listModels(): List<String> =
        openAI
            .models()
            .map { if (aiHost == AIHost.GEMINI) it.id.id.removePrefix("models/") else it.id.id }
            .distinct()
            .sorted()

    /**
     * One reading of the listener's taste from [input].
     *
     * Sent without a `responseFormat`, unlike [translateLyrics]: providers reached through the
     * custom OpenAI-compatible path do not all accept `json_schema` (issue #2297 traced DeepSeek's
     * chat endpoint to `json_object` only), and a refused format fails the whole request. The
     * prompt asks for a bare JSON object instead, and [parseTasteReading] reads it leniently.
     */
    suspend fun describeTaste(input: TasteInput): TasteReading {
        val request =
            chatCompletionRequest {
                this.model = this@AiService.model
                messages {
                    system {
                        content = TASTE_SYSTEM_PROMPT
                    }
                    user {
                        content {
                            text(input.toPromptData())
                        }
                    }
                }
            }
        val raw =
            openAI
                .chatCompletion(request)
                .choices
                .firstOrNull()
                ?.message
                ?.content
                ?: throw TasteException(TasteException.Kind.TEMPORARY, message = "Empty response from AI")
        return parseTasteReading(raw)
            ?: throw TasteException(TasteException.Kind.TEMPORARY, message = "Unreadable response from AI")
    }

    suspend fun translateLyrics(
        inputLyrics: Lyrics,
        targetLanguage: String,
    ): Lyrics {
        val lines = inputLyrics.lines ?: throw IllegalStateException("No lyrics lines to translate")

        // Build key-value map: index -> words (only non-empty lines)
        val indexToWords = mutableMapOf<String, String>()
        lines.forEachIndexed { index, line ->
            val words = line.words.trim()
            if (words.isNotEmpty() && words != "♫") {
                indexToWords[index.toString()] = words
            }
        }

        if (indexToWords.isEmpty()) {
            throw IllegalStateException("No translatable lyrics lines found")
        }

        val inputJson = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), indexToWords)

        val request =
            chatCompletionRequest {
                this.model = this@AiService.model
                responseFormat = translationFormat
                messages {
                    system {
                        content =
                            "You are a song lyrics translation assistant.\n" +
                            "\n" +
                            "TASK:\n" +
                            "- You will receive a JSON object where keys are line indices and values are lyrics text.\n" +
                            "- FIRST, detect the dominant language of the input lyrics.\n" +
                            "- If the detected language is the SAME as the target language code, return an EMPTY \"translations\" object. Do NOT translate. Do NOT paraphrase.\n" +
                            "- Otherwise, translate ONLY the values to the target language.\n" +
                            "- When translating: keep ALL keys exactly the same, output MUST have the EXACT same number of entries as the input, do NOT merge/split/add/remove any entries, and preserve the song's meaning, tone, and emotion.\n" +
                            "\n" +
                            "OUTPUT:\n" +
                            translationOutputInstructions
                    }
                    user {
                        content {
                            text("Target language: $targetLanguage")
                        }
                        content {
                            text("Input lyrics: $inputJson")
                        }
                    }
                }
            }
        val completion: ChatCompletion = openAI.chatCompletion(request)
        val jsonContent =
            completion.choices
                .firstOrNull()
                ?.message
                ?.content ?: throw IllegalStateException("No response from AI")
        val jsonData =
            Regex(
                "```json\\s*([\\s\\S]*?)```",
            ).find(jsonContent)
                ?.groups
                ?.firstOrNull()
                ?.value ?: jsonContent
        val cleanedJson = jsonData.replace("```json", "").replace("```", "")
        val translatedMap = parseTranslations(cleanedJson)
        if (translatedMap.isEmpty()) {
            throw IllegalStateException(
                "Input lyrics are already in the target language ($targetLanguage). Translation aborted.",
            )
        }

        // Map translated text back to original lines, preserving all timestamps
        val translatedLines = lines.mapIndexed { index, originalLine ->
            val translatedWords = translatedMap[index.toString()]
            if (translatedWords != null) {
                Line(
                    startTimeMs = originalLine.startTimeMs,
                    endTimeMs = originalLine.endTimeMs,
                    words = translatedWords,
                    syllables = null,
                )
            } else {
                // Non-translatable line (empty or ♫): keep original
                Line(
                    startTimeMs = originalLine.startTimeMs,
                    endTimeMs = originalLine.endTimeMs,
                    words = originalLine.words,
                    syllables = originalLine.syllables,
                )
            }
        }

        return Lyrics(
            error = false,
            lines = translatedLines,
            syncType = inputLyrics.syncType,
        )
    }

    /**
     * The output format each provider accepts, per its own documentation (checked 2026-11-23), so
     * the reply is always JSON that [parseTranslations] can read. Anthropic's OpenAI layer takes only
     * `json_schema` with `strict: true`, and strict schemas cannot have open keys, so it gets the
     * key/value list. DeepSeek takes only `json_object`; Groq, Mistral, xAI, Fireworks, Cerebras
     * and OpenRouter document it for every model, while `json_schema` is per model on most of
     * them. Together documents `json_schema` only; the custom endpoint keeps the format it had.
     */
    private val translationFormat: ChatResponseFormat
        get() =
            when (aiHost) {
                AIHost.GEMINI, AIHost.OPENAI, AIHost.CUSTOM_OPENAI, AIHost.TOGETHER -> {
                    ChatResponseFormat.jsonSchema(aiResponseJsonSchema)
                }

                AIHost.ANTHROPIC -> {
                    ChatResponseFormat.jsonSchema(strictResponseJsonSchema)
                }

                AIHost.DEEPSEEK, AIHost.GROQ, AIHost.MISTRAL, AIHost.XAI, AIHost.FIREWORKS,
                AIHost.CEREBRAS, AIHost.OPENROUTER,
                -> {
                    ChatResponseFormat.JsonObject
                }
            }

    /**
     * The OUTPUT part of the translation prompt, matching [translationFormat]. DeepSeek's JSON mode
     * asks for an example of the reply in the prompt, Together asks for the schema itself in the
     * system prompt, and the strict schema returns a list rather than a map, so its prompt says so.
     */
    private val translationOutputInstructions: String
        get() =
            when (aiHost) {
                AIHost.ANTHROPIC -> {
                    "- A JSON object whose \"translations\" field is a list of {\"key\": line index, \"value\": translated text} " +
                        "pairs, one per input entry (or an empty list when the input is already in the target language).\n" +
                        "- Example: {\"translations\": [{\"key\": \"0\", \"value\": \"First line\"}, {\"key\": \"2\", \"value\": \"Third line\"}]}"
                }

                else -> {
                    "- A JSON object with the \"translations\" field containing the same keys mapped to translated values " +
                        "(or an empty object when the input is already in the target language).\n" +
                        "- Example: {\"translations\": {\"0\": \"First line\", \"2\": \"Third line\"}}" +
                        if (aiHost == AIHost.TOGETHER) "\n- JSON Schema of the reply: $translationJsonSchema" else ""
                }
            }

    /**
     * The `translations` of a reply as line index → text: an object of them (the open schema and
     * `json_object`), or the list of key/value pairs the strict schema asks for.
     */
    private fun parseTranslations(raw: String): Map<String, String> =
        when (val translations = (json.parseToJsonElement(raw) as? JsonObject)?.get("translations")) {
            is JsonObject -> {
                translations.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }.toMap()
            }

            is JsonArray -> {
                translations
                    .mapNotNull { item ->
                        val pair = item as? JsonObject ?: return@mapNotNull null
                        val key = (pair["key"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        val value = (pair["value"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                        key to value
                    }.toMap()
            }

            else -> {
                emptyMap()
            }
        }

    companion object {
        /**
         * How long a request may go without a byte coming back. The library's default is 30 s,
         * which a model that thinks before answering — or a router in front of one — exceeds on an
         * open-ended prompt like the taste reading, and the request then dies with
         * "Socket timeout has expired" while the provider is still working on it.
         */
        private val REQUEST_TIMEOUT = Timeout(socket = 2.minutes)

        private val translationJsonSchema: JsonObject =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("translations") {
                        put("type", "object")
                        putJsonObject("additionalProperties") {
                            put("type", "string")
                        }
                    }
                }
                putJsonArray("required") {
                    add("translations")
                }
            }
        private val aiResponseJsonSchema =
            JsonSchema(
                name = "ai_translation_schema",
                schema = translationJsonSchema,
                strict = false,
            )

        /** The same reply for a strict schema: every object closed, so the open map becomes a list. */
        private val strictResponseJsonSchema =
            JsonSchema(
                name = "ai_translation_schema",
                schema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("translations") {
                                put("type", "array")
                                putJsonObject("items") {
                                    put("type", "object")
                                    putJsonObject("properties") {
                                        putJsonObject("key") { put("type", "string") }
                                        putJsonObject("value") { put("type", "string") }
                                    }
                                    putJsonArray("required") {
                                        add("key")
                                        add("value")
                                    }
                                    put("additionalProperties", false)
                                }
                            }
                        }
                        putJsonArray("required") { add("translations") }
                        put("additionalProperties", false)
                    },
                strict = true,
            )
    }
}

/**
 * The AI provider. The stored setting is the entry's name in lower case ("gemini", "custom_openai",
 * "openrouter"…), so a new entry needs no mapping of its own. [presetHost] is the library's preset
 * for that provider; [CUSTOM_OPENAI] takes the base URL and headers from Settings instead.
 */
enum class AIHost(
    internal val presetHost: OpenAIHost?,
) {
    GEMINI(OpenAIHost.Gemini),
    OPENAI(OpenAIHost.OpenAI),
    CUSTOM_OPENAI(null),
    OPENROUTER(OpenAIHost.OpenRouter),
    GROQ(OpenAIHost.Groq),
    DEEPSEEK(OpenAIHost.DeepSeek),
    MISTRAL(OpenAIHost.Mistral),
    XAI(OpenAIHost.XAI),
    TOGETHER(OpenAIHost.Together),
    FIREWORKS(OpenAIHost.Fireworks),
    CEREBRAS(OpenAIHost.Cerebras),
    ANTHROPIC(OpenAIHost.Anthropic),
    ;

    companion object {
        /** The entry a stored provider setting names; Gemini when it names none. */
        fun fromSetting(provider: String): AIHost = entries.firstOrNull { it.name.equals(provider, ignoreCase = true) } ?: GEMINI
    }
}
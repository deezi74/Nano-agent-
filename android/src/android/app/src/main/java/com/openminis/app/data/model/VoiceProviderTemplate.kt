package com.openminis.app.data.model

/**
 * [T-android-provider-voice] Voice provider templates — Android port of iOS
 * VoiceProviderTemplate.swift (single source of truth for voice vendors).
 *
 * Voice-specialised vendors (MiniMax / Alibaba / Doubao / iFlytek / MiMo …)
 * are NOT distinct ProviderType cases — they ride on an OpenAI/Anthropic-
 * compatible instance identified by its base URL. A template:
 *   1. Preseeds the Add-Provider flow (underlying type + base URL).
 *   2. Carries mock voice models tagged with the exact single-flag SEED
 *      modality shape (see VoiceModality): ASR seed = inputs ["audio"] only,
 *      TTS seed = outputs ["audio"] only. These vendors have no OpenAI-style
 *      /models endpoint, so addInstance seeds these entries directly. Once
 *      seeded they are ordinary ModelEntry values.
 *
 * `baseURLMarkers` serve TWO narrow roles only: (1) seed the mock voice models
 * at add-time, (2) route voice REQUESTS to the right vendor adapter in
 * VoiceProviderFactory. They never classify an instance as "voice-only" —
 * voice visibility is per-model-modality (shadow view over audio entries).
 *
 * Model ids / display names / base URLs are kept byte-identical to iOS so a
 * config exported on one platform imports losslessly on the other.
 */
data class VoiceProviderTemplate(
    val id: String,
    val name: String,
    val providerType: ProviderType,
    val baseURL: String,
    val appendV1: Boolean,
    /** What the vendor can do — UI localizes the label from this. */
    val capability: Capability,
    val baseURLMarkers: List<String>,
    val mockModels: List<LLMModel>,
    /** Optional caveat surfaced in the UI (extra credentials etc.). */
    val note: String? = null,
) {
    enum class Capability { TTS, ASR, BOTH }

    companion object {
        /** The template whose base-URL markers match [baseURL], if any. */
        fun template(forBaseURL: String?): VoiceProviderTemplate? {
            val base = forBaseURL?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return all.firstOrNull { tpl -> tpl.baseURLMarkers.any { base.contains(it) } }
        }

        /** Build the seed ModelEntry list for an instance matching a template. */
        fun mockEntries(instance: ProviderInstance): List<ModelEntry> {
            val tpl = template(instance.customBaseURL) ?: return emptyList()
            return tpl.mockModels.map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
        }

        private fun tts(id: String, name: String, provider: String) = LLMModel(
            id = id, displayName = name, provider = provider,
            outputModalities = listOf("audio"),
        )

        private fun asr(id: String, name: String, provider: String) = LLMModel(
            id = id, displayName = name, provider = provider,
            inputModalities = listOf("audio"),
        )

        val all: List<VoiceProviderTemplate> = listOf(
            VoiceProviderTemplate(
                id = "elevenlabs",
                name = "ElevenLabs",
                providerType = ProviderType.openAI,
                baseURL = "https://api.elevenlabs.io",
                appendV1 = false,
                capability = Capability.TTS,
                baseURLMarkers = listOf("elevenlabs"),
                mockModels = listOf(
                    // The model `id` is the ElevenLabs voice_id.
                    tts("21m00Tcm4TlvDq8ikWAM", "Rachel (EN, F)", "elevenlabs"),
                    tts("pNInz6obpgDQGcFmaJgB", "Adam (EN, M)", "elevenlabs"),
                    tts("EXAVITQu4vr4xnSDxMaL", "Bella (EN, F)", "elevenlabs"),
                    tts("ErXwobaYiN019PkySvjV", "Antoni (EN, M)", "elevenlabs"),
                ),
            ),
            VoiceProviderTemplate(
                id = "deepgram",
                name = "Deepgram",
                providerType = ProviderType.openAI,
                baseURL = "https://api.deepgram.com",
                appendV1 = false,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("deepgram"),
                mockModels = listOf(
                    asr("nova-2", "Nova-2 (ASR)", "deepgram"),
                    asr("nova-3", "Nova-3 (ASR)", "deepgram"),
                    tts("aura-asteria-en", "Aura Asteria (TTS, EN F)", "deepgram"),
                    tts("aura-luna-en", "Aura Luna (TTS, EN F)", "deepgram"),
                    tts("aura-orion-en", "Aura Orion (TTS, EN M)", "deepgram"),
                ),
            ),
            VoiceProviderTemplate(
                id = "azure-tts",
                name = "Azure TTS",
                providerType = ProviderType.openAI,
                baseURL = "https://eastus.tts.speech.microsoft.com",
                appendV1 = false,
                capability = Capability.TTS,
                baseURLMarkers = listOf("tts.speech.microsoft.com"),
                mockModels = listOf(
                    // English (US) — popular picks
                    tts("en-US-JennyNeural", "Jenny (EN, F)", "azure-tts"),
                    tts("en-US-GuyNeural", "Guy (EN, M)", "azure-tts"),
                    tts("en-US-AriaNeural", "Aria (EN, F)", "azure-tts"),
                    tts("en-US-DavisNeural", "Davis (EN, M)", "azure-tts"),
                    tts("en-US-AvaNeural", "Ava (EN, F)", "azure-tts"),
                    tts("en-US-AndrewNeural", "Andrew (EN, M)", "azure-tts"),
                    tts("en-US-EmmaNeural", "Emma (EN, F)", "azure-tts"),
                    tts("en-US-BrianNeural", "Brian (EN, M)", "azure-tts"),
                    // Japanese
                    tts("ja-JP-NanamiNeural", "Nanami (JA, F)", "azure-tts"),
                    tts("ja-JP-KeitaNeural", "Keita (JA, M)", "azure-tts"),
                    tts("ja-JP-AoiNeural", "Aoi (JA, F)", "azure-tts"),
                    tts("ja-JP-DaichiNeural", "Daichi (JA, M)", "azure-tts"),
                    tts("ja-JP-ShioriNeural", "Shiori (JA, F)", "azure-tts"),
                    // Korean
                    tts("ko-KR-SunHiNeural", "SunHi (KO, F)", "azure-tts"),
                    tts("ko-KR-InJoonNeural", "InJoon (KO, M)", "azure-tts"),
                ),
                note = "Enter the Azure Speech Services subscription key. Set the base URL to your region, e.g. https://eastus.tts.speech.microsoft.com",
            ),
        )
    }
}

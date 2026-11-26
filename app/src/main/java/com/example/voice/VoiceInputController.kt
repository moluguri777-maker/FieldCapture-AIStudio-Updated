package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VoiceUiState(
    val isAvailable: Boolean = false,
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val lastTranscript: String = "",
    val statusMessage: String = "Ready for voice or quick command",
    val errorMessage: String? = null
)

class VoiceInputController(
    private val context: Context,
    private val onTranscriptReady: (String) -> Unit
) {
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    init {
        probeScope.launch {
            val available = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
            _state.value = _state.value.copy(
                isAvailable = available,
                statusMessage = if (available) {
                    "Android SpeechRecognizer ready"
                } else {
                    "Speech service unavailable on emulator — use Quick-Command bar"
                }
            )
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = _state.value.copy(
                isAvailable = false,
                isListening = false,
                errorMessage = "Speech recognition service unavailable on this device. Use Quick-Command buttons below."
            )
            return
        }

        stopListening()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = _state.value.copy(
                    isListening = true,
                    errorMessage = null,
                    statusMessage = "Listening for command…"
                )
            }

            override fun onBeginningOfSpeech() {
                _state.value = _state.value.copy(statusMessage = "Detecting speech…")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _state.value = _state.value.copy(statusMessage = "Processing transcript…")
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Speech client stopped"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network error"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized — try again or use Quick-Command"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                    else -> "Speech error ($error)"
                }
                _state.value = _state.value.copy(
                    isListening = false,
                    errorMessage = msg,
                    statusMessage = "Ready"
                )
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val top = matches?.firstOrNull().orEmpty().trim()
                _state.value = _state.value.copy(
                    isListening = false,
                    partialTranscript = "",
                    lastTranscript = top,
                    errorMessage = if (top.isEmpty()) "Empty transcript" else null,
                    statusMessage = if (top.isNotEmpty()) "Heard: \"$top\"" else "Ready"
                )
                if (top.isNotEmpty()) {
                    onTranscriptReady(top)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (partial.isNotBlank()) {
                    _state.value = _state.value.copy(partialTranscript = partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer.startListening(intent)
    }

    fun stopListening() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
        _state.value = _state.value.copy(
            isListening = false,
            statusMessage = if (_state.value.isAvailable) "Ready" else "Use Quick-Command bar"
        )
    }

    fun release() {
        stopListening()
        probeScope.cancel()
    }
}

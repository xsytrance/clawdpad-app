package dev.pokepad.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One-shot voice capture for trainer commands. Say "use Thunderbolt" / "Salamence,
 * Flamethrower!" / just "Fly" — we return the recognizer's hypotheses and the
 * caller matches them against the active mon's legal moves. Runs on the main
 * thread; self-destructs after a single result or error.
 */
object Voice {
    fun available(ctx: Context) = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun listen(ctx: Context, onState: (String) -> Unit, onResult: (List<String>) -> Unit) {
        val sr = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (e: Exception) { onResult(emptyList()); return }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onState("🎤 listening…") }
            override fun onBeginningOfSpeech() { onState("🎤 …") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onState("… thinking") }
            override fun onError(error: Int) { runCatching { sr.destroy() }; onResult(emptyList()) }
            override fun onResults(results: Bundle) {
                val hyps = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                runCatching { sr.destroy() }; onResult(hyps.toList())
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        runCatching { sr.startListening(intent) }.onFailure { onResult(emptyList()) }
    }
}

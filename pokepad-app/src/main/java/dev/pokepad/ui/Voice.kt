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

    /** match spoken hypotheses against options ("use flamethrower" → flamethrower,
     *  "salamence!" → Salamence). Normalized contains + token overlap. */
    fun match(hyps: List<String>, options: List<String>): String? {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z]"), "")
        val no = options.map { it to norm(it.replace("-", " ")) }
        for (h in hyps) {
            val hn = norm(h)
            for ((o, on) in no) if (on.isNotEmpty() && (hn.contains(on) || (hn.length >= 3 && on.contains(hn)))) return o
        }
        for (h in hyps) {
            val toks = h.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 }.toSet()
            for ((o, _) in no) {
                val otoks = o.replace("-", " ").lowercase().split(" ").filter { it.length >= 3 }.toSet()
                if (toks.any { it in otoks }) return o
            }
        }
        return null
    }

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

/**
 * Hands-free trainer voice: toggle VOICE MODE on and every turn it listens by
 * itself. Say your Pokémon's NAME → beep + buzz + "…your command?" → say the
 * ATTACK. Saying the attack directly also works. Keeps re-listening while the
 * move menu is open; backs off automatically if it can't hear anything.
 */
class VoiceCommander(
    private val ctx: Context,
    private val onStatus: (String) -> Unit,
    private val onMove: (String) -> Unit,
) {
    var enabled = false; private set
    private var names: List<String> = emptyList()
    private var moves: List<String> = emptyList()
    private var awaitingMove = false
    private var menuOpen = false
    private var misses = 0
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val tone = runCatching {
        android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 85) }.getOrNull()

    /** returns the new enabled state */
    fun toggle(): Boolean {
        enabled = !enabled
        if (enabled) { misses = 0; if (menuOpen) begin() } else onStatus("")
        return enabled
    }

    fun menuShown(monNames: List<String>, legalMoves: List<String>) {
        names = monNames.filter { it.isNotBlank() }.distinct()
        moves = legalMoves
        menuOpen = true; awaitingMove = false
        if (enabled) begin()
    }

    fun menuHidden() { menuOpen = false }
    fun stop() { menuOpen = false; enabled = false }

    private fun begin() {
        if (!menuOpen || !enabled) return
        onStatus(if (awaitingMove) "🎤 …your command?" else "🎤 say your Pokémon's name (or the move)")
        Voice.listen(ctx, onState = {}, onResult = { hyps -> ui.post { handle(hyps) } })
    }

    private fun handle(hyps: List<String>) {
        if (!menuOpen || !enabled) return
        if (hyps.isEmpty()) {
            if (++misses >= 8) { enabled = false; onStatus("voice off — couldn't hear (tap 🎤 to retry)"); return }
            ui.postDelayed({ begin() }, 700); return
        }
        misses = 0
        if (!awaitingMove) {
            Voice.match(hyps, moves)?.let { onMove(it); return }          // direct attack works
            val nm = Voice.match(hyps, names)
            if (nm != null) {                                             // name → acknowledge → attack
                awaitingMove = true
                ack()
                onStatus("⚡ $nm! …your command?")
                ui.postDelayed({ begin() }, 350)
                return
            }
        } else {
            Voice.match(hyps, moves)?.let { awaitingMove = false; onMove(it); return }
        }
        ui.postDelayed({ begin() }, 600)                                  // heard something else — keep listening
    }

    private fun ack() {
        runCatching { tone?.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 140) }
        runCatching {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            v?.vibrate(android.os.VibrationEffect.createOneShot(90, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}

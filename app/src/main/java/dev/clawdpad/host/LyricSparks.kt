package dev.clawdpad.host

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyric Sparks — keywords from the song you're ACTUALLY playing, timed to
 * the moment they're sung, rendered kinetically on the glass.
 *
 * Pipeline: MediaSession (real track title/artist + live playback
 * position; needs notification access) → LRCLIB (free synced-lyrics API,
 * no key) → per-line keyword extraction → ClawdRenderer.lyric() cards.
 */
class MediaListener : NotificationListenerService()  // just the access hook

class LyricSparks(private val context: Context) {

    data class Spark(val word: String, val atMs: Long, val color: IntArray)

    @Volatile private var sparks: List<Spark> = emptyList()
    @Volatile private var trackKey = ""
    @Volatile var trackLabel = ""
        private set
    private var controller: MediaController? = null

    private val PALETTE = listOf(
        intArrayOf(217, 119, 87), intArrayOf(255, 205, 90),
        intArrayOf(120, 170, 255), intArrayOf(235, 120, 180),
        intArrayOf(130, 220, 140))

    private val STOP = setOf("the","a","an","and","or","but","in","on","at",
        "to","of","i","im","you","your","we","me","my","it","its","is","are",
        "was","be","been","do","dont","not","no","so","for","with","that",
        "this","they","them","he","she","his","her","oh","ooh","yeah","la",
        "na","uh","hey","got","get","just","like","all","up","down","out",
        "when","what","where","who","how","can","cant","will","wont","if",
        "as","from","by","have","has","had","were","there","their","then")

    fun accessGranted(): Boolean =
        Settings.Secure.getString(context.contentResolver,
            "enabled_notification_listeners")
            ?.contains(context.packageName) == true

    /** Poll the active media session; fetch lyrics when the track changes.
     *  Returns a status line for the UI. */
    fun refresh(): String {
        if (!accessGranted()) return "needs notification access"
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as MediaSessionManager
            val sessions = msm.getActiveSessions(
                ComponentName(context, MediaListener::class.java))
            controller = sessions.firstOrNull { it.metadata != null }
            val md = controller?.metadata ?: return "no music session found"
            val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return "no track info"
            val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            val key = "$artist — $title"
            trackLabel = key
            if (key != trackKey) {
                trackKey = key
                sparks = emptyList()
                Thread { fetchLyrics(artist, title) }.start()
                "🎤 $key — fetching lyrics…"
            } else "🎤 $key" + if (sparks.isEmpty()) " (no synced lyrics)" else ""
        } catch (e: SecurityException) {
            "needs notification access"
        }
    }

    /** The word to show right now, with its age and color — or null. */
    fun current(): Triple<String, Double, IntArray>? {
        val c = controller ?: return null
        val st = c.playbackState ?: return null
        if (st.state != PlaybackState.STATE_PLAYING) return null
        val pos = st.position +
                ((System.currentTimeMillis() - st.lastPositionUpdateTime) *
                        st.playbackSpeed).toLong()
        val list = sparks
        if (list.isEmpty()) return null
        var best: Spark? = null
        for (s in list) {
            if (s.atMs <= pos && pos - s.atMs < 2600) best = s
            if (s.atMs > pos) break
        }
        return best?.let { Triple(it.word, (pos - it.atMs) / 1000.0, it.color) }
    }

    private fun fetchLyrics(artist: String, title: String) {
        try {
            val url = URL("https://lrclib.net/api/get?artist_name=" +
                    URLEncoder.encode(artist, "UTF-8") +
                    "&track_name=" + URLEncoder.encode(title, "UTF-8"))
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "clawdpad (github.com/xsytrance/clawdpad)")
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode != 200) return
            val body = conn.inputStream.readBytes().decodeToString()
            val synced = org.json.JSONObject(body)
                .optString("syncedLyrics", "")
            if (synced.isBlank()) return
            val out = mutableListOf<Spark>()
            var lineIdx = 0
            for (line in synced.lines()) {
                val m = Regex("""^\[(\d+):(\d+)(?:\.(\d+))?](.*)""")
                    .find(line.trim()) ?: continue
                val (mm, ss, cs, text) = m.destructured
                val ms = mm.toLong() * 60000 + ss.toLong() * 1000 +
                        (cs.ifEmpty { "0" }).padEnd(2, '0').take(2).toLong() * 10
                val word = keyword(text) ?: continue
                out.add(Spark(word, ms, PALETTE[lineIdx % PALETTE.size]))
                lineIdx++
            }
            sparks = out
        } catch (e: Exception) {
            android.util.Log.e("clawdpad", "lyrics fetch failed", e)
        }
    }

    /** Pick the line's most spark-worthy word. */
    private fun keyword(text: String): String? {
        val words = text.lowercase()
            .replace(Regex("[^a-z' ]"), " ")
            .split(" ")
            .map { it.trim('\'') }
            .filter { it.length in 3..9 && it !in STOP }
        return words.maxByOrNull { it.length }?.uppercase()
    }
}

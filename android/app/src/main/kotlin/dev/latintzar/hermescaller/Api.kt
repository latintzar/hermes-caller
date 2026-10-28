package dev.latintzar.hermescaller

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object Api {
    private const val TAG = "CallerApi"
    @Volatile var BASE: String = ""
    @Volatile private var accessKey: String = ""
    fun configure(base: String, key: String) {
        require(base.startsWith("https://") && !base.contains("?") && !base.contains("#"))
        BASE = base.trimEnd('/')
        accessKey = key
    }
    private fun authorize(conn: HttpURLConnection) {
        check(BASE.isNotEmpty() && accessKey.isNotEmpty()) { "Configure the server first" }
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Authorization", "Bearer $accessKey")
    }

    /** POST to url, return response body string or throw. */
    fun post(path: String, body: String? = null, contentType: String = "application/json"): String {
        val url = URL("$BASE$path")
        val conn = url.openConnection() as HttpURLConnection
        authorize(conn)
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code: $text")
        return text
    }

    /** GET url, return response body or throw. */
    fun get(path: String): String {
        val url = URL("$BASE$path")
        val conn = url.openConnection() as HttpURLConnection
        authorize(conn)
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        val code = conn.responseCode
        val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return text
    }

    /** Fetch Twilio access token. Returns the JWT string. */
    fun fetchToken(): String {
        val resp = post("/api/caller/token")
        return JSONObject(resp).getString("token")
    }

    data class VersionInfo(val code: Int, val name: String, val apkUrl: String)

    /** Check latest APK version from server. */
    fun fetchVersion(): VersionInfo {
        val json = JSONObject(get("/api/caller/version"))
        return VersionInfo(
            code   = json.getInt("version_code"),
            name   = json.getString("version_name"),
            apkUrl = json.getString("apk_url"),
        )
    }

    /** Download bytes from a full or relative URL. */
    fun downloadBytes(urlOrPath: String): ByteArray {
        val full = if (urlOrPath.startsWith("http")) urlOrPath else "$BASE$urlOrPath"
        val conn = URL(full).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        return conn.inputStream.use { it.readBytes() }
    }

    // ── Discovery / Coach panel ──────────────────────────────────────────────
    //
    // The backend parses Hermes' cheat-sheet markdown live (one .md per
    // segment under apps/discovery workspace/discovery/scripts/) and returns a
    // JSON array of cues. The Android app renders one row per cue.

    /**
     * One pain hypothesis on the validation board.
     * `status` is one of: "new" | "unclear" | "probably-invalidated".
     */
    data class PainPoint(val status: String, val en: String, val es: String)

    data class Discovery(
        val segment: String,
        val items: List<PainPoint>,
        val updatedAt: String,
    )

    fun fetchDiscovery(segment: String): Discovery {
        val raw = JSONObject(get("/api/discovery/$segment"))
        val arr = raw.getJSONArray("items")
        val items = mutableListOf<PainPoint>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // Backwards-compat: older builds shipped a `cue` field; the
            // server now returns `status`. Read either, prefer status.
            val status = if (o.has("status")) o.getString("status")
                         else o.optString("cue", "unclear").lowercase()
            items.add(PainPoint(status, o.getString("en"), o.getString("es")))
        }
        return Discovery(
            segment = raw.getString("segment"),
            items = items,
            updatedAt = raw.optString("updated_at", ""),
        )
    }

    /**
     * Honcho-synthesised cross-call insights for the Pain Points tab.
     *
     * Primary source: the caller's Honcho peer synthesises across the full call
     * corpus. Falls back to static discovery file if Honcho is unavailable.
     *
     * `source` is "honcho" when live, "static-fallback" otherwise.
     * `hotSignal` is one standout field observation from recent calls, or "".
     */
    data class Insights(
        val ok: Boolean,
        val painThemes: List<PainPoint>,
        val segmentSignals: List<String>,
        val hotSignal: String,
        val source: String,
        val staleAfterMs: Long,
    )

    fun fetchInsights(segment: String = "restaurants"): Insights {
        val raw = JSONObject(get("/api/insights?segment=$segment"))
        val ok = raw.optBoolean("ok", false)
        val themes = mutableListOf<PainPoint>()
        val arr = raw.optJSONArray("pain_themes") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val status = o.optString("status", "unclear")
            themes.add(PainPoint(
                status,
                o.optString("en", ""),
                o.optString("es", ""),
            ))
        }
        val signals = mutableListOf<String>()
        val sarr = raw.optJSONArray("segment_signals") ?: JSONArray()
        for (i in 0 until sarr.length()) signals.add(sarr.optString(i, ""))
        return Insights(
            ok = ok,
            painThemes = themes,
            segmentSignals = signals,
            hotSignal = raw.optString("hot_signal", ""),
            source = raw.optString("source", ""),
            staleAfterMs = raw.optLong("stale_after_ms", 300_000L),
        )
    }

    /** POST raw-mic diagnostic to /api/diag/mic. Best-effort, never throws. */
    fun postMicDiag(payload: JSONObject) {
        try {
            // Use the existing `post()` which we know works — the previous
            // custom postJson lost POSTs silently for reasons that were
            // never confirmed (HTTP/2 negotiation? buffered output never
            // flushed? unknown). `post()` is the path used by every other
            // endpoint and is battle-tested.
            post("/api/diag/mic", payload.toString())
            Log.i(TAG, "Mic diag posted: ${payload}")
        } catch (e: Exception) {
            Log.w(TAG, "diag post failed: ${e.message}")
            // As a fallback, smuggle the diag fields into the next caller
            // request by stuffing a static. Server can pick them up out
            // of /api/diag/mic-fallback if that path is ever wired in.
        }
    }

    /** Best-effort heartbeat — never throws. Backend logs to a file
     *  Hermes can read on its own cadence to know the caller is in coach mode. */
    fun pingHeartbeat() {
        try { post("/api/discovery/heartbeat") } catch (e: Exception) {
            Log.d(TAG, "heartbeat skipped: ${e.message}")
        }
    }

    // ── Prospects (rolodex) ──────────────────────────────────────────────────
    //
    // Backend reconciles immutable call notes (~/calls/*.md) with
    // Hermes-curated per-prospect markdown (~/discovery workspace/contacts/<phone>.md).
    // See backend/prospects.py for the data shape contract.

    data class Prospect(
        val phone: String,           // digits only, used as path key
        val phoneE164: String,       // +34… form for the call button
        val name: String,
        val lastCall: String,
        val callCount: Int,
        val segments: List<String>,
        val place: String?,
        val neighborhood: String?,
    )

    data class TranscriptRef(
        val name: String,
        val date: String,
        val duration: String,
        val place: String?,
    )

    data class ProspectDetail(
        val phone: String,
        val phoneE164: String,
        val name: String,
        val place: String?,
        val type: String?,
        val neighborhood: String?,
        val rating: String?,
        val segments: List<String>,
        val callCount: Int,
        val notes: String,
        val nextSteps: List<String>,
        val transcripts: List<TranscriptRef>,
    )

    private fun jsonStringList(json: JSONObject, key: String): List<String> {
        if (!json.has(key) || json.isNull(key)) return emptyList()
        val arr = json.getJSONArray(key)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** Returns null when the key is missing or JSON null, otherwise the
     *  string value. JSONObject.optString(key, null) triggers a Kotlin
     *  platform-type warning; this is the explicit replacement. */
    private fun optStringOrNull(o: JSONObject, key: String): String? =
        if (o.has(key) && !o.isNull(key)) o.optString(key) else null

    fun fetchProspects(): List<Prospect> {
        val raw = JSONObject(get("/api/prospects"))
        val arr = raw.getJSONArray("prospects")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Prospect(
                phone = o.getString("phone"),
                phoneE164 = o.optString("phone_e164", ""),
                name = o.optString("name", ""),
                lastCall = o.optString("last_call", ""),
                callCount = o.optInt("call_count", 0),
                segments = jsonStringList(o, "segments"),
                place = optStringOrNull(o, "place"),
                neighborhood = optStringOrNull(o, "neighborhood"),
            )
        }
    }

    fun fetchProspectDetail(phone: String): ProspectDetail {
        val o = JSONObject(get("/api/prospects/$phone"))
        val txArr = o.getJSONArray("transcripts")
        val transcripts = (0 until txArr.length()).map { i ->
            val t = txArr.getJSONObject(i)
            TranscriptRef(
                name = t.getString("name"),
                date = t.optString("date", ""),
                duration = t.optString("duration", ""),
                place = optStringOrNull(t, "place"),
            )
        }
        return ProspectDetail(
            phone = o.getString("phone"),
            phoneE164 = o.optString("phone_e164", ""),
            name = o.optString("name", ""),
            place = optStringOrNull(o, "place"),
            type = optStringOrNull(o, "type"),
            neighborhood = optStringOrNull(o, "neighborhood"),
            rating = optStringOrNull(o, "rating"),
            segments = jsonStringList(o, "segments"),
            callCount = o.optInt("call_count", 0),
            notes = o.optString("notes", ""),
            nextSteps = jsonStringList(o, "next_steps"),
            transcripts = transcripts,
        )
    }

    fun fetchTranscript(phone: String, noteName: String): String {
        val url = URL("$BASE/api/prospects/$phone/calls/$noteName")
        val conn = url.openConnection() as HttpURLConnection
        authorize(conn)
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        val code = conn.responseCode
        val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return text
    }

    data class SearchHit(
        val phone: String,
        val phoneE164: String,
        val name: String,
        val segments: List<String>,
        val notesMatch: String,
        val transcriptMatch: String,
    )

    /**
     * Honcho-derived insight for a prospect. Computed live from the
     * representation Honcho has built across every prior call.
     *
     * `ok=false` is normal on brand-new prospects — Honcho hasn't seen
     * enough signal yet. The Android UI shows a "no signal yet" state.
     */
    data class ProspectInsight(
        val ok: Boolean,
        val summary: String,
        val nextSteps: List<String>,
        val tags: List<String>,
        val computedAt: String,
        val reason: String?,
    )

    fun fetchProspectInsight(phone: String): ProspectInsight {
        val raw = JSONObject(get("/api/prospects/$phone/insight"))
        return ProspectInsight(
            ok = raw.optBoolean("ok", false),
            summary = raw.optString("summary", ""),
            nextSteps = jsonStringList(raw, "next_steps"),
            tags = jsonStringList(raw, "tags"),
            computedAt = raw.optString("computed_at", ""),
            reason = optStringOrNull(raw, "reason"),
        )
    }

    fun searchProspects(query: String): List<SearchHit> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val raw = JSONObject(get("/api/prospects/search?q=$q"))
        val arr = raw.getJSONArray("hits")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SearchHit(
                phone = o.getString("phone"),
                phoneE164 = o.optString("phone_e164", ""),
                name = o.optString("name", ""),
                segments = jsonStringList(o, "segments"),
                notesMatch = o.optString("notes_match", ""),
                transcriptMatch = o.optString("transcript_match", ""),
            )
        }
    }
}

package dev.latintzar.hermescaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Hermes Caller — main shell.
 *
 * Bottom-nav tabs:
 *   - PAIN POINTS  → Hermes' live cold-call cheat sheet, two segment tabs
 *                    (Restaurants / Farms). Tap a cue → flips EN ⇄ ES.
 *                    Heartbeat fires every 30s while this view is visible
 *                    so Hermes knows the caller is in coach mode.
 *   - PROSPECTS    → rolodex. Search bar + list of every phone we've called.
 *                    Tap a prospect → detail view with call button (top
 *                    right), Hermes' running notes, NEXT STEPS, and the
 *                    full transcript list. Tap a transcript → read it.
 *
 * The dialer view (view_dialer.xml) is reachable only via system tel:
 * intents (we're a registered PhoneAccount) — no nav button leads to
 * it. Calls are placed from the prospect detail's "CALL" button.
 *
 * Source-of-truth for the coach content lives at
 * apps/discovery workspace/discovery/scripts/<segment>.md. Per-prospect notes
 * live at ~/discovery workspace/contacts/<phone-slug>.md. Both are
 * Hermes-maintained.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HermesCaller"
        private const val PERM_CODE = 42
        private const val COACH_REFRESH_MS = 20_000L
        private const val COACH_HEARTBEAT_MS = 30_000L
        private const val SEARCH_DEBOUNCE_MS = 250L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Top-level views (one VISIBLE at a time). */
    private enum class Screen { COACH, PROSPECTS, PROSPECT_DETAIL, TRANSCRIPT, DIALER }

    // Top-level view containers
    private lateinit var contentDialer: View
    private lateinit var contentCoach: View
    private lateinit var contentProspects: View
    private lateinit var contentProspectDetail: View
    private lateinit var contentTranscript: View

    // Dialer
    private lateinit var numberInput: EditText
    private lateinit var callBtn: Button
    private lateinit var statusText: TextView

    // Banners
    private lateinit var updateBanner: View
    private lateinit var updateBtn: Button
    private lateinit var setupBanner: View
    private lateinit var setupBtn: Button
    private lateinit var setupText: TextView

    // Bottom nav + segment tabs
    private lateinit var navCoach: Button
    private lateinit var navProspects: Button
    private lateinit var segRestaurants: Button
    private lateinit var segFarms: Button

    // Coach content
    private lateinit var cueList: LinearLayout
    private lateinit var coachStatus: TextView
    private lateinit var cueRefresh: SwipeRefreshLayout
    private lateinit var hotSignalBanner: View
    private lateinit var hotSignalText: TextView

    // In-call overlay (full-screen) — shown while an OutboundCall is active.
    private lateinit var contentInCall: View
    private lateinit var icState: TextView
    private lateinit var icNumber: TextView
    private lateinit var icHint: TextView
    private lateinit var icMuteBtn: Button
    private lateinit var icSpeakerBtn: Button
    private lateinit var icHangupBtn: Button
    private var activeCall: OutboundCall? = null
    private var speakerOn: Boolean = false

    // Prospects
    private lateinit var prospectSearch: EditText
    private lateinit var prospectList: LinearLayout
    private lateinit var prospectsStatus: TextView

    // Prospect detail
    private lateinit var pdBackBtn: Button
    private lateinit var pdCallBtn: Button
    private lateinit var pdName: TextView
    private lateinit var pdSegments: TextView
    private lateinit var pdNotes: TextView
    private lateinit var pdNextSteps: LinearLayout
    private lateinit var pdNextStepsEmpty: TextView
    private lateinit var pdTranscripts: LinearLayout
    // Honcho-derived "WHAT WE KNOW" card
    private lateinit var pdInsightStatus: TextView
    private lateinit var pdInsightSummary: TextView
    private lateinit var pdInsightTags: LinearLayout
    private lateinit var pdInsightEmpty: TextView

    // Transcript view
    private lateinit var txBackBtn: Button
    private lateinit var txTitle: TextView
    private lateinit var txBody: TextView

    private var coachJob: Job? = null
    private var heartbeatJob: Job? = null
    private var searchJob: Job? = null
    private var currentSegment: String = "restaurants"

    /** Last visible "tab" (where back from detail/transcript returns). */
    private var lastTab: Screen = Screen.COACH
    private var currentScreen: Screen = Screen.COACH
    private var currentProspectPhone: String? = null
    private var currentProspectE164: String? = null

    private val required = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ runtime permission. Without this the foreground
            // service notification ("Hermes Caller — call active") is
            // suppressed, and on some OEMs (Samsung especially) the OS
            // treats the foreground service as orphaned and kills it
            // mid-call — taking the mic with it.
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            bindViews()
            wireDialPad()
            wireButtons()
            wireNav()
            wireProspects()
            val prefs = getSharedPreferences("caller", MODE_PRIVATE)
            val base = prefs.getString("base", "") ?: ""
            val key = prefs.getString("key", "") ?: ""
            if (base.isNotEmpty() && key.isNotEmpty()) {
                try { Api.configure(base, key) } catch (_: IllegalArgumentException) { }
            }
            showDialer()
            if (Api.BASE.isEmpty()) showServerSettings()
            if (!allGranted()) requestPerms() else postPermissionSetup()
            // Updates are installed by the operator.
            handleTelIntent(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal in onCreate", e)
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (allGranted()) refreshSetupBanner()
        if (currentScreen == Screen.COACH) startCoachLoops()
    }

    override fun onPause() {
        super.onPause()
        stopCoachLoops()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTelIntent(intent)
    }

    /** Pre-fill the dial pad if launched via tel: from an external app
     *  (Maps, Contacts, Hermes Discord links, etc.). */
    private fun handleTelIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "tel") return
        val number = data.schemeSpecificPart?.replace(Regex("[^+0-9*#]"), "") ?: ""
        if (number.isNotEmpty()) {
            numberInput.setText(number)
            statusText.text = "PRE-FILLED — TAP CALL"
            showDialer()
        }
    }

    override fun onBackPressed() {
        // Custom back stack: detail/transcript pop back through screens
        // before falling through to the OS.
        when (currentScreen) {
            Screen.TRANSCRIPT -> { showProspectDetail(currentProspectPhone ?: return); return }
            Screen.PROSPECT_DETAIL -> { showTab(Screen.PROSPECTS); return }
            Screen.DIALER -> { showTab(lastTab); return }
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        // Don't leave a call running if the activity is destroyed (e.g.
        // user kills the app). The foreground service would otherwise
        // keep the mic + audio mode held with no UI to hang up.
        try { activeCall?.stop() } catch (_: Exception) {}
        activeCall = null
        scope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        contentDialer         = findViewById(R.id.contentDialer)
        contentCoach          = findViewById(R.id.contentCoach)
        contentProspects      = findViewById(R.id.contentProspects)
        contentProspectDetail = findViewById(R.id.contentProspectDetail)
        contentTranscript     = findViewById(R.id.contentTranscript)
        numberInput    = findViewById(R.id.numberInput)
        callBtn        = findViewById(R.id.callBtn)
        statusText     = findViewById(R.id.statusText)
        updateBanner   = findViewById(R.id.updateBanner)
        updateBtn      = findViewById(R.id.updateBtn)
        setupBanner    = findViewById(R.id.setupBanner)
        setupBtn       = findViewById(R.id.setupBtn)
        setupText      = findViewById(R.id.setupText)
        navCoach       = findViewById(R.id.navCoach)
        navProspects   = findViewById(R.id.navProspects)
        segRestaurants = findViewById(R.id.segRestaurants)
        segFarms       = findViewById(R.id.segFarms)
        cueList        = findViewById(R.id.cueList)
        coachStatus    = findViewById(R.id.coachStatus)
        cueRefresh     = findViewById(R.id.cueRefresh)
        hotSignalBanner = findViewById(R.id.hotSignalBanner)
        hotSignalText   = findViewById(R.id.hotSignalText)
        contentInCall  = findViewById(R.id.contentInCall)
        icState        = findViewById(R.id.icState)
        icNumber       = findViewById(R.id.icNumber)
        icHint         = findViewById(R.id.icHint)
        icMuteBtn      = findViewById(R.id.icMuteBtn)
        icSpeakerBtn   = findViewById(R.id.icSpeakerBtn)
        icHangupBtn    = findViewById(R.id.icHangupBtn)
        icMuteBtn.setOnClickListener { toggleMute() }
        icSpeakerBtn.setOnClickListener { toggleSpeaker() }
        icHangupBtn.setOnClickListener { activeCall?.hangup() }
        cueRefresh.setOnRefreshListener {
            // Pull-to-refresh: re-fetch Hermes' latest pain points
            // immediately. The 20s background poll keeps things fresh
            // when this isn't pulled.
            loadCoach(currentSegment)
        }
        cueRefresh.setColorSchemeResources(R.color.rust, R.color.moss, R.color.gold)
        prospectSearch    = findViewById(R.id.prospectSearch)
        prospectList      = findViewById(R.id.prospectList)
        prospectsStatus   = findViewById(R.id.prospectsStatus)
        pdBackBtn       = findViewById(R.id.pdBackBtn)
        pdCallBtn       = findViewById(R.id.pdCallBtn)
        pdName          = findViewById(R.id.pdName)
        pdSegments      = findViewById(R.id.pdSegments)
        pdNotes         = findViewById(R.id.pdNotes)
        pdNextSteps     = findViewById(R.id.pdNextSteps)
        pdNextStepsEmpty = findViewById(R.id.pdNextStepsEmpty)
        pdTranscripts   = findViewById(R.id.pdTranscripts)
        pdInsightStatus  = findViewById(R.id.pdInsightStatus)
        pdInsightSummary = findViewById(R.id.pdInsightSummary)
        pdInsightTags    = findViewById(R.id.pdInsightTags)
        pdInsightEmpty   = findViewById(R.id.pdInsightEmpty)
        txBackBtn = findViewById(R.id.txBackBtn)
        txTitle   = findViewById(R.id.txTitle)
        txBody    = findViewById(R.id.txBody)
    }

    private fun wireDialPad() {
        val keys = listOf("1","2","3","4","5","6","7","8","9","*","0","#")
        val ids  = listOf(
            R.id.k1, R.id.k2, R.id.k3,
            R.id.k4, R.id.k5, R.id.k6,
            R.id.k7, R.id.k8, R.id.k9,
            R.id.kStar, R.id.k0, R.id.kHash
        )
        keys.zip(ids).forEach { (digit, id) ->
            findViewById<Button>(id).setOnClickListener {
                val cur = numberInput.text.toString()
                if (digit == "0" && cur.isEmpty()) numberInput.setText("+")
                else numberInput.append(digit)
            }
        }
        findViewById<Button>(R.id.kDel).setOnClickListener {
            val t = numberInput.text
            if (t.isNotEmpty()) t.delete(t.length - 1, t.length)
        }
    }

    private fun wireButtons() {
        callBtn.setOnClickListener { onCallPressed() }
        updateBtn.setOnClickListener { triggerUpdate() }
        setupBtn.setOnClickListener { Telecom.openCallingAccountsSettings(this) }
        pdBackBtn.setOnClickListener { showTab(Screen.PROSPECTS) }
        pdCallBtn.setOnClickListener { onProspectCallPressed() }
        txBackBtn.setOnClickListener {
            currentProspectPhone?.let { showProspectDetail(it) } ?: showTab(Screen.PROSPECTS)
        }
    }

    private fun wireNav() {
        navCoach.setOnClickListener { showDialer() }
        navProspects.setOnClickListener { showServerSettings() }
        segRestaurants.setOnClickListener { selectSegment("restaurants") }
        segFarms.setOnClickListener { selectSegment("farms") }
    }

    private fun showServerSettings() {
        val prefs = getSharedPreferences("caller", MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 0)
        }
        val base = EditText(this).apply {
            hint = "HTTPS server URL"
            setText(prefs.getString("base", ""))
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val key = EditText(this).apply {
            hint = "Access key"
            setText(prefs.getString("key", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        panel.addView(base)
        panel.addView(key)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Server settings")
            .setView(panel)
            .setPositiveButton("Save") { _, _ ->
                val url = base.text.toString().trim()
                val secret = key.text.toString().trim()
                try {
                    require(secret.isNotEmpty())
                    Api.configure(url, secret)
                    prefs.edit().putString("base", url).putString("key", secret).apply()
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(this, "Enter an HTTPS server URL and access key", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wireProspects() {
        prospectSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                searchJob?.cancel()
                searchJob = scope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    if (q.isEmpty()) loadProspects() else searchProspectsRemote(q)
                }
            }
        })
    }

    // ── View switching ──────────────────────────────────────────────────────

    /** Hide every top-level container; caller flips one to VISIBLE. */
    private fun hideAllScreens() {
        contentCoach.visibility = View.GONE
        contentProspects.visibility = View.GONE
        contentProspectDetail.visibility = View.GONE
        contentTranscript.visibility = View.GONE
        contentDialer.visibility = View.GONE
    }

    private fun showCoach() {
        hideAllScreens()
        contentCoach.visibility = View.VISIBLE
        currentScreen = Screen.COACH
        lastTab = Screen.COACH
        paintNav()
        selectSegment(currentSegment)
        startCoachLoops()
    }

    private fun showProspects() {
        hideAllScreens()
        contentProspects.visibility = View.VISIBLE
        currentScreen = Screen.PROSPECTS
        lastTab = Screen.PROSPECTS
        paintNav()
        stopCoachLoops()
        if (prospectList.childCount == 0) loadProspects()
    }

    private fun showTab(tab: Screen) {
        when (tab) {
            Screen.COACH -> showCoach()
            Screen.PROSPECTS -> showProspects()
            else -> showCoach()  // safety: only the two real tabs are valid here
        }
    }

    private fun showDialer() {
        hideAllScreens()
        contentDialer.visibility = View.VISIBLE
        currentScreen = Screen.DIALER
        // Don't repaint nav — neither tab is "active" while dialing.
        stopCoachLoops()
    }

    private fun paintNav() {
        val coachActive = currentScreen == Screen.COACH
        val prospectsActive = currentScreen == Screen.PROSPECTS ||
            currentScreen == Screen.PROSPECT_DETAIL ||
            currentScreen == Screen.TRANSCRIPT
        navCoach.setBackgroundColor(
            ContextCompat.getColor(this, if (coachActive) R.color.ink else R.color.paper)
        )
        navCoach.setTextColor(
            ContextCompat.getColor(this, if (coachActive) R.color.paper else R.color.ink)
        )
        navProspects.setBackgroundColor(
            ContextCompat.getColor(this, if (prospectsActive) R.color.ink else R.color.paper)
        )
        navProspects.setTextColor(
            ContextCompat.getColor(this, if (prospectsActive) R.color.paper else R.color.ink)
        )
    }

    private fun selectSegment(segment: String) {
        currentSegment = segment
        val isRest = segment == "restaurants"
        segRestaurants.setBackgroundColor(
            ContextCompat.getColor(this, if (isRest) R.color.ink else R.color.paper_warm)
        )
        segRestaurants.setTextColor(
            ContextCompat.getColor(this, if (isRest) R.color.paper else R.color.ink)
        )
        segFarms.setBackgroundColor(
            ContextCompat.getColor(this, if (isRest) R.color.paper_warm else R.color.ink)
        )
        segFarms.setTextColor(
            ContextCompat.getColor(this, if (isRest) R.color.ink else R.color.paper)
        )
        loadCoach(segment)
    }

    // ── Coach loading + flip ────────────────────────────────────────────────

    private fun loadCoach(segment: String) {
        if (!cueRefresh.isRefreshing) coachStatus.text = "loading…"
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { Api.fetchDiscovery(segment) }
                if (currentSegment != segment) return@launch  // user switched mid-flight
                renderPainPoints(data.items)
                coachStatus.text = "${data.items.size} to validate"
            } catch (e: Exception) {
                Log.w(TAG, "pain-points load failed: ${e.message}")
                coachStatus.text = "offline"
            } finally {
                cueRefresh.isRefreshing = false
            }
        }
        // Load Honcho's hot-signal async — never blocks the board.
        // The signal is one raw field observation Honcho synthesised across
        // all calls; it complements the curated pain list without replacing it.
        loadHotSignal(segment)
    }

    /** Async Honcho hot-signal — fire and forget, never fails the board. */
    private fun loadHotSignal(segment: String) {
        scope.launch {
            try {
                val insights = withContext(Dispatchers.IO) { Api.fetchInsights(segment) }
                if (currentSegment != segment) return@launch
                val signal = insights.hotSignal.trim()
                if (signal.isNotEmpty()) {
                    hotSignalText.text = signal
                    hotSignalBanner.visibility = View.VISIBLE
                } else {
                    hotSignalBanner.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.d(TAG, "hot signal skipped: ${e.message}")
                hotSignalBanner.visibility = View.GONE
            }
        }
    }

    /**
     * Render the validation board. Each card shows a status badge
     * (only when status ≠ unclear, to keep the "default" case clean)
     * and the big pain-point statement. Tap → flip EN ⇄ ES with
     * haptic feedback.
     */
    private fun renderPainPoints(items: List<Api.PainPoint>) {
        cueList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        items.forEach { p ->
            val row = inflater.inflate(R.layout.item_cue, cueList, false)
            val label = row.findViewById<TextView>(R.id.cueLabel)
            val text = row.findViewById<TextView>(R.id.cueText)
            paintStatus(label, text, p.status)
            text.text = p.en
            row.tag = false  // false = showing EN, true = showing ES
            row.setOnClickListener { flipPainPoint(row, text, p) }
            cueList.addView(row)
        }
    }

    /** Apply status-specific styling to a pain card.
     *
     *  - new:                  rust badge, normal pain text
     *  - probably-invalidated: ink_faint badge, struck-through pain text
     *  - unclear (default):    no badge shown, normal pain text
     */
    private fun paintStatus(label: TextView, text: TextView, status: String) {
        val s = status.lowercase().trim()
        text.paintFlags = text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        text.alpha = 1.0f
        when (s) {
            "new" -> {
                label.visibility = View.VISIBLE
                label.text = "NEW"
                label.setTextColor(ContextCompat.getColor(this, R.color.rust))
            }
            "probably-invalidated" -> {
                label.visibility = View.VISIBLE
                label.text = "PROBABLY INVALIDATED"
                label.setTextColor(ContextCompat.getColor(this, R.color.ink_faint))
                text.paintFlags = text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                text.alpha = 0.55f
            }
            else -> {
                // unclear (baseline) — no badge to keep the "default"
                // card visually clean.
                label.visibility = View.GONE
            }
        }
    }

    private fun flipPainPoint(row: View, textView: TextView, p: Api.PainPoint) {
        val showingEs = row.tag as? Boolean ?: false
        val nextEs = !showingEs
        // Light haptic — same family as a key press, no permission needed.
        row.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        // Faster flip — feels snappier on Pixel, still readable.
        row.animate()
            .rotationY(90f)
            .setDuration(70)
            .withEndAction {
                textView.text = if (nextEs) p.es else p.en
                row.rotationY = -90f
                row.animate().rotationY(0f).setDuration(70).start()
            }.start()
        row.tag = nextEs
    }

    private fun startCoachLoops() {
        stopCoachLoops()
        coachJob = scope.launch {
            while (isActive) {
                delay(COACH_REFRESH_MS)
                if (currentScreen == Screen.COACH) loadCoach(currentSegment)
            }
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                withContext(Dispatchers.IO) { Api.pingHeartbeat() }
                delay(COACH_HEARTBEAT_MS)
            }
        }
    }

    private fun stopCoachLoops() {
        coachJob?.cancel()
        coachJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ── Prospects (rolodex) ─────────────────────────────────────────────────

    private fun loadProspects() {
        prospectsStatus.text = "loading…"
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Api.fetchProspects() }
                renderProspects(list)
                prospectsStatus.text = "${list.size} prospects · sorted by last call"
            } catch (e: Exception) {
                Log.w(TAG, "prospects load failed", e)
                prospectsStatus.text = "offline"
            }
        }
    }

    private fun searchProspectsRemote(query: String) {
        prospectsStatus.text = "searching…"
        scope.launch {
            try {
                val hits = withContext(Dispatchers.IO) { Api.searchProspects(query) }
                renderSearchHits(hits)
                prospectsStatus.text = "${hits.size} hits for \"$query\""
            } catch (e: Exception) {
                Log.w(TAG, "search failed", e)
                prospectsStatus.text = "search error"
            }
        }
    }

    private fun renderProspects(list: List<Api.Prospect>) {
        prospectList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        list.forEach { p ->
            val row = inflater.inflate(R.layout.item_prospect, prospectList, false)
            row.findViewById<TextView>(R.id.pName).text = p.name
            row.findViewById<TextView>(R.id.pMeta).text =
                "${p.callCount} · ${humanRelative(p.lastCall)}"
            row.findViewById<TextView>(R.id.pSegments).text =
                listOfNotNull(
                    p.segments.joinToString(" · ").takeIf { it.isNotBlank() },
                    p.neighborhood?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "—" }
            row.setOnClickListener { showProspectDetail(p.phone) }
            prospectList.addView(row)
        }
    }

    private fun renderSearchHits(hits: List<Api.SearchHit>) {
        prospectList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        hits.forEach { h ->
            val row = inflater.inflate(R.layout.item_prospect, prospectList, false)
            row.findViewById<TextView>(R.id.pName).text = h.name
            row.findViewById<TextView>(R.id.pMeta).text = "→"
            val snippet = listOf(h.notesMatch, h.transcriptMatch)
                .firstOrNull { it.isNotBlank() }
                ?.replace("<<", "[")
                ?.replace(">>", "]")
                ?: h.segments.joinToString(" · ")
            row.findViewById<TextView>(R.id.pSegments).text = snippet
            row.setOnClickListener { showProspectDetail(h.phone) }
            prospectList.addView(row)
        }
    }

    private fun showProspectDetail(phone: String) {
        hideAllScreens()
        contentProspectDetail.visibility = View.VISIBLE
        currentScreen = Screen.PROSPECT_DETAIL
        currentProspectPhone = phone
        paintNav()
        stopCoachLoops()

        pdName.text = "loading…"
        pdSegments.text = ""
        pdNotes.text = ""
        pdNextSteps.removeAllViews()
        pdTranscripts.removeAllViews()
        // Reset Honcho insight card
        pdInsightStatus.text = "thinking…"
        pdInsightSummary.text = ""
        pdInsightSummary.visibility = View.GONE
        pdInsightTags.removeAllViews()
        pdInsightTags.visibility = View.GONE
        pdInsightEmpty.visibility = View.GONE

        scope.launch {
            try {
                val d = withContext(Dispatchers.IO) { Api.fetchProspectDetail(phone) }
                currentProspectE164 = d.phoneE164
                pdName.text = d.name
                val segLine = listOfNotNull(
                    d.segments.joinToString(" · ").takeIf { it.isNotBlank() },
                    d.neighborhood?.takeIf { it.isNotBlank() },
                    d.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
                ).joinToString(" · ").ifBlank { "—" }
                pdSegments.text = segLine

                pdNotes.text = if (d.notes.isBlank())
                    "(no notes yet — Hermes will add some after the next call)"
                else d.notes

                if (d.nextSteps.isEmpty()) {
                    pdNextStepsEmpty.visibility = View.VISIBLE
                } else {
                    pdNextStepsEmpty.visibility = View.GONE
                    d.nextSteps.forEach { step ->
                        val tv = TextView(this@MainActivity).apply {
                            text = "•  $step"
                            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                            textSize = 14f
                            setLineSpacing(0f, 1.4f)
                            setPadding(0, 4, 0, 4)
                        }
                        pdNextSteps.addView(tv)
                    }
                }

                renderTranscriptList(d.transcripts)
            } catch (e: Exception) {
                Log.w(TAG, "prospect detail failed", e)
                pdName.text = "error"
                pdNotes.text = "Could not load prospect: ${e.message}"
            }
        }

        // Honcho insight runs in parallel — three dialectic queries on
        // the prospect peer. Cached server-side for 60s. Failure here
        // is silent: the rest of the page renders fine without it.
        scope.launch {
            try {
                val insight = withContext(Dispatchers.IO) { Api.fetchProspectInsight(phone) }
                if (currentProspectPhone != phone) return@launch
                renderInsight(insight)
            } catch (e: Exception) {
                Log.w(TAG, "insight fetch failed", e)
                pdInsightStatus.text = "offline"
                pdInsightEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun renderInsight(insight: Api.ProspectInsight) {
        if (!insight.ok || (insight.summary.isBlank() && insight.tags.isEmpty() && insight.nextSteps.isEmpty())) {
            pdInsightStatus.text = "no signal yet"
            pdInsightEmpty.visibility = View.VISIBLE
            pdInsightSummary.visibility = View.GONE
            pdInsightTags.visibility = View.GONE
        } else {
            pdInsightStatus.text = "honcho · live"
            pdInsightEmpty.visibility = View.GONE
            if (insight.summary.isNotBlank()) {
                pdInsightSummary.text = insight.summary
                pdInsightSummary.visibility = View.VISIBLE
            } else {
                pdInsightSummary.visibility = View.GONE
            }
            renderInsightTags(insight.tags)
        }
        // Merge Honcho next-steps into the existing list. Curated bullets
        // (from contact-card markdown) sit on top; Honcho-suggested ones
        // appear below in a slightly faded style.
        if (insight.nextSteps.isNotEmpty()) {
            pdNextStepsEmpty.visibility = View.GONE
            insight.nextSteps.forEach { step ->
                val tv = TextView(this).apply {
                    text = "•  $step"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_soft))
                    textSize = 14f
                    alpha = 0.85f
                    setLineSpacing(0f, 1.4f)
                    setPadding(0, 4, 0, 4)
                }
                pdNextSteps.addView(tv)
            }
        }
    }

    private fun renderInsightTags(tags: List<String>) {
        pdInsightTags.removeAllViews()
        if (tags.isEmpty()) {
            pdInsightTags.visibility = View.GONE
            return
        }
        pdInsightTags.visibility = View.VISIBLE
        // Simple inline pill row — wraps via FlexboxLayout would be cleaner
        // but we don't want another dep. Stack horizontally and ellipsize
        // the row if it overflows; tags are short.
        tags.take(6).forEach { tag ->
            val pill = TextView(this).apply {
                text = tag
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.paper))
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                textSize = 10f
                setPadding(14, 6, 14, 6)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = 8
            pill.layoutParams = lp
            pdInsightTags.addView(pill)
        }
    }

    private fun renderTranscriptList(transcripts: List<Api.TranscriptRef>) {
        pdTranscripts.removeAllViews()
        val inflater = LayoutInflater.from(this)
        transcripts.forEach { tx ->
            val row = inflater.inflate(R.layout.item_transcript, pdTranscripts, false)
            row.findViewById<TextView>(R.id.tDate).text = tx.date.ifBlank { tx.name }
            row.findViewById<TextView>(R.id.tDur).text = tx.duration
            row.setOnClickListener {
                showTranscript(currentProspectPhone ?: return@setOnClickListener, tx.name)
            }
            pdTranscripts.addView(row)
        }
    }

    private fun showTranscript(phone: String, noteName: String) {
        hideAllScreens()
        contentTranscript.visibility = View.VISIBLE
        currentScreen = Screen.TRANSCRIPT
        paintNav()
        txTitle.text = noteName
        txBody.text = "loading…"
        scope.launch {
            try {
                val md = withContext(Dispatchers.IO) { Api.fetchTranscript(phone, noteName) }
                txBody.text = md
            } catch (e: Exception) {
                Log.w(TAG, "transcript load failed", e)
                txBody.text = "Could not load transcript: ${e.message}"
            }
        }
    }

    private fun onProspectCallPressed() {
        val number = currentProspectE164 ?: return
        if (!allGranted()) { requestPerms(); return }
        startOutboundCall(number)
    }

    /** ISO timestamp → "2d", "3h", "12m", or absolute date for >7d. */
    private fun humanRelative(iso: String): String {
        if (iso.isBlank()) return ""
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val d = fmt.parse(iso.substringBefore("+").substringBefore(".")) ?: return ""
            val deltaMs = System.currentTimeMillis() - d.time
            val mins = deltaMs / 60_000
            when {
                mins < 1   -> "now"
                mins < 60  -> "${mins}M"
                mins < 1440 -> "${mins / 60}H"
                mins < 7 * 1440 -> "${mins / 1440}D"
                else -> SimpleDateFormat("MMM d", Locale.US).format(d)
            }
        } catch (e: Exception) {
            ""
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    private fun allGranted() = required.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        ActivityCompat.requestPermissions(this, required, PERM_CODE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == PERM_CODE) {
            if (allGranted()) postPermissionSetup()
            else statusText.text = "MIC + PHONE PERMS REQUIRED"
        }
    }

    private fun postPermissionSetup() {
        Telecom.registerAccount(this)
        refreshSetupBanner()
        // Prompt for battery-optimization exemption once. The system dialog
        // remembers the answer; if the user already granted it (or denied),
        // this no-ops on subsequent launches.

    }

    private fun promptIgnoreBatteryOptimizationsOnce() {
        try {
            val pm = getSystemService(android.os.PowerManager::class.java) ?: return
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).setData(android.net.Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "battery-opt prompt failed: ${e.message}")
        }
    }

    private fun refreshSetupBanner() {
        // Outbound calls now go through OutboundCall directly (no Telecom),
        // so the calling-account toggle is no longer required for the basic
        // flow. Keep the banner only when notifications haven't been
        // granted (Android 13+ requires runtime permission for the in-call
        // foreground notification). Telecom is still registered for tel:
        // links from outside the app, but that's optional.
        setupBanner.visibility = View.GONE
        statusText.text = ""
    }

    // ── Call (from system tel: intent → dialer view) ─────────────────────────

    private fun onCallPressed() {
        val number = numberInput.text.toString().trim()
        if (number.isBlank()) { statusText.text = "ENTER A NUMBER FIRST"; return }
        if (!number.startsWith("+")) {
            statusText.text = "MUST START WITH +"; return
        }
        if (!allGranted()) { requestPerms(); return }
        startOutboundCall(number)
        numberInput.text.clear()
        statusText.text = ""
    }

    // ── Outbound call (in-app, foreground) ─────────────────────────────────
    //
    // Bypasses Telecom for outbound. Keeps MainActivity in the foreground
    // for the entire call + a microphone+phoneCall foreground service alive
    // through OutboundCall — together those guarantee Twilio's WebRTC stack
    // can capture the mic. Telecom-routed outbound used to drop our app to
    // background and the mic captured silence (channel 0 silent on the
    // server-side recording).

    private fun startOutboundCall(numberE164: String) {
        if (activeCall != null) {
            Toast.makeText(this, "Call already active", Toast.LENGTH_SHORT).show()
            return
        }
        showInCallOverlay(numberE164)
        speakerOn = false
        val call = OutboundCall(applicationContext, numberE164, object : OutboundCall.Listener {
            override fun onDialing()   { runOnUiThread { icState.text = "CALLING…" } }
            override fun onRinging()   { runOnUiThread { icState.text = "RINGING…" } }
            override fun onConnected() { runOnUiThread { icState.text = "CONNECTED" } }
            override fun onDisconnected(reason: String?) {
                runOnUiThread {
                    icState.text = "ENDED"
                    hideInCallOverlay()
                    activeCall = null
                }
            }
            override fun onError(reason: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Call error: $reason", Toast.LENGTH_LONG).show()
                    icState.text = "ERROR"
                    hideInCallOverlay()
                    activeCall = null
                }
            }
        })
        activeCall = call
        call.start()
    }

    private fun showInCallOverlay(number: String) {
        icNumber.text = number
        icState.text = "CALLING…"
        icMuteBtn.text = "MUTE"
        icSpeakerBtn.text = "SPEAKER"
        contentInCall.visibility = View.VISIBLE
        // Keep screen on during the call so the caller can see status.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideInCallOverlay() {
        contentInCall.visibility = View.GONE
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toggleMute() {
        val call = activeCall ?: return
        val nowMuted = !call.isMuted()
        call.mute(nowMuted)
        icMuteBtn.text = if (nowMuted) "UNMUTE" else "MUTE"
    }

    private fun toggleSpeaker() {
        val call = activeCall ?: return
        speakerOn = !speakerOn
        call.setSpeakerOn(speakerOn)
        icSpeakerBtn.text = if (speakerOn) "EARPIECE" else "SPEAKER"
    }

    // ── Auto-update ──────────────────────────────────────────────────────────

    private fun checkForUpdate() {
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { Api.fetchVersion() }
                if (info.code > BuildConfig.VERSION_CODE) {
                    Log.i(TAG, "Update available: v${info.name} (code ${info.code})")
                    updateBanner.visibility = View.VISIBLE
                    updateBtn.text = "v${info.name}"
                    updateBtn.tag = info.apkUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "Version check failed: ${e.message}")
            }
        }
    }

    private fun triggerUpdate() {
        val apkUrl = updateBtn.tag as? String ?: return
        updateBtn.isEnabled = false
        updateBtn.text = "DOWNLOADING…"

        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { Api.downloadBytes(apkUrl) }

                val dir = File(cacheDir, "updates").also { it.mkdirs() }
                val apk = File(dir, "update.apk")
                apk.writeBytes(bytes)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !packageManager.canRequestPackageInstalls()
                ) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    })
                    statusText.text = "ALLOW UNKNOWN APPS, THEN RETRY"
                    updateBtn.isEnabled = true
                    updateBtn.text = "RETRY"
                    return@launch
                }

                val uri = FileProvider.getUriForFile(
                    this@MainActivity, "$packageName.provider", apk
                )
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)

            } catch (e: Exception) {
                Log.e(TAG, "Update failed", e)
                statusText.text = "UPDATE FAILED: ${e.message}"
                updateBtn.isEnabled = true
                updateBtn.text = "RETRY"
            }
        }
    }
}

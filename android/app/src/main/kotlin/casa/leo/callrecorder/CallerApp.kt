package casa.leo.callrecorder

import android.app.Application

class CallerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("caller", MODE_PRIVATE)
        val base = prefs.getString("base", "") ?: ""
        val key = prefs.getString("key", "") ?: ""
        if (base.isNotEmpty() && key.isNotEmpty()) {
            try { Api.configure(base, key) } catch (_: IllegalArgumentException) { }
        }
    }
}

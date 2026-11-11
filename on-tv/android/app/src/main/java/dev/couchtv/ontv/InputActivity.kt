package dev.couchtv.ontv

import android.app.Activity
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvView
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

/**
 * A TV input (HDMI 1, a console, the antenna) full screen, shown by this app itself through
 * Android's TvView, so the home can switch inputs from its own picker. Fire OS keeps its own input
 * switcher for Amazon's apps (com.amazon.tv.permission.TUNE_INPUT is signature-only), so this plays
 * the input here instead. Back leaves it, and the home is underneath.
 *
 * Start with the input's id: Intent(this, InputActivity::class.java).putExtra("input", id)
 */
class InputActivity : Activity() {
    private var view: TvView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = TvView(this)
        v.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(inputId: String) { Log.w(TAG, "connection failed: $inputId") }
            override fun onDisconnected(inputId: String) { Log.w(TAG, "disconnected: $inputId") }
            override fun onVideoAvailable(inputId: String) { Log.i(TAG, "video: $inputId") }
            override fun onVideoUnavailable(inputId: String, reason: Int) { Log.i(TAG, "no video ($reason): $inputId") }
        })
        view = v
        setContentView(v)
        tune(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); tune(i) }

    private fun tune(i: Intent) {
        val id = i.getStringExtra("input") ?: run { finish(); return }
        Log.i(TAG, "tuning $id")
        try { view?.tune(id, TvContract.buildChannelUriForPassthroughInput(id)) }
        catch (e: Exception) { Log.w(TAG, "tune refused: $id", e); finish() }
    }

    // Leaving the input (Back, Home, an app) ends it, so it never keeps playing behind the home.
    override fun onStop() { super.onStop(); runCatching { view?.reset() }; finish() }

    companion object { private const val TAG = "couchtv.input" }
}

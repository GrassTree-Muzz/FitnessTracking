package com.example.peakmildeffort

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : AppCompatActivity() {

    private val appHost = WebViewAssetLoader.DEFAULT_DOMAIN
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingBackup: String? = null
    private var healthReply: JavaScriptReplyProxy? = null
    private var healthSync: Job? = null

    private val requestHealthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { sendHealthStatus() }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
        fileCallback = null
    }

    private val saveBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val json = pendingBackup
            pendingBackup = null
            if (uri == null) return@registerForActivityResult
            val saved = json != null && runCatching {
                contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
            }.isSuccess
            Toast.makeText(this, if (saved) "Backup saved" else "Backup could not be saved", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The page is light-only, so keep dark system bar icons even in phone dark mode.
        val lightBars = SystemBarStyle.light(Color.TRANSPARENT, Color.argb(0x80, 0x1b, 0x1b, 0x1b))
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)

        val webView = WebView(this)
        val root = FrameLayout(this).apply { addView(webView) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true // required by the tracker's localStorage
        webView.settings.allowFileAccess = false

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val bridgeSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val back = onBackPressedDispatcher.addCallback(this, enabled = false) { webView.goBack() }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme != "https") return true
                if (url.host == appHost) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                back.isEnabled = view.canGoBack()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!bridgeSupported) view.evaluateJavascript(
                    "window.AndroidBackup={postMessage:function(){alert('Backup not saved. Update Android System WebView from Google Play, then try again.')}}",
                    null
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                openBackup.launch(arrayOf("*/*"))
                return true
            }
        }

        if (bridgeSupported) {
            WebViewCompat.addWebMessageListener(webView, "AndroidBackup", setOf("https://$appHost")) { _, message, _, isMainFrame, _ ->
                val json = if (message.type == WebMessageCompat.TYPE_STRING) message.data else null
                if (isMainFrame && !json.isNullOrBlank()) {
                    pendingBackup = json
                    saveBackup.launch("ol-man-muz-${DateFormat.format("yyyy-MM-dd", System.currentTimeMillis())}.json")
                }
            }
            WebViewCompat.addWebMessageListener(webView, "AndroidHealth", setOf("https://$appHost")) { _, message, _, isMainFrame, replyProxy ->
                val json = if (message.type == WebMessageCompat.TYPE_STRING) message.data else null
                if (isMainFrame && !json.isNullOrBlank()) {
                    healthReply = replyProxy
                    onHealthMessage(json)
                }
            }
        }

        webView.loadUrl("https://$appHost/assets/index.html")
    }

    private fun onHealthMessage(json: String) {
        val request = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (request.optString("type")) {
            "status" -> sendHealthStatus()
            "connect" -> {
                val reader = healthReader()
                if (reader == null) sendHealthStatus() else requestHealthPermissions.launch(reader.requestedPermissions())
            }
            "settings" -> runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            "sync" -> {
                val activityDays = request.optLong("activityDays", 365).coerceIn(1, 3650)
                val healthDays = request.optLong("healthDays", 90).coerceIn(1, 365)
                val known = request.optJSONObject("known")?.let { json ->
                    json.keys().asSequence().filter { RECORD_ID.matches(it) }.take(5000).associateWith { json.optLong(it) }
                } ?: emptyMap()
                healthSync?.cancel()
                healthSync = launchHealth("sync") { it.sync(activityDays, healthDays, known) }
            }
            "activity" -> {
                val id = request.optString("id")
                if (RECORD_ID.matches(id)) launchHealth("activity", id) { it.activityDetail(id) }
            }
        }
    }

    private fun sendHealthStatus() {
        launchHealth("status") { it.status() }
    }

    private fun healthReader(): HealthReader? =
        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            HealthReader(HealthConnectClient.getOrCreate(this))
        } else {
            null
        }

    private fun launchHealth(request: String, id: String? = null, read: suspend (HealthReader) -> JSONObject): Job =
        lifecycleScope.launch {
            val reader = healthReader()
            val reply = if (reader == null) {
                JSONObject()
                    .put("type", "status")
                    .put("available", false)
                    .put("updateRequired", HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
            } else {
                try {
                    read(reader)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = if (e is SecurityException) {
                        "Health Connect access is off for some data. Review it in Health Connect."
                    } else {
                        "Health Connect couldn't be read (${e.javaClass.simpleName}). Try again."
                    }
                    JSONObject().put("type", "error").put("request", request).putOpt("id", id).put("message", message)
                }
            }
            runCatching { healthReply?.postMessage(reply.toString()) }
        }

    private companion object {
        val RECORD_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}

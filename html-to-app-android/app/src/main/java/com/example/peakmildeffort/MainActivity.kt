package com.example.peakmildeffort

import android.content.Intent
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
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private val appHost = WebViewAssetLoader.DEFAULT_DOMAIN
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingBackup: String? = null

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
        enableEdgeToEdge()

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
                    saveBackup.launch("peak-mild-effort-${DateFormat.format("yyyy-MM-dd", System.currentTimeMillis())}.json")
                }
            }
        }

        webView.loadUrl("https://$appHost/assets/index.html")
    }
}

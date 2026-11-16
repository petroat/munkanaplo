package hu.munkanaplo.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val appUrl = "https://script.google.com/macros/s/AKfycbySsPaCt9iK76WRELplyrIUrgRUQN5fo6sXeKd_53c4lTNjsPWAHrTBj4y8iqv7iGB59g/exec"

    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingFieldId: String? = null
    private var isUserStopping = false

    companion object {
        private const val REQ_RECORD_AUDIO = 1001
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Állapotsor és navigációs sáv színeinek beállítása
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.parseColor("#f0f2f5")
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.parseColor("#f0f2f5")
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#f0f2f5"))
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#f0f2f5"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout.addView(webView)
        setContentView(rootLayout)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString MunkanaploApp/1.0"
        }

        // Natív Android beszédfelismerő híd regisztrálása a WebView-ba
        webView.addJavascriptInterface(AndroidSpeechBridge(), "AndroidSpeech")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectTopLevelBridge(view)
                dismissGoogleBanner(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val retryHtml = """
                        <!DOCTYPE html>
                        <html lang="hu">
                        <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
                        <body style="font-family:sans-serif;background:#f0f2f5;color:#333;display:flex;flex-direction:column;align-items:center;justify-content:center;height:90vh;text-align:center;padding:20px;">
                            <div style="font-size:48px;margin-bottom:12px;">📡</div>
                            <h2 style="color:#1a73e8;margin:0 0 8px 0;">Nincs internetkapcsolat</h2>
                            <p style="color:#666;font-size:14px;margin-bottom:20px;">A Munkanapló betöltéséhez hálózati kapcsolat szükséges.</p>
                            <button onclick="window.location.href='$appUrl'" style="background:#1a73e8;color:#fff;border:none;padding:12px 24px;border-radius:8px;font-size:16px;font-weight:bold;">Újrapróbálás</button>
                        </body>
                        </html>
                    """.trimIndent()
                    view?.loadDataWithBaseURL(null, retryHtml, "text/html", "UTF-8", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 30) {
                    injectTopLevelBridge(view)
                }
                if (newProgress >= 75) {
                    dismissGoogleBanner(view)
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("Igen") { _, _ -> result?.confirm() }
                    .setNegativeButton("Mégse") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl(appUrl)
    }

    private fun injectTopLevelBridge(view: WebView?) {
        val js = """
            (function() {
                if (window._munkanaploSpeechBridge) return;
                window._munkanaploSpeechBridge = true;
                window.addEventListener('message', function(event) {
                    var d = event.data;
                    if (!d || typeof d !== 'object') return;
                    if (d.type === 'START_ANDROID_SPEECH' && window.AndroidSpeech) {
                        window.AndroidSpeech.startListening(d.fieldId || '');
                    } else if (d.type === 'STOP_ANDROID_SPEECH' && window.AndroidSpeech) {
                        window.AndroidSpeech.stopListening();
                    }
                });
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    inner class AndroidSpeechBridge {
        @JavascriptInterface
        fun startListening(fieldId: String) {
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingFieldId = fieldId
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        REQ_RECORD_AUDIO
                    )
                    return@runOnUiThread
                }
                beginNativeRecognition()
            }
        }

        @JavascriptInterface
        fun stopListening() {
            runOnUiThread {
                isUserStopping = true
                try {
                    speechRecognizer?.stopListening()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingFieldId != null) {
                    pendingFieldId = null
                    beginNativeRecognition()
                }
            } else {
                pendingFieldId = null
                sendSpeechEvent(
                    event = "error",
                    message = "A mikrofon használata nincs engedélyezve az alkalmazásnak."
                )
            }
        }
    }

    private fun beginNativeRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendSpeechEvent(
                event = "error",
                message = "A készüléken nem érhető el a Google beszédfelismerő szolgáltatás."
            )
            return
        }

        isUserStopping = false
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    sendSpeechEvent(event = "ready")
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) {
                        sendSpeechEvent(event = "result", text = text, isFinal = false)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull().orEmpty()
                    if (text.isNotEmpty()) {
                        sendSpeechEvent(event = "result", text = text, isFinal = true)
                    } else {
                        sendSpeechEvent(event = "end")
                    }
                }

                override fun onError(error: Int) {
                    if (isUserStopping && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                        sendSpeechEvent(event = "end")
                        return
                    }
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Hangrögzítési hiba történt."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Nincs mikrofon engedély."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Hálózati hiba a beszédfelismerés közben."
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nem érzékeltem érthető beszédet. Próbáld újra!"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "A beszédfelismerő éppen foglalt, próbáld újra!"
                        SpeechRecognizer.ERROR_SERVER -> "Szerverhiba a beszédfelismerésnél."
                        else -> ""
                    }
                    sendSpeechEvent(event = "error", message = msg)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            sendSpeechEvent(event = "error", message = "Nem sikerült elindítani a mikrofont: ${e.message}")
        }
    }

    private fun sendSpeechEvent(
        event: String,
        text: String = "",
        isFinal: Boolean = false,
        message: String = ""
    ) {
        val json = JSONObject().apply {
            put("type", "ANDROID_SPEECH")
            put("event", event)
            put("text", text)
            put("isFinal", isFinal)
            put("message", message)
        }.toString()

        val js = """
            (function() {
                var msg = $json;
                function broadcast(win) {
                    try { win.postMessage(msg, '*'); } catch (e) {}
                    try {
                        for (var i = 0; i < win.frames.length; i++) {
                            broadcast(win.frames[i]);
                        }
                    } catch (e) {}
                }
                broadcast(window);
            })();
        """.trimIndent()

        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    private fun dismissGoogleBanner(view: WebView?) {
        val js = """
            (function() {
                function hideBanner() {
                    var known = document.getElementById('warning-bar') || document.querySelector('.warning-bar');
                    if (known) {
                        var closeBtn = known.querySelector('[role="button"], button, a[href="#"], .warning-bar-close, #warning-bar-close');
                        if (closeBtn) { try { closeBtn.click(); } catch (e) {} }
                        known.style.display = 'none';
                    }

                    var divs = document.querySelectorAll('div');
                    for (var i = 0; i < divs.length; i++) {
                        var d = divs[i];
                        if (d.querySelector('iframe')) continue;
                        var txt = (d.innerText || '').trim();
                        if (txt.indexOf('Apps Script') !== -1 && d.offsetHeight > 0 && d.offsetHeight < 140) {
                            var buttons = d.querySelectorAll('[role="button"], button, span, div, svg');
                            for (var j = buttons.length - 1; j >= 0; j--) {
                                var b = buttons[j];
                                var rect = b.getBoundingClientRect();
                                if (rect.width > 10 && rect.width < 60 && rect.right > window.innerWidth - 70) {
                                    try { b.click(); } catch (e) {}
                                    break;
                                }
                            }
                            d.style.display = 'none';
                        }
                    }

                    var frame = document.getElementById('sandboxFrame') || document.querySelector('iframe');
                    if (frame) {
                        frame.style.height = '100vh';
                        frame.style.top = '0px';
                    }
                }

                hideBanner();
                setTimeout(hideBanner, 150);
                setTimeout(hideBanner, 500);

                if (!window._bannerObserver && document.body) {
                    window._bannerObserver = new MutationObserver(function() {
                        hideBanner();
                    });
                    window._bannerObserver.observe(document.body, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}

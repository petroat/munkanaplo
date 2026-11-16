package hu.munkanaplo.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val appUrl = "https://script.google.com/macros/s/AKfycbySsPaCt9iK76WRELplyrIUrgRUQN5fo6sXeKd_53c4lTNjsPWAHrTBj4y8iqv7iGB59g/exec"

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

        // Külső keret, amely pontosan a státuszsáv ALATT tartja a WebView-t
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

        // Rendszersávok (státuszsáv + billentyűzet) magasságának figyelembevétele
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Sütik engedélyezése (szükséges a Google Apps Script háttérhívásaihoz)
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
        }

        // Átirányítások tartása a WebView-n belül + Google sáv elrejtése + hálózati hiba kezelése
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
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

        // Natív felugró ablakok (alert és törlés megerősítés) + korai sáv-elrejtés
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
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

        // Vissza gomb kezelése
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
}

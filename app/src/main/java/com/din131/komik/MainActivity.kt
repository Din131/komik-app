package com.din131.komik

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView

    @Volatile private var keepSplash = true
    private var popupView: WebView? = null

    /** Domain yang boleh dibuka di halaman utama (beserta subdomainnya). */
    private val allowedDomains = mutableSetOf<String>()
    private var initialLoad = true
    private var lastBackPress = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen: tampil sampai halaman pertama siap (maksimal 4 detik)
        val splash = installSplashScreen()
        keepSplash = savedInstanceState == null
        splash.setKeepOnScreenCondition { keepSplash }
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate().alpha(0f).setDuration(300L)
                .withEndAction { provider.remove() }.start()
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AdBlocker.init(this)

        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)

        // Jarak untuk status bar, navigasi, notch, dan keyboard (Android 15+)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val startUrl = getString(R.string.start_url)
        Uri.parse(startUrl).host?.let { allowedDomains.add(baseDomain(it)) }

        // Layar tidak mati selama membaca
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // Zoom dengan dua jari untuk halaman komik
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.setBackgroundColor(getColor(R.color.bg))
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { _, _, _, _, _ -> /* abaikan unduhan (biasanya iklan) */ }

        // Tarik untuk refresh hanya saat halaman berada di paling atas
        swipe.setColorSchemeColors(getColor(R.color.accent))
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.bg))
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        swipe.setOnRefreshListener { webView.reload() }

        webView.postDelayed({ keepSplash = false }, 4000)

        errorView.setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    popupView != null -> popupView!!.let { if (it.canGoBack()) it.goBack() else closePopup() }
                    webView.canGoBack() -> webView.goBack()
                    System.currentTimeMillis() - lastBackPress < 2000 -> finish()
                    else -> {
                        lastBackPress = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            initialLoad = false
        } else {
            webView.loadUrl(startUrl)
        }
        // Cek versi baru di GitHub Releases setiap aplikasi dibuka
        if (savedInstanceState == null) UpdateChecker(this).checkInBackground()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        // Simpan cookie login supaya tetap login setelah aplikasi ditutup
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // ---------- Helper ----------

    /** "www.komiku.org" -> "komiku.org" */
    private fun baseDomain(host: String): String {
        val parts = host.lowercase().split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase()
    }

    private fun isAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return allowedDomains.any { h == it || h.endsWith(".$it") }
    }

    /** Halaman login & captcha pihak ketiga yang boleh dibuka. */
    private val authDomains = setOf(
        "accounts.google.com", "accounts.youtube.com", "www.google.com",
        "discord.com", "discordapp.com",
        "facebook.com", "x.com", "twitter.com", "appleid.apple.com",
        "challenges.cloudflare.com", "hcaptcha.com",
        // Firebase Authentication (dipakai login Google di banyak situs, termasuk Komiku)
        "firebaseapp.com", "web.app", "securetoken.googleapis.com", "identitytoolkit.googleapis.com"
    )

    private fun isAuth(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return authDomains.any { h == it || h.endsWith(".$it") }
    }

    private fun blockedToast(host: String?) {
        if (host.isNullOrEmpty()) return
        Toast.makeText(this, getString(R.string.link_blocked, host), Toast.LENGTH_SHORT).show()
    }

    private val emptyResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    // Sembunyikan sisa elemen iklan + izinkan zoom walau situs mematikannya
    private val pageJs = """
        (function(){
          if (!document.getElementById('__komik_css')) {
            var s = document.createElement('style');
            s.id = '__komik_css';
            s.textContent = 'ins.adsbygoogle,.adsbygoogle,[id^="ad-banner"],[class^="ad-banner"],' +
              '[id*="popunder"],[class*="popunder"],[id^="ads-"],[class^="ads-"],' +
              'iframe[src*="/ads/"],iframe[src*="doubleclick"],div[id^="div-gpt-ad"]' +
              '{display:none!important;visibility:hidden!important;height:0!important;}';
            (document.head || document.documentElement).appendChild(s);
          }
          // Tutup otomatis pop-up promo Komiku Plus dengan menekan tombol "Tutup"-nya
          if (!window.__komikPromo) {
            window.__komikPromo = true;
            var PROMO = /ambil diskon|komiku plus|klaim harga promo/i;
            var closePromo = function () {
              var btns = document.querySelectorAll('button, a, [role=button], .btn');
              for (var i = 0; i < btns.length; i++) {
                var b = btns[i];
                if (!/^\s*(tutup|close)\s*$/i.test(b.textContent || '')) continue;
                if (!b.offsetParent && getComputedStyle(b).position !== 'fixed') continue; // tidak terlihat
                // Cari wadah pop-up terdekat (modal/dialog), lalu pastikan isinya promo
                var p = b.parentElement, box = null;
                for (var d = 0; p && p !== document.body && d < 12; d++, p = p.parentElement) {
                  var cls = (p.className && p.className.baseVal === undefined ? p.className : '') || '';
                  if (p.getAttribute('role') === 'dialog' || /modal|popup|swal|dialog/i.test(cls) ||
                      getComputedStyle(p).position === 'fixed') { box = p; break; }
                }
                if (box && PROMO.test(box.textContent || '')) b.click();
              }
            };
            closePromo();
            var pending = false;
            new MutationObserver(function () {
              if (pending) return;
              pending = true;
              setTimeout(function () { pending = false; closePromo(); }, 250);
            })
              .observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
          }
          var m = document.querySelector('meta[name=viewport]');
          if (!m) { m = document.createElement('meta'); m.name = 'viewport'; (document.head || document.documentElement).appendChild(m); }
          m.content = 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=5, user-scalable=yes';
        })();
    """.trimIndent()

    // ---------- WebViewClient: adblock + kunci domain ----------

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host
            // Jangan blokir situs utama maupun layanan login
            if (!isAllowed(host) && !isAuth(host) && AdBlocker.isBlocked(host)) return emptyResponse
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()

            // intent://, market://, dll. biasanya redirect iklan ke aplikasi lain
            if (scheme != "http" && scheme != "https") {
                return scheme != "about" && scheme != "blob" && scheme != "data" && scheme != "javascript"
            }

            val host = url.host
            if (AdBlocker.isBlocked(host) && !isAllowed(host)) return true
            if (!request.isForMainFrame) return false
            if (isAllowed(host) || isAuth(host)) return false

            // Saat pertama buka, ikuti redirect domain (misalnya situs pindah domain)
            if (initialLoad && request.isRedirect && host != null) {
                allowedDomains.add(baseDomain(host))
                return false
            }

            blockedToast(host)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            errorView.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            keepSplash = false
            initialLoad = false
            swipe.isRefreshing = false
            view.evaluateJavascript(pageJs, null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                errorView.visibility = View.VISIBLE
                swipe.isRefreshing = false
            }
        }
    }

    // ---------- WebChromeClient: progress & pop-up ----------

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress >= 80) keepSplash = false
            progress.progress = newProgress
            progress.visibility = if (newProgress < 100 && !swipe.isRefreshing) View.VISIBLE else View.GONE
        }

        /**
         * Tab/jendela baru: tangkap URL tujuannya.
         * - Situs yang sama  -> buka di jendela utama
         * - Halaman login (Google, Discord, dll.) -> tampilkan sebagai jendela pop-up
         * - Domain lain (iklan) -> buang
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val temp = WebView(this@MainActivity)
            setupWebView(temp)
            temp.webViewClient = object : WebViewClient() {
                private var decided = false

                /** true = batalkan pemuatan */
                private fun decide(url: Uri?): Boolean {
                    val scheme = url?.scheme?.lowercase()
                    if (url == null || scheme == "about" || scheme == "javascript") return false
                    decided = true
                    return when {
                        isAuth(url.host) -> { showPopup(temp); false }
                        isAllowed(url.host) -> {
                            webView.loadUrl(url.toString())
                            temp.post { temp.destroy() }
                            true
                        }
                        else -> {
                            blockedToast(url.host)
                            temp.post { temp.destroy() }
                            true
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    if (decided) return false
                    return decide(request.url)
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (!decided && decide(url?.let { Uri.parse(it) })) v.stopLoading()
                }
            }
            temp.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) = closePopup()
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }

    // ---------- Pop-up login ----------

    private fun showPopup(popup: WebView) {
        if (popupView === popup) return
        closePopup()
        popupView = popup
        findViewById<FrameLayout>(R.id.root).addView(popup, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun closePopup() {
        val p = popupView ?: return
        popupView = null
        findViewById<FrameLayout>(R.id.root).removeView(p)
        p.post { p.destroy() }
        CookieManager.getInstance().flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(w: WebView) {
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(getColor(R.color.bg))
    }
}

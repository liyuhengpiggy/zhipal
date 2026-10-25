package cn.seniorvoice.demo

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class VisualDemoActivity : Activity() {
    private var demoWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFEDF2EF.toInt())
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(16), dp(6))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        toolbar.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })
        toolbar.addView(TextView(this).apply {
            text = "完整视觉测试版"
            textSize = 18f
            setTextColor(0xFF1D2B25.toInt())
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val webView = WebView(this).apply {
            setBackgroundColor(0xFFEDF2EF.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = false
                allowFileAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportZoom(false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    blockExternalNavigation(request.url.toString())

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    blockExternalNavigation(url)
            }
        }
        demoWebView = webView
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)

        val html = assets.open("voice-life-demo.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        webView.loadDataWithBaseURL(DEMO_ORIGIN, html, "text/html", "UTF-8", null)
    }

    private fun blockExternalNavigation(url: String): Boolean {
        val isLocalDemo = url.startsWith(DEMO_ORIGIN)
        if (!isLocalDemo) Toast.makeText(this, "测试页不会打开外部链接", Toast.LENGTH_SHORT).show()
        return !isLocalDemo
    }

    override fun onPause() {
        demoWebView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        demoWebView?.onResume()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        demoWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
            webViewClient = WebViewClient()
            removeAllViews()
            destroy()
        }
        demoWebView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val DEMO_ORIGIN = "https://voice-life-demo.local/"
    }
}

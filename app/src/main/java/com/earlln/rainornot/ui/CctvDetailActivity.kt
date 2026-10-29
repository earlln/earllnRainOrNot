package com.earlln.rainornot.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.earlln.rainornot.databinding.ActivityCctvDetailBinding

class CctvDetailActivity : AppCompatActivity() {
    private lateinit var b: ActivityCctvDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCctvDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        val name = intent.getStringExtra(EXTRA_NAME) ?: "CCTV"
        val url = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "1"
        b.tvCctvName.text = name
        b.tvStreamUrl.text = url

        if (url.isBlank()) {
            b.tvError.text = "스트림 URL이 없습니다."
            b.tvError.visibility = View.VISIBLE
        } else {
            setupWebView(url, type)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.webView.canGoBack()) b.webView.goBack() else finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String, type: String) {
        b.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        b.webView.webChromeClient = WebChromeClient()
        b.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                b.progressBar.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    b.progressBar.visibility = View.GONE
                    b.tvError.text = "영상을 불러올 수 없습니다.\n(${error?.description})"
                    b.tvError.visibility = View.VISIBLE
                }
            }
        }
        b.progressBar.visibility = View.VISIBLE

        val html = if (type == "3") {
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=yes, minimum-scale=1, maximum-scale=8">
            <meta http-equiv="refresh" content="10">
            <style>body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh}
            img{width:100%;display:block;touch-action:pinch-zoom}</style>
            </head><body><img src="$url" alt="CCTV 이미지"/></body></html>
            """.trimIndent()
        } else {
            """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=yes, minimum-scale=1, maximum-scale=5">
            <style>body{margin:0;background:#000;display:flex;align-items:center;justify-content:center;height:100vh;overflow:hidden}
            video{width:100%;max-height:100vh;touch-action:pinch-zoom}</style>
            </head><body>
            <video autoplay muted playsinline controls>
              <source src="$url" type="application/x-mpegURL">
              <source src="$url" type="video/mp4">
              <p style="color:white">영상을 재생할 수 없습니다.</p>
            </video></body></html>
            """.trimIndent()
        }
        b.webView.loadDataWithBaseURL("https://topis.seoul.go.kr/", html, "text/html", "UTF-8", null)
    }

    override fun onDestroy() {
        b.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TYPE = "extra_type"
    }
}

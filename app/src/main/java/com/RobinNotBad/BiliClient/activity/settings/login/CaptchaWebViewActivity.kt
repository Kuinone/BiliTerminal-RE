package com.RobinNotBad.BiliClient.activity.settings.login

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.util.CaptchaParamPolicy
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import org.json.JSONObject

class CaptchaWebViewActivity : BaseActivity() {

    companion object {
        const val EXTRA_GT = "gt"
        const val EXTRA_CHALLENGE = "challenge"
        const val RESULT_CHALLENGE = "geetest_challenge"
        const val RESULT_VALIDATE = "geetest_validate"
        const val RESULT_SECCODE = "geetest_seccode"

        /**
         * 本设备建不出 WebView，人机验证根本打不开。
         *
         * 与「用户主动取消」分开报，调用方才能给出正确的下一步提示（改用扫码登录），
         * 而不是误导性的「验证已取消」。
         */
        const val RESULT_UNAVAILABLE = RESULT_FIRST_USER
        private const val ZOOM_STEP = 25
        private const val ZOOM_MIN = 50
        private const val ZOOM_MAX = 300
        private const val ZOOM_DEFAULT = 100
    }

    private var webView: WebView? = null
    private var mGt: String? = null
    private var mChallenge: String? = null
    private var currentZoom = ZOOM_DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先探一次「本进程能不能建出 WebView」，再决定要不要铺页面。
        //
        // 26.10.14 线上崩溃：小米手表（M2505W1 / SDK 34）上本页 100% 崩——
        // WebViewFactory.getProvider() 抛 UnsupportedOperationException，而原因是**进程被赋予
        // 了特权 uid**（框架层禁止在 root/system 进程里创建 WebView）。布局里写死 <WebView> 意味着
        // 这类设备连页面都 inflate 不出来，异常直接冒到 ActivityThread 把应用带崩。
        // 详见 docs/review/fix-progress.md §3.15。
        if (!isWebViewAvailable()) {
            MsgUtil.showMsgLong(getString(R.string.msg_long_captchawebviewactivity_001))
            setResult(RESULT_UNAVAILABLE)
            finish()
            return
        }

        setContentView(R.layout.activity_captcha_webview)
        setPageName(getString(R.string.pagename_captchawebviewactivity_001))

        mGt = intent.getStringExtra(EXTRA_GT)
        mChallenge = intent.getStringExtra(EXTRA_CHALLENGE)

        // 安全审计 P2-8：gt / challenge 会被拼进 WebView 执行的 JS，先用白名单卡一道。
        //
        // 这两个值来自 B 站接口、经 Intent 转手进来；此前直接把它们塞进单引号字符串，
        // 参数里只要有引号、换行或 `</script>`，就能改写我们要执行的脚本
        // （参数来自服务端，等价于远程可执行代码）。官方取值是 32 位十六进制，
        // 规则见 CaptchaParamPolicy；不合法就按「验证参数错误」结束，
        // 且**不打印原值**——可疑内容不该再进日志。
        if (!CaptchaParamPolicy.areValid(mGt, mChallenge)) {
            Logu.w("CaptchaWebView", "gt/challenge 未通过格式校验，拒绝加载验证页")
            MsgUtil.showMsg(getString(R.string.msg_captchawebviewactivity_001))
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        initZoomControls()
        initWebView()
    }

    /**
     * 探测本进程能否创建 WebView。
     *
     * 用「建一个再销毁」而不是读某个标志位：WebView 能不能用取决于**运行期**的进程 uid 与
     * ROM 里的 WebView 实现，没有可靠的公开 API 可以静态判断（`WebViewFactory` 是 @hide）。
     * 探针本身就是真实创建，所以结论一定准；代价只有一次对象构造。
     *
     * 捕获 `Throwable` 而不是 `Exception`：现场抛的 `UnsupportedOperationException` 虽是 Exception，
     * 但部分 ROM 的 WebView 缺失路径会抛 `Error` 子类，用 Throwable 才兜得住。
     */
    private fun isWebViewAvailable(): Boolean = try {
        WebView(this).destroy()
        true
    } catch (t: Throwable) {
        Logu.w("CaptchaWebView", "WebView 不可用，人机验证降级：${t.javaClass.simpleName}: ${t.message}")
        false
    }

    private fun initZoomControls() {
        val btnZoomIn = findViewById<Button>(R.id.btnZoomIn)
        val btnZoomOut = findViewById<Button>(R.id.btnZoomOut)
        val btnZoomReset = findViewById<Button>(R.id.btnZoomReset)

        btnZoomIn.setOnClickListener { zoomIn() }
        btnZoomOut.setOnClickListener { zoomOut() }
        btnZoomReset.setOnClickListener { resetZoom() }
    }

    private fun zoomIn() {
        if (currentZoom >= ZOOM_MAX) return
        currentZoom += ZOOM_STEP
        applyZoom()
    }

    private fun zoomOut() {
        if (currentZoom <= ZOOM_MIN) return
        currentZoom -= ZOOM_STEP
        applyZoom()
    }

    private fun resetZoom() {
        currentZoom = ZOOM_DEFAULT
        applyZoom()
    }

    private fun applyZoom() {
        webView?.evaluateJavascript(
            "document.querySelector('meta[name=\"viewport\"]').setAttribute('content', 'width=device-width, initial-scale=${currentZoom / 100.0}, maximum-scale=${ZOOM_MAX / 100.0}, user-scalable=yes');",
            null
        )
    }

    @Suppress("SetJavaScriptEnabled")
    private fun initWebView() {
        // 运行期建 WebView 再塞进容器（容器见 activity_captcha_webview.xml）。
        // 这里再兜一层 try/catch：探针过了不代表后续构造一定成功（WebView 提供者进程可能刚被回收）。
        val container = findViewById<ViewGroup>(R.id.webViewContainer)
        val created = try {
            WebView(this)
        } catch (t: Throwable) {
            Logu.e("CaptchaWebView", "WebView 创建失败", t)
            MsgUtil.showMsgLong(getString(R.string.msg_long_captchawebviewactivity_001))
            setResult(RESULT_UNAVAILABLE)
            finish()
            return
        }
        created.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.addView(created)
        webView = created

        val settings = created.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // 验证码页面无需本地文件访问，关闭危险开关以降低攻击面
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.allowFileAccessFromFileURLs = false
        // 极验脚本为 https，禁止混合内容（setMixedContentMode 是 API 21；Android 4.4 上 WebView
        // 默认就不允许 https 页面加载 http 子资源，跳过等价）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // setAcceptThirdPartyCookies 是 API 21；4.4 的 WebView 默认就接受第三方 Cookie，跳过等价
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(created, true)
        }

        created.addJavascriptInterface(CaptchaJsInterface(), "Android")
        created.webViewClient = CustomWebViewClient()

        val html = buildCaptchaHtml()
        created.loadDataWithBaseURL("https://www.bilibili.com/", html, "text/html", "UTF-8", null)
    }

    private fun buildCaptchaHtml(): String {
        // 安全审计 P2-8：用 JSONObject.quote 生成 JS 字符串字面量，不再手工拼单引号。
        //
        // quote() 会把 `"` `\` 和换行等控制字符转义成合法字面量，参数里带引号也逃不出字符串；
        // 但它**不转义 `</script>`**（HTML 解析器会先于 JS 结束脚本块），
        // 所以它是第二道防线，第一道是 onCreate 里的 CaptchaParamPolicy 白名单，缺一不可。
        val gtLiteral = JSONObject.quote(mGt)
        val challengeLiteral = JSONObject.quote(mChallenge)
        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "<meta charset=\"utf-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes\">\n" +
                "<style>\n" +
                "  * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
                "  body { display: flex; justify-content: center; align-items: center; min-height: 100vh; background: #f5f5f5; padding: 16px; }\n" +
                "  #captcha { width: 100%; max-width: 320px; }\n" +
                "  .loading { text-align: center; color: #666; padding: 40px 0; font-size: 14px; }\n" +
                "  .error { text-align: center; color: #e74c3c; padding: 40px 0; font-size: 14px; }\n" +
                "  .retry-btn { margin-top: 12px; padding: 10px 24px; background: #00a1d6; color: white; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div id=\"captcha\"><div class=\"loading\">正在加载验证码...</div></div>\n" +
                "<script>\n" +
                "var gt = " + gtLiteral + ";\n" +
                "var challenge = " + challengeLiteral + ";\n" +
                "var captchaEl = document.getElementById('captcha');\n" +
                "\n" +
                "function loadScript(url, callback) {\n" +
                "  var script = document.createElement('script');\n" +
                "  script.type = 'text/javascript';\n" +
                "  script.src = url;\n" +
                "  script.onload = function() { callback(null); };\n" +
                "  script.onerror = function() { callback(new Error('Load failed')); };\n" +
                "  document.body.appendChild(script);\n" +
                "}\n" +
                "\n" +
                "function initCaptcha() {\n" +
                "  captchaEl.innerHTML = '<div class=\"loading\">正在加载验证码...</div>';\n" +
                "  \n" +
                "  loadScript('https://static.geetest.com/static/js/gt.0.4.9.js', function(err) {\n" +
                "    if (err) {\n" +
                "      captchaEl.innerHTML = '<div class=\"error\">加载核心库失败<br><button class=\"retry-btn\" onclick=\"initCaptcha()\">重试</button></div>';\n" +
                "      return;\n" +
                "    }\n" +
                "    \n" +
                "    loadScript('https://static.geetest.com/static/js/click.3.1.2.js', function(err2) {\n" +
                "      if (err2) {\n" +
                "        captchaEl.innerHTML = '<div class=\"error\">加载验证库失败<br><button class=\"retry-btn\" onclick=\"initCaptcha()\">重试</button></div>';\n" +
                "        return;\n" +
                "      }\n" +
                "      \n" +
                "      if (typeof initGeetest === 'undefined') {\n" +
                "        captchaEl.innerHTML = '<div class=\"error\">初始化失败<br><button class=\"retry-btn\" onclick=\"initCaptcha()\">重试</button></div>';\n" +
                "        return;\n" +
                "      }\n" +
                "      \n" +
                "      initGeetest({\n" +
                "        gt: gt,\n" +
                "        challenge: challenge,\n" +
                "        new_captcha: true,\n" +
                "        product: 'popup',\n" +
                "        offline: false,\n" +
                "        type: 'click',\n" +
                "        https: true\n" +
                "      }, function(captchaObj) {\n" +
                "        captchaEl.innerHTML = '';\n" +
                "        captchaObj.appendTo('#captcha');\n" +
                "        \n" +
                "        captchaObj.onReady(function() {\n" +
                "          console.log('Captcha ready');\n" +
                "        });\n" +
                "        \n" +
                "        captchaObj.onSuccess(function() {\n" +
                "          var result = captchaObj.getValidate();\n" +
                "          if (result && result.geetest_validate) {\n" +
                "            var seccode = result.geetest_seccode || (result.geetest_validate + '|jordan');\n" +
                "            Android.onCaptchaResult(\n" +
                "              result.geetest_challenge || challenge,\n" +
                "              result.geetest_validate,\n" +
                "              seccode\n" +
                "            );\n" +
                "          } else {\n" +
                "            Android.onCaptchaError('验证结果为空');\n" +
                "          }\n" +
                "        });\n" +
                "        \n" +
                "        captchaObj.onError(function(err) {\n" +
                "          Android.onCaptchaError('验证失败: ' + (err && err.msg || '网络错误'));\n" +
                "        });\n" +
                "        \n" +
                "        captchaObj.verify();\n" +
                "      });\n" +
                "    });\n" +
                "  });\n" +
                "}\n" +
                "\n" +
                "initCaptcha();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>"
    }

    private inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return false
        }
    }

    private inner class CaptchaJsInterface {
        @JavascriptInterface
        fun onCaptchaResult(challenge: String, validate: String, seccode: String) {
            runOnUiThread {
                val data = Intent()
                data.putExtra(RESULT_CHALLENGE, challenge)
                data.putExtra(RESULT_VALIDATE, validate)
                data.putExtra(RESULT_SECCODE, seccode)
                setResult(RESULT_OK, data)
                finish()
            }
        }

        @JavascriptInterface
        fun onCaptchaError(errorMsg: String) {
            runOnUiThread {
                MsgUtil.showMsg(errorMsg)
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    override fun onDestroy() {
        webView?.let {
            it.destroy()
            webView = null
        }
        super.onDestroy()
    }
}
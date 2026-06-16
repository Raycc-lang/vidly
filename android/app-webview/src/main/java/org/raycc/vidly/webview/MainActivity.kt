package org.raycc.vidly.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import org.raycc.vidly.R
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.widget.FrameLayout
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var pendingWebPermission: PermissionRequest? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var currentRoom = ""
    private var currentUsername = ""
    private var callActive = false
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var lastLoadedUrl = BASE_URL

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)

        CallService.ensureChannel(this)
        SignalingListener.ensureChannels(this)
        requestRuntimePermissions()

        webView = findViewById(R.id.webView)
        WebView.setWebContentsDebuggingEnabled(true)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        webView.addJavascriptInterface(NativeBridge(), "VidlyNative")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "voice.raycc.org") return false
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                }.getOrDefault(false)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                lastLoadedUrl = url
                Log.d("Vidly", "onPageFinished: $url")
                injectNativeBridgeHooks()
                syncCallStateFromUrl(url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (hasMediaPermissions()) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermission = request
                        requestRuntimePermissions()
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val intent = fileChooserParams.createIntent()
                return try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileChooserCallback = null
                    false
                }
            }
        }

        setupProximitySensor()
        Log.d("Vidly", "onCreate: savedInstanceState=${savedInstanceState != null}")
        if (savedInstanceState != null) {
            lastLoadedUrl = savedInstanceState.getString("lastUrl") ?: BASE_URL
            Log.d("Vidly", "onCreate: restored lastUrl=$lastLoadedUrl")
        }
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            val url = if (savedInstanceState != null) lastLoadedUrl else urlFromIntent(intent)
            Log.d("Vidly", "onCreate: loading url=$url")
            webView.loadUrl(url)
        } else {
            Log.d("Vidly", "onCreate: restoreState succeeded, webView.url=${webView.url}")
        }
        SignalingListener.configure(this, currentRoom, currentUsername)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d("Vidly", "onNewIntent: data=${intent.data}, extras=${intent.extras}")
        val url = urlFromIntent(intent)
        // Only navigate if the intent actually has a room URL;
        // onNewIntent fires with null data when returning from background
        if (url != BASE_URL) {
            webView.loadUrl(url)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("lastUrl", lastLoadedUrl)
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        SignalingListener.setAppForeground(this, true)
    }

    override fun onResume() {
        super.onResume()
        Log.d("Vidly", "onResume: webView.url=${webView.url}, callActive=$callActive")
        webView.onResume()
    }

    override fun onPause() {
        Log.d("Vidly", "onPause: webView.url=${webView.url}, callActive=$callActive")
        if (!callActive) webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        Log.d("Vidly", "onStop")
        SignalingListener.setAppForeground(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        unregisterProximity()
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (callActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        webView.evaluateJavascript(
            "window.dispatchEvent(new Event('${if (isInPictureInPictureMode) "vidly-native-pip-enter" else "vidly-native-pip-exit"}'))",
            null
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileChooserCallback?.onReceiveValue(result)
            fileChooserCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST && hasMediaPermissions()) {
            pendingWebPermission?.grant(pendingWebPermission?.resources ?: emptyArray())
            pendingWebPermission = null
        } else {
            pendingWebPermission?.deny()
            pendingWebPermission = null
        }
    }

    private fun urlFromIntent(intent: Intent?): String {
        val room = intent?.getStringExtra(SignalingListener.EXTRA_ROOM).orEmpty()
        if (room.isNotBlank()) return "$BASE_URL/room/${Uri.encode(room)}"
        val data = intent?.data
        if (data?.host == "voice.raycc.org") return data.toString()
        return BASE_URL
    }

    private fun syncCallStateFromUrl(url: String) {
        val room = roomFromUrl(url)
        if (room.isNotBlank()) {
            setCallActive(true, room, currentUsername)
        } else {
            setCallActive(false, "", currentUsername)
        }
    }

    private fun setCallActive(active: Boolean, room: String, username: String) {
        if (username.isNotBlank()) currentUsername = username

        val changed = active != callActive || room != currentRoom
        currentRoom = room
        callActive = active

        // Only reconfigure signaling when room or username actually changes
        SignalingListener.configure(this, currentRoom, currentUsername)

        if (!changed) return

        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            CallService.start(this)
            registerProximity()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            CallService.stop(this)
            unregisterProximity()
        }
    }

    private fun roomFromUrl(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return ""
        val segments = uri.pathSegments
        return if (segments.size == 2 && segments[0] == "room") segments[1] else ""
    }

    private fun requestRuntimePermissions() {
        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST)
        }
    }

    private fun hasMediaPermissions(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupProximitySensor() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "Vidly:ProximityWakeLock"
            ).apply { setReferenceCounted(false) }
        }
    }

    // Hold PROXIMITY_SCREEN_OFF_WAKE_LOCK during the call and let the system
    // (PowerManagerService) drive screen off/on from the proximity sensor. We do
    // NOT register our own SensorEventListener: a second consumer reacting to the
    // same sensor jitter fights the system at the near/far boundary and causes
    // screen blackout/flicker.
    private fun registerProximity() {
        val lock = proximityWakeLock ?: return
        if (!lock.isHeld) lock.acquire()
    }

    private fun unregisterProximity() {
        proximityWakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun injectNativeBridgeHooks() {
        webView.evaluateJavascript(
            """
            (function() {
              if (window.__vidlyNativeInstalled || !window.VidlyNative) return;
              window.__vidlyNativeInstalled = true;
              function room() {
                var match = location.pathname.match(/^\/room\/([A-Za-z0-9_-]+)$/);
                return match ? match[1] : '';
              }
              function username() {
                try { return localStorage.getItem('vidly-username') || ''; } catch (e) { return ''; }
              }
              function active() {
                var call = document.getElementById('call');
                return !!room() && (!call || call.classList.contains('active'));
              }
              function notifyNative() {
                try { window.VidlyNative.onCallState(active(), room(), username(), location.href); } catch (e) {}
              }
              ['pushState', 'replaceState'].forEach(function(name) {
                var original = history[name];
                history[name] = function() {
                  var result = original.apply(this, arguments);
                  setTimeout(notifyNative, 0);
                  return result;
                };
              });
              window.addEventListener('popstate', notifyNative);
              window.addEventListener('visibilitychange', notifyNative);
              document.addEventListener('click', function() { setTimeout(notifyNative, 150); }, true);
              var call = document.getElementById('call');
              if (call && window.MutationObserver) {
                new MutationObserver(notifyNative).observe(call, { attributes: true, attributeFilter: ['class'] });
              }
              setInterval(notifyNative, 2000);
              notifyNative();
            })();
            """.trimIndent(),
            null
        )
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun onCallState(active: Boolean, room: String?, username: String?, url: String?) {
            runOnUiThread {
                val detectedRoom = room?.takeIf { it.isNotBlank() } ?: roomFromUrl(url.orEmpty())
                setCallActive(active || detectedRoom.isNotBlank(), detectedRoom, username.orEmpty())
            }
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    })
                }
            }
        }
    }

    companion object {
        private const val BASE_URL = "https://voice.raycc.org"
        private const val PERMISSIONS_REQUEST = 4001
        private const val FILE_CHOOSER_REQUEST = 4002
    }
}

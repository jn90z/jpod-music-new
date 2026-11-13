package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.music.spotui.data.preferences.getSpotiflacSession
import com.music.spotui.data.preferences.saveSpotiflacSession
import com.music.spotui.lossless.LosslessRegistry
import com.music.spotui.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gets a signed SpotiFLAC community session, the way the official client does:
 * bootstrap → the service's own hosted challenge page (the user solves Cloudflare
 * Turnstile there) → redirect to [CALLBACK] with a grant → exchange the grant for a
 * session. The app only loads the page and catches the redirect.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotiflacVerifyScreen(navController: NavController, next: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var message by remember { mutableStateOf("Loading verification…") }
    var attempt by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (next == "home") {
            navController.navigate(Routes.Home.route) {
                popUpTo(Routes.MusicSource.route) { inclusive = true }
            }
        } else navController.popBackStack()
    }
    // Back must never trap the user here (onboarding has nothing behind this screen).
    androidx.activity.compose.BackHandler(onBack = leave)
    DisposableEffect(Unit) {
        onDispose { webView?.destroy(); webView = null }
    }

    // Reused so a retry keeps the same install; the service binds the session to it.
    val installId = remember {
        getSpotiflacSession(context)?.installId?.takeIf { it.isNotBlank() }
            ?: java.security.SecureRandom().let { r -> (1..16).joinToString("") { "%02x".format(r.nextInt(256)) } }
    }
    val state = remember(attempt) {
        java.security.SecureRandom().let { r -> (1..16).joinToString("") { "%02x".format(r.nextInt(256)) } }
    }

    fun finish(grant: String) = scope.launch {
        message = "Creating secure session…"
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("grant", grant).put("install_id", installId)
                    .put("app_version", APP_VERSION).put("platform", PLATFORM)
                val o = JSONObject(post("${LosslessRegistry.communityVerifyUrl()}/session/exchange", body.toString()))
                val id = o.optString("session_id")
                val secret = o.optString("session_secret")
                require(id.isNotBlank() && secret.isNotBlank())
                saveSpotiflacSession(context, id, secret, installId, o.optString("expires_at"))
            }.isSuccess
        }
        if (ok) {
            done = true
            android.widget.Toast.makeText(context, "SpotiFLAC session captured", android.widget.Toast.LENGTH_LONG).show()
            leave()
        } else {
            message = "Could not finish verification. Tap Retry or Skip for now."
        }
    }

    LaunchedEffect(attempt) {
        message = "Loading verification…"
        val challenge = withContext(Dispatchers.IO) {
            runCatching {
                val base = LosslessRegistry.communityVerifyUrl()
                val url = "$base/bootstrap?install_id=$installId&app_version=$APP_VERSION&platform=$PLATFORM"
                JSONObject(get(url)).optString("challenge_url").takeIf { it.startsWith("https://") }
            }.getOrNull()
        }
        if (challenge == null) {
            message = "Could not start verification. Tap Retry or Skip for now."
            return@LaunchedEffect
        }
        message = ""
        webView?.loadUrl("$challenge&cb=" + URLEncoder.encode("$CALLBACK?state=$state", "UTF-8"))
        // Never leave the user on a page that silently stalled.
        delay(180_000)
        if (!done) message = "Verification timed out. Tap Retry or Skip for now."
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(top = 40.dp, bottom = 56.dp),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    webView = this
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (!uri.toString().startsWith(CALLBACK)) return false
                            val grant = uri.getQueryParameter("grant").orEmpty()
                            if (uri.getQueryParameter("state") != state || grant.isBlank()) {
                                message = "Verification was rejected. Tap Retry or Skip for now."
                            } else {
                                finish(grant)
                            }
                            return true
                        }
                    }
                }
            },
        )

        if (message.isNotEmpty()) {
            Text(
                message,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().height(56.dp).background(Color.Black),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = { attempt++ }) { Text("Retry", color = Color.White) }
            TextButton(onClick = leave) { Text("Skip for now", color = Color.White) }
        }

        Box(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(40.dp).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "SpotiFLAC verification",
                color = Color(0xFF00C7B7),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
    }
}

// Must match the signing in SpotiflacGated: the session is bound to both.
private const val APP_VERSION = "4.8.5"
private const val PLATFORM = "android"
private const val CALLBACK = "spotiflac://session-grant"

private fun get(url: String): String = request(url, null)

private fun post(url: String, json: String): String = request(url, json)

private fun request(url: String, json: String?): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 20_000
        setRequestProperty("Accept", "application/json")
        if (json != null) {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
    }
    try {
        if (json != null) conn.outputStream.use { it.write(json.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return text
    } finally {
        conn.disconnect()
    }
}

package com.spotifyclone.app.spotify

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import com.spotifyclone.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Spotify OAuth 2.0 with PKCE for the Android app.
 *
 * Requires a Spotify Developer app (https://developer.spotify.com/dashboard)
 * with redirect URI "spotifyclone://callback" and its Client ID stored in
 * local.properties as SPOTIFY_CLIENT_ID (exposed via BuildConfig).
 */
object SpotifyAuthManager {

    private const val AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"
    private const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
    const val REDIRECT_URI = "spotifyclone://callback"

    private val SCOPES = listOf(
        "user-read-private",
        "user-read-email",
        "playlist-read-private",
        "playlist-read-collaborative",
        "user-library-read",
        "user-top-read"
    ).joinToString(" ")

    private const val PREFS_NAME = "spotify_auth_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_ms"
    private const val KEY_CODE_VERIFIER = "pkce_verifier"
    private const val KEY_STATE = "pkce_state"

    private lateinit var prefs: SharedPreferences
    private val mutex = Mutex()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isConnected.value = !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrEmpty() ||
            !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrEmpty()
    }

    fun isConfigured(): Boolean = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()

    fun isLoggedIn(): Boolean {
        if (!::prefs.isInitialized) return false
        return !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrEmpty() ||
            !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrEmpty()
    }

    /** Build the Spotify authorize URL. Caller opens it in a Custom Tab. */
    fun buildAuthorizationUrl(): String {
        val verifier = randomUrlSafe(64)
        val challenge = base64UrlEncode(sha256(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(16)
        prefs.edit()
            .putString(KEY_CODE_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .apply()

        val params = listOf(
            "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPES,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "state" to state
        ).joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }

        return "$AUTH_ENDPOINT?$params"
    }

    /** Handle the spotifyclone://callback deep link. Returns true on success. */
    suspend fun handleCallback(uri: Uri): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val error = uri.getQueryParameter("error")
                if (error != null) return@withContext false

                val returnedState = uri.getQueryParameter("state")
                val expectedState = prefs.getString(KEY_STATE, null)
                if (returnedState == null || returnedState != expectedState) return@withContext false

                val code = uri.getQueryParameter("code") ?: return@withContext false
                val verifier = prefs.getString(KEY_CODE_VERIFIER, null) ?: return@withContext false

                val form = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", REDIRECT_URI)
                    .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                    .add("code_verifier", verifier)
                    .build()

                val request = Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .post(form)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@withContext false
                    if (!response.isSuccessful) return@withContext false
                    val json = JSONObject(body)
                    saveTokens(json)
                    _isConnected.value = true
                    true
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Returns a valid (auto-refreshed) access token, or null if logged out. */
    suspend fun getAccessToken(): String? = mutex.withLock {
        if (!::prefs.isInitialized) return@withLock null
        withContext(Dispatchers.IO) {
            val token = prefs.getString(KEY_ACCESS_TOKEN, null)
            val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
            if (!token.isNullOrEmpty() && expiresAt > System.currentTimeMillis() + 60_000) {
                return@withContext token
            }

            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
                ?: return@withContext token?.takeIf { expiresAt > System.currentTimeMillis() }

            try {
                val form = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", BuildConfig.SPOTIFY_CLIENT_ID)
                    .build()

                val request = Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .post(form)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        if (response.code == 400 || response.code == 401) {
                            // Refresh token revoked/expired -> force re-login
                            prefs.edit()
                                .remove(KEY_ACCESS_TOKEN)
                                .remove(KEY_REFRESH_TOKEN)
                                .remove(KEY_EXPIRES_AT)
                                .apply()
                            _isConnected.value = false
                        }
                        return@withContext null
                    }
                    val json = JSONObject(body)
                    saveTokens(json)
                    prefs.getString(KEY_ACCESS_TOKEN, null)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Display name of the connected Spotify user, if known. */
    fun getSpotifyDisplayName(): String? =
        if (::prefs.isInitialized) prefs.getString(KEY_DISPLAY_NAME, null) else null

    fun saveDisplayName(name: String?) {
        if (::prefs.isInitialized) prefs.edit().putString(KEY_DISPLAY_NAME, name).apply()
    }

    fun logout() {
        if (!::prefs.isInitialized) { _isConnected.value = false; return }
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_DISPLAY_NAME)
            .apply()
        _isConnected.value = false
    }

    private const val KEY_DISPLAY_NAME = "display_name"

    private fun saveTokens(json: JSONObject) {
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return
        val expiresIn = json.optLong("expires_in", 3600L)
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, access)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
            editor.putString(KEY_REFRESH_TOKEN, it)
        }
        editor.apply()
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    private fun urlEncode(value: String): String =
        Uri.encode(value).replace("+", "%20")
}

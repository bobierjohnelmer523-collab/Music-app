package com.spotifyclone.app.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    SERVER_WAKING,
    OFFLINE
}

data class ConnectionStatus(
    val state: ConnectionState,
    val message: String
)

class BackendConnectionManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(
        ConnectionStatus(ConnectionState.CONNECTING, "Connecting to Neon + Render...")
    )
    val status: StateFlow<ConnectionStatus> = _status

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var checkJob: Job? = null

    init {
        registerNetworkCallback()
        checkConnection()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                checkConnection()
            }

            override fun onLost(network: Network) {
                _status.value = ConnectionStatus(
                    ConnectionState.OFFLINE,
                    "No internet connection (Offline mode active)"
                )
            }
        })
    }

    fun checkConnection() {
        checkJob?.cancel()
        checkJob = scope.launch {
            _status.value = ConnectionStatus(
                ConnectionState.CONNECTING,
                "Connecting to Neon + Render..."
            )

            var attempts = 0
            val maxAttempts = 6
            var connected = false

            while (attempts < maxAttempts && !connected) {
                attempts++
                try {
                    val request = Request.Builder()
                        .url("https://music-app-5b9t.onrender.com/")
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful || response.code == 401 || response.code == 403 || response.code == 404) {
                        connected = true
                        _status.value = ConnectionStatus(
                            ConnectionState.CONNECTED,
                            "Connected to Neon Database & Render"
                        )
                        // After 3.5 seconds, set to IDLE so the banner smoothly fades away
                        delay(3500)
                        if (_status.value.state == ConnectionState.CONNECTED) {
                            _status.value = ConnectionStatus(ConnectionState.IDLE, "")
                        }
                        return@launch
                    } else {
                        _status.value = ConnectionStatus(
                            ConnectionState.SERVER_WAKING,
                            "Waking up Render backend (attempt $attempts)..."
                        )
                    }
                } catch (e: Exception) {
                    Log.w("BackendConnManager", "Connection ping failed (attempt $attempts): ${e.message}")
                    if (attempts == 1) {
                        _status.value = ConnectionStatus(
                            ConnectionState.SERVER_WAKING,
                            "Waking up Render backend (cold start ~30s)..."
                        )
                    }
                }
                delay(4000)
            }

            if (!connected) {
                _status.value = ConnectionStatus(
                    ConnectionState.OFFLINE,
                    "Backend unreachable - Using local offline database"
                )
            }
        }
    }
}

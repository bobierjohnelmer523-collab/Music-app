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
    UNREACHABLE
}

data class ConnectionStatus(
    val state: ConnectionState,
    val message: String
)

class BackendConnectionManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(
        ConnectionStatus(ConnectionState.CONNECTING, "Connecting")
    )
    val status: StateFlow<ConnectionStatus> = _status

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private var checkJob: Job? = null
    private var dismissJob: Job? = null

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
                setResult(ConnectionState.UNREACHABLE, "Unreachable")
            }
        })
    }

    fun dismiss() {
        dismissJob?.cancel()
        _status.value = ConnectionStatus(ConnectionState.IDLE, "")
    }

    private fun setResult(state: ConnectionState, message: String) {
        dismissJob?.cancel()
        _status.value = ConnectionStatus(state, message)
        // Automatically close and go away after 4 seconds
        dismissJob = scope.launch {
            delay(4000)
            _status.value = ConnectionStatus(ConnectionState.IDLE, "")
        }
    }

    fun checkConnection() {
        checkJob?.cancel()
        dismissJob?.cancel()
        checkJob = scope.launch {
            _status.value = ConnectionStatus(ConnectionState.CONNECTING, "Connecting")

            var attempts = 0
            val maxAttempts = 3
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
                        setResult(ConnectionState.CONNECTED, "Connected")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w("BackendConnManager", "Connection ping failed attempt $attempts: ${e.message}")
                }
                if (attempts < maxAttempts) {
                    delay(2000)
                }
            }

            if (!connected) {
                setResult(ConnectionState.UNREACHABLE, "Unreachable")
            }
        }
    }
}

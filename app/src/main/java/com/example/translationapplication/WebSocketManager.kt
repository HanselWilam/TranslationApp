package com.example.translationapplication

import android.util.Log
import org.json.JSONObject
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class WebSocketManager(var onResultReceived: (String) -> Unit) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var currentSourceLang: String = "ja"
    @Volatile private var currentTargetLang: String = "en"
    @Volatile private var isConnected: Boolean = false
    @Volatile private var isReconnecting: Boolean = false

    fun connect() {
        if (isConnected || isReconnecting) return
        isReconnecting = true

        val request = Request.Builder()
            .url("ws://192.168.0.106:8000/ws")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                isReconnecting = false
                Log.d("WebSocket", "Connected to server websocket")
                sendLanguagePair()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "language_ack") {
                        Log.d("WebSocket", "Language pair: $text")
                        return
                    }
                    if (json.optString("type") == "error") {
                        Log.e("WebSocket", "Server error: $text")
                        return
                    }
                } catch (_: Exception) {
                    // Not a control JSON payload
                }

                onResultReceived(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                isReconnecting = false
                Log.e("WebSocket", "Connection Failed: ${t.message}. Retrying in 3s...")
                reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                isReconnecting = false
                Log.d("WebSocket", "Closed: $reason. Retrying in 3s...")
                reconnect()
            }
        })
    }

    private fun reconnect() {
        Thread.sleep(3000)
        connect()
    }

    fun updateLanguagePair(sourceLang: String, targetLang: String) {
        currentSourceLang = sourceLang
        currentTargetLang = targetLang
        if (isConnected) {
            sendLanguagePair()
        }
    }

    private fun sendLanguagePair() {
        val payload = JSONObject().apply {
            put("type", "language_pair")
            put("sourceLang", currentSourceLang)
            put("targetLang", currentTargetLang)
        }
        webSocket?.send(payload.toString())
    }

    fun sendImage(imageBytes: ByteArray) {
        if (!isConnected) {
            Log.w("WebSocket", "Not connected. Frame dropped.")
            return
        }

        val success = webSocket?.send(imageBytes.toByteString()) ?: false
        if (success) {
            Log.d("WebSocket", "Successfully sent frame to server")
        } else {
            Log.e("WebSocket", "Failed to push frame to socket")
        }
    }

    fun disconnect() {
        isConnected = false
        webSocket?.close(1000, "App closed")
    }
}
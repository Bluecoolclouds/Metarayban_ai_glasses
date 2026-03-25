package com.meta.wearable.dat.externalsampleapps.cameraaccess.twitch

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class TwitchChatMessage(
    val username: String,
    val text: String,
    val color: String?,
    val timestamp: Long = System.currentTimeMillis(),
)

class TwitchChatClient {
    companion object {
        private const val TAG = "TwitchChatClient"
        private const val IRC_URL = "wss://irc-ws.chat.twitch.tv:443"
        private const val MAX_MESSAGES = 200
    }

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var channel: String? = null
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _messages = MutableStateFlow<List<TwitchChatMessage>>(emptyList())
    val messages: StateFlow<List<TwitchChatMessage>> = _messages.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun connect(channelName: String) {
        if (channelName.isBlank()) return
        disconnect()

        channel = channelName.lowercase().trim().removePrefix("#")
        Log.d(TAG, "Connecting to #$channel")

        val request = Request.Builder().url(IRC_URL).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                val nick = "justinfan${(10000..99999).random()}"
                ws.send("CAP REQ :twitch.tv/tags")
                ws.send("PASS SCHMOOPIIE")
                ws.send("NICK $nick")
                ws.send("JOIN #${channel}")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                text.split("\r\n").filter { it.isNotEmpty() }.forEach { line ->
                    handleLine(ws, line)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                _isConnected.value = false
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                _isConnected.value = false
            }
        })
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _isConnected.value = false
        _messages.value = emptyList()
        channel = null
    }

    private fun handleLine(ws: WebSocket, line: String) {
        if (line.startsWith("PING")) {
            ws.send("PONG :tmi.twitch.tv")
            return
        }

        if (line.contains("366")) {
            _isConnected.value = true
            Log.d(TAG, "Joined #$channel")
            return
        }

        if (!line.contains("PRIVMSG")) return

        val tags = mutableMapOf<String, String>()
        var rest = line
        if (rest.startsWith("@")) {
            val spaceIdx = rest.indexOf(' ')
            if (spaceIdx > 0) {
                val tagPart = rest.substring(1, spaceIdx)
                rest = rest.substring(spaceIdx + 1)
                tagPart.split(";").forEach { tag ->
                    val eqIdx = tag.indexOf('=')
                    if (eqIdx > 0) {
                        tags[tag.substring(0, eqIdx)] = tag.substring(eqIdx + 1)
                    }
                }
            }
        }

        val privmsgIdx = rest.indexOf("PRIVMSG")
        if (privmsgIdx < 0) return

        val prefix = rest.substring(0, privmsgIdx).trim()
        val username = if (prefix.startsWith(":")) {
            val excl = prefix.indexOf('!')
            if (excl > 0) prefix.substring(1, excl) else prefix.removePrefix(":")
        } else {
            tags["display-name"] ?: "unknown"
        }

        val colonIdx = rest.indexOf(':', privmsgIdx)
        if (colonIdx < 0) return
        val messageText = rest.substring(colonIdx + 1)

        val displayName = tags["display-name"]?.ifEmpty { null } ?: username
        val color = tags["color"]?.ifEmpty { null }

        val msg = TwitchChatMessage(
            username = displayName,
            text = messageText,
            color = color,
        )

        val current = _messages.value.toMutableList()
        current.add(msg)
        if (current.size > MAX_MESSAGES) {
            _messages.value = current.takeLast(MAX_MESSAGES)
        } else {
            _messages.value = current
        }
    }

    private fun scheduleReconnect() {
        val ch = channel ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            Log.d(TAG, "Reconnecting to #$ch")
            connect(ch)
        }
    }
}

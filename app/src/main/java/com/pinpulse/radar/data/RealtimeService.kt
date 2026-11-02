package com.pinpulse.radar.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.pinpulse.radar.model.AlertMessage
import com.pinpulse.radar.model.FriendRequest
import com.pinpulse.radar.model.LocationPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets

class RealtimeService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RealtimeService"
        private const val BROKER_URL = "tcp://broker.emqx.io:1883"
        private const val BACKUP_BROKER_URL = "tcp://broker.hivemq.com:1883"

        @Volatile
        private var INSTANCE: RealtimeService? = null

        fun getInstance(context: Context): RealtimeService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RealtimeService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var client: MqttClient? = null
    private val subscribedTopics = mutableSetOf<String>()

    private val _locationUpdates = MutableSharedFlow<LocationPacket>(extraBufferCapacity = 64)
    val locationUpdates: SharedFlow<LocationPacket> = _locationUpdates.asSharedFlow()

    private val _alerts = MutableSharedFlow<AlertMessage>(extraBufferCapacity = 32)
    val alerts: SharedFlow<AlertMessage> = _alerts.asSharedFlow()

    private val _friendRequests = MutableSharedFlow<FriendRequest>(extraBufferCapacity = 32)
    val friendRequests: SharedFlow<FriendRequest> = _friendRequests.asSharedFlow()

    private val _rawMessages = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val rawMessages: SharedFlow<Pair<String, String>> = _rawMessages.asSharedFlow()

    fun connect(myUserId: String) {
        scope.launch {
            try {
                if (client?.isConnected == true) return@launch

                val clientId = "pinpulse_" + myUserId + "_" + System.currentTimeMillis().toString().takeLast(5)
                client = MqttClient(BROKER_URL, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                }

                client?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d(TAG, "MQTT Connected to $serverURI (reconnect=$reconnect)")
                        // Resubscribe to user's direct channels
                        subscribe("pinpulse/v1/user/$myUserId/loc")
                        subscribe("pinpulse/v1/direct/$myUserId")
                        subscribe("pinpulse/v1/alerts/$myUserId")
                        synchronized(subscribedTopics) {
                            subscribedTopics.forEach { topic ->
                                try {
                                    client?.subscribe(topic, 1)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Resubscribe failed for $topic: ${e.message}")
                                }
                            }
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(TAG, "MQTT Connection lost: ${cause?.message}")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic == null || message == null) return
                        val payload = String(message.payload, StandardCharsets.UTF_8)
                        handleIncomingMessage(topic, payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                client?.connect(options)
            } catch (e: Exception) {
                Log.e(TAG, "Connect primary broker failed: ${e.message}, trying backup...")
                connectBackup(myUserId)
            }
        }
    }

    private fun connectBackup(myUserId: String) {
        try {
            val clientId = "pinpulse_bk_" + myUserId + "_" + System.currentTimeMillis().toString().takeLast(5)
            client = MqttClient(BACKUP_BROKER_URL, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
            }
            client?.connect(options)
            subscribe("pinpulse/v1/user/$myUserId/loc")
            subscribe("pinpulse/v1/direct/$myUserId")
            subscribe("pinpulse/v1/alerts/$myUserId")
        } catch (e: Exception) {
            Log.e(TAG, "Backup broker connect failed: ${e.message}")
        }
    }

    fun subscribe(topic: String) {
        scope.launch {
            try {
                synchronized(subscribedTopics) {
                    subscribedTopics.add(topic)
                }
                if (client?.isConnected == true) {
                    client?.subscribe(topic, 1)
                    Log.d(TAG, "Subscribed to $topic")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Subscribe failed for $topic: ${e.message}")
            }
        }
    }

    fun unsubscribe(topic: String) {
        scope.launch {
            try {
                synchronized(subscribedTopics) {
                    subscribedTopics.remove(topic)
                }
                if (client?.isConnected == true) {
                    client?.unsubscribe(topic)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unsubscribe error: ${e.message}")
            }
        }
    }

    fun publishLocation(packet: LocationPacket) {
        scope.launch {
            try {
                val topic = "pinpulse/v1/user/${packet.userId}/loc"
                val json = gson.toJson(packet)
                val message = MqttMessage(json.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = true // Retain so new openers immediately get last location
                }
                if (client?.isConnected == true) {
                    client?.publish(topic, message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "publishLocation error: ${e.message}")
            }
        }
    }

    fun broadcastAlert(targetUserId: String, alert: AlertMessage) {
        scope.launch {
            try {
                val topic = "pinpulse/v1/alerts/$targetUserId"
                val json = gson.toJson(alert)
                val message = MqttMessage(json.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = false
                }
                if (client?.isConnected == true) {
                    client?.publish(topic, message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "broadcastAlert error: ${e.message}")
            }
        }
    }

    fun publishDirectMessage(targetUserId: String, messagePayload: String) {
        scope.launch {
            try {
                val topic = "pinpulse/v1/direct/$targetUserId"
                val message = MqttMessage(messagePayload.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = false
                }
                if (client?.isConnected == true) {
                    client?.publish(topic, message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "publishDirectMessage error: ${e.message}")
            }
        }
    }

    fun publishGroupMessage(groupId: String, messagePayload: String) {
        scope.launch {
            try {
                val topic = "pinpulse/v1/group/$groupId/sync"
                val message = MqttMessage(messagePayload.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = false
                }
                if (client?.isConnected == true) {
                    client?.publish(topic, message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "publishGroupMessage error: ${e.message}")
            }
        }
    }

    private fun handleIncomingMessage(topic: String, payload: String) {
        scope.launch {
            try {
                when {
                    topic.contains("/loc") -> {
                        val packet = gson.fromJson(payload, LocationPacket::class.java)
                        _locationUpdates.emit(packet)
                    }
                    topic.contains("/alerts/") -> {
                        val alert = gson.fromJson(payload, AlertMessage::class.java)
                        _alerts.emit(alert)
                    }
                    topic.contains("/direct/") -> {
                        _rawMessages.emit(Pair(topic, payload))
                        if (payload.contains("\"status\":\"PENDING\"")) {
                            val req = gson.fromJson(payload, FriendRequest::class.java)
                            _friendRequests.emit(req)
                        }
                    }
                    else -> {
                        _rawMessages.emit(Pair(topic, payload))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message on $topic: ${e.message}")
            }
        }
    }
}

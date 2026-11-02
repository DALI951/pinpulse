package com.pinpulse.radar.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.pinpulse.radar.model.AlertMessage
import com.pinpulse.radar.model.FriendRequest
import com.pinpulse.radar.model.Group
import com.pinpulse.radar.model.LocationPacket
import com.pinpulse.radar.model.User
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
    private var currentUser: User? = null

    private val _locationUpdates = MutableSharedFlow<LocationPacket>(extraBufferCapacity = 64)
    val locationUpdates: SharedFlow<LocationPacket> = _locationUpdates.asSharedFlow()

    private val _alerts = MutableSharedFlow<AlertMessage>(extraBufferCapacity = 32)
    val alerts: SharedFlow<AlertMessage> = _alerts.asSharedFlow()

    private val _friendRequests = MutableSharedFlow<FriendRequest>(extraBufferCapacity = 32)
    val friendRequests: SharedFlow<FriendRequest> = _friendRequests.asSharedFlow()

    private val _groupUpdates = MutableSharedFlow<Group>(extraBufferCapacity = 32)
    val groupUpdates: SharedFlow<Group> = _groupUpdates.asSharedFlow()

    private val _rawMessages = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    val rawMessages: SharedFlow<Pair<String, String>> = _rawMessages.asSharedFlow()

    fun connect(user: User) {
        currentUser = user
        scope.launch {
            try {
                if (client?.isConnected == true) {
                    resubscribeUserChannels(user)
                    return@launch
                }

                val clientId = "pinpulse_" + user.id + "_" + System.currentTimeMillis().toString().takeLast(5)
                client = MqttClient(BROKER_URL, clientId, MemoryPersistence())

                val lwtPayload = """{"userId":"${user.id}","senderId":"${user.id}","name":"${user.displayName}","senderName":"${user.displayName}","status":"OFFLINE","type":"PHONE_OFFLINE","timestamp":${System.currentTimeMillis()},"message":"⚠️ ${user.displayName}'s phone lost connection / went offline."}"""
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    setWill("pinpulse/v1/user/${user.id}/status", lwtPayload.toByteArray(StandardCharsets.UTF_8), 1, true)
                }

                client?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.d(TAG, "MQTT Connected to $serverURI (reconnect=$reconnect)")
                        resubscribeUserChannels(user)
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
                connectBackup(user)
            }
        }
    }

    private fun connectBackup(user: User) {
        try {
            val clientId = "pinpulse_bk_" + user.id + "_" + System.currentTimeMillis().toString().takeLast(5)
            client = MqttClient(BACKUP_BROKER_URL, clientId, MemoryPersistence())
            val lwtPayload = """{"userId":"${user.id}","senderId":"${user.id}","name":"${user.displayName}","senderName":"${user.displayName}","status":"OFFLINE","type":"PHONE_OFFLINE","timestamp":${System.currentTimeMillis()},"message":"⚠️ ${user.displayName}'s phone lost connection / went offline."}"""
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                setWill("pinpulse/v1/user/${user.id}/status", lwtPayload.toByteArray(StandardCharsets.UTF_8), 1, true)
            }
            client?.connect(options)
            resubscribeUserChannels(user)
        } catch (e: Exception) {
            Log.e(TAG, "Backup broker connect failed: ${e.message}")
        }
    }

    private fun resubscribeUserChannels(user: User) {
        // 1. Subscribe to own location & direct messages by ID, and network live stream
        subscribe("pinpulse/v1/user/${user.id}/loc")
        subscribe("pinpulse/v1/direct/${user.id}")
        subscribe("pinpulse/v1/alerts/${user.id}")
        subscribe("pinpulse/v1/ring/${user.id}")
        subscribe("pinpulse/v1/network/live")

        // 2. CRITICAL: Subscribe to messages and invites sent to Friend Code!
        subscribe("pinpulse/v1/code/${user.friendCode}")
        subscribe("pinpulse/v1/invites/${user.friendCode}")
        subscribe("pinpulse/v1/alerts/${user.friendCode}")
        subscribe("pinpulse/v1/ring/${user.friendCode}")

        // 3. Register user profile as retained message for code lookups & publish online status
        val registryPayload = gson.toJson(user)
        publishRetained("pinpulse/v1/registry/code/${user.friendCode}", registryPayload)
        publishRetained("pinpulse/v1/registry/user/${user.id}", registryPayload)

        val onlinePayload = """{"userId":"${user.id}","senderId":"${user.id}","name":"${user.displayName}","senderName":"${user.displayName}","status":"ONLINE","type":"PHONE_ONLINE","timestamp":${System.currentTimeMillis()}}"""
        publishRetained("pinpulse/v1/user/${user.id}/status", onlinePayload)

        // 4. Resubscribe to existing dynamically added topics
        synchronized(subscribedTopics) {
            subscribedTopics.forEach { topic ->
                try {
                    client?.subscribe(topic, 1)
                } catch (e: Exception) {}
            }
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

    fun publish(topic: String, payload: String, retained: Boolean = false) {
        scope.launch {
            try {
                val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = 1
                    isRetained = retained
                }
                if (client?.isConnected == true) {
                    client?.publish(topic, message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Publish error to $topic: ${e.message}")
            }
        }
    }

    fun publishRetained(topic: String, payload: String) {
        publish(topic, payload, retained = true)
    }

    fun publishLocation(packet: LocationPacket) {
        val payload = gson.toJson(packet)
        publishRetained("pinpulse/v1/user/${packet.userId}/loc", payload)
        publish("pinpulse/v1/network/live", payload)
    }

    fun sendFriendRequest(request: FriendRequest) {
        val payload = gson.toJson(request)
        // Send to recipient's friend code channels
        publishRetained("pinpulse/v1/invites/${request.toFriendCode}", payload)
        publish("pinpulse/v1/code/${request.toFriendCode}", payload)
    }

    fun publishGroupInfo(group: Group) {
        val payload = gson.toJson(group)
        publishRetained("pinpulse/v1/groups/${group.code}/info", payload)
    }

    fun broadcastAlert(targetCodeOrId: String, alert: AlertMessage) {
        val payload = gson.toJson(alert)
        publish("pinpulse/v1/alerts/$targetCodeOrId", payload)
        publish("pinpulse/v1/code/$targetCodeOrId", payload)
    }

    fun cleanEmail(email: String): String {
        return email.trim().lowercase().replace("@", "_at_").replace(".", "_dot_")
    }

    fun publishAccountBackup(email: String, accountJson: String) {
        if (email.isBlank()) return
        val topic = "pinpulse/v1/accounts/${cleanEmail(email)}/backup"
        publishRetained(topic, accountJson)
    }

    fun sendRingCommand(targetUserId: String, targetFriendCode: String, callerName: String) {
        val payload = """{"type":"RING_PHONE","callerName":"$callerName","timestamp":${System.currentTimeMillis()}}"""
        if (targetUserId.isNotBlank()) {
            publish("pinpulse/v1/ring/$targetUserId", payload)
        }
        if (targetFriendCode.isNotBlank()) {
            publish("pinpulse/v1/ring/$targetFriendCode", payload)
        }
    }

    private fun handleIncomingMessage(topic: String, payload: String) {
        scope.launch {
            try {
                when {
                    topic.contains("/ring/") || payload.contains("\"RING_PHONE\"") -> {
                        try {
                            val map = gson.fromJson(payload, Map::class.java)
                            val caller = map["callerName"]?.toString() ?: "A friend"
                            com.pinpulse.radar.util.SirenHelper.ring(context, caller)
                        } catch (e: Exception) {
                            com.pinpulse.radar.util.SirenHelper.ring(context, "A friend")
                        }
                    }
                    topic.contains("/loc") || topic.contains("/network/live") -> {
                        val packet = gson.fromJson(payload, LocationPacket::class.java)
                        _locationUpdates.emit(packet)
                    }
                    topic.contains("/invites/") || (topic.contains("/code/") && payload.contains("\"status\":\"PENDING\"")) -> {
                        val req = gson.fromJson(payload, FriendRequest::class.java)
                        _friendRequests.emit(req)
                    }
                    topic.contains("/groups/") && topic.endsWith("/info") -> {
                        val group = gson.fromJson(payload, Group::class.java)
                        _groupUpdates.emit(group)
                    }
                    topic.contains("/alerts/") || (topic.endsWith("/status") && payload.contains("PHONE_OFFLINE")) -> {
                        try {
                            val alert = gson.fromJson(payload, AlertMessage::class.java)
                            _alerts.emit(alert)
                        } catch (e: Exception) {
                            val map = gson.fromJson(payload, Map::class.java)
                            val senderName = map["senderName"]?.toString() ?: map["name"]?.toString() ?: "Friend"
                            val alert = AlertMessage(
                                senderId = map["userId"]?.toString() ?: map["senderId"]?.toString() ?: "",
                                senderName = senderName,
                                type = "PHONE_OFFLINE",
                                message = "⚠️ $senderName's phone lost connection / went offline."
                            )
                            _alerts.emit(alert)
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

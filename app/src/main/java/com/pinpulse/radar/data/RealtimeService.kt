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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _friendRequests = MutableSharedFlow<FriendRequest>(replay = 10, extraBufferCapacity = 32)
    val friendRequests: SharedFlow<FriendRequest> = _friendRequests.asSharedFlow()

    private val registeredUsersMap = java.util.concurrent.ConcurrentHashMap<String, User>()
    private val _registeredUsers = MutableStateFlow<Map<String, User>>(emptyMap())
    val registeredUsers: StateFlow<Map<String, User>> = _registeredUsers.asStateFlow()

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
                Log.d(TAG, "MQTT Synchronously Connected to $BROKER_URL")
                resubscribeUserChannels(user)
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
        val emailTag = if (user.email.isNotBlank()) cleanEmail(user.email) else ""
        val codeUpper = user.friendCode.trim().uppercase()

        val coreTopics = mutableListOf(
            "pinpulse/v1/user/${user.id}/loc",
            "pinpulse/v1/user/${user.id}/status",
            "pinpulse/v1/direct/${user.id}",
            "pinpulse/v1/alerts/${user.id}",
            "pinpulse/v1/ring/${user.id}",
            "pinpulse/v1/invites/${user.id}",
            "pinpulse/v1/invites/broadcast",
            "pinpulse/v1/network/live"
        )

        if (codeUpper.isNotBlank()) {
            coreTopics.add("pinpulse/v1/code/$codeUpper")
            coreTopics.add("pinpulse/v1/invites/$codeUpper")
            coreTopics.add("pinpulse/v1/alerts/$codeUpper")
            coreTopics.add("pinpulse/v1/ring/$codeUpper")
            coreTopics.add("pinpulse/v1/registry/code/$codeUpper")
            coreTopics.add("pinpulse/v1/registry/code/${codeUpper.replace("-", "")}")
        }

        if (emailTag.isNotBlank()) {
            coreTopics.add("pinpulse/v1/code/$emailTag")
            coreTopics.add("pinpulse/v1/invites/$emailTag")
            coreTopics.add("pinpulse/v1/alerts/$emailTag")
            coreTopics.add("pinpulse/v1/ring/$emailTag")
            coreTopics.add("pinpulse/v1/registry/email/$emailTag")
        }

        synchronized(subscribedTopics) {
            coreTopics.addAll(subscribedTopics)
        }

        subscribeBatch(coreTopics.distinct(), qos = 1)

        // Register own profile as retained message for code lookups & publish online status
        val registryPayload = gson.toJson(user)
        if (codeUpper.isNotBlank()) {
            publishRetained("pinpulse/v1/registry/code/$codeUpper", registryPayload, qosLevel = 1)
            publishRetained("pinpulse/v1/registry/code/${codeUpper.replace("-", "")}", registryPayload, qosLevel = 1)
        }
        publishRetained("pinpulse/v1/registry/user/${user.id}", registryPayload, qosLevel = 1)
        if (emailTag.isNotBlank()) {
            publishRetained("pinpulse/v1/registry/email/$emailTag", registryPayload, qosLevel = 1)
        }

        val onlinePayload = """{"userId":"${user.id}","senderId":"${user.id}","name":"${user.displayName}","senderName":"${user.displayName}","status":"ONLINE","type":"PHONE_ONLINE","timestamp":${System.currentTimeMillis()}}"""
        publishRetained("pinpulse/v1/user/${user.id}/status", onlinePayload, qosLevel = 1)
    }

    fun subscribeBatch(topics: List<String>, qos: Int = 1) {
        if (topics.isEmpty()) return
        scope.launch {
            try {
                synchronized(subscribedTopics) {
                    subscribedTopics.addAll(topics)
                }
                if (client?.isConnected == true) {
                    val qosArray = IntArray(topics.size) { qos }
                    client?.subscribe(topics.toTypedArray(), qosArray)
                    Log.d(TAG, "Batch subscribed to ${topics.size} topics successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch subscribe error: ${e.message}")
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

    fun publish(topic: String, payload: String, retained: Boolean = false, qosLevel: Int = 0) {
        scope.launch {
            try {
                val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                    qos = qosLevel
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

    fun publishRetained(topic: String, payload: String, qosLevel: Int = 0) {
        publish(topic, payload, retained = true, qosLevel = qosLevel)
    }

    fun publishLocation(packet: LocationPacket) {
        val payload = gson.toJson(packet)
        // Publish retained location on user channel with QoS 0 for instant, zero-handshake caching
        publishRetained("pinpulse/v1/user/${packet.userId}/loc", payload, qosLevel = 0)
        // Broadcast location on live network with QoS 0 for immediate zero-delay streaming
        publish("pinpulse/v1/network/live", payload, qosLevel = 0)
    }

    fun sendFriendRequest(request: FriendRequest, targetEmail: String = "") {
        val payload = gson.toJson(request)
        // 1. Send to recipient's friend code channels
        if (request.toFriendCode.isNotBlank()) {
            val code = request.toFriendCode.trim().uppercase()
            publishRetained("pinpulse/v1/invites/$code", payload, qosLevel = 1)
            publish("pinpulse/v1/code/$code", payload, qosLevel = 1)
        }
        // 2. Send to recipient's direct user ID channel
        if (request.toUserId.isNotBlank()) {
            publishRetained("pinpulse/v1/invites/${request.toUserId}", payload, qosLevel = 1)
            publish("pinpulse/v1/direct/${request.toUserId}", payload, qosLevel = 1)
        }
        // 3. If target is an email, send to cleaned email channel
        if (targetEmail.contains("@")) {
            val emailTag = cleanEmail(targetEmail)
            publishRetained("pinpulse/v1/invites/$emailTag", payload, qosLevel = 1)
            publish("pinpulse/v1/code/$emailTag", payload, qosLevel = 1)
        }
        // 4. Universal broadcast fallback so recipient device catches invite regardless of address style
        publish("pinpulse/v1/invites/broadcast", payload, qosLevel = 1)
    }

    fun registerKnownUser(user: User) {
        if (user.id.isBlank()) return
        registeredUsersMap[user.id] = user
        if (user.friendCode.isNotBlank()) {
            val codeUpper = user.friendCode.trim().uppercase()
            registeredUsersMap[codeUpper] = user
            registeredUsersMap[codeUpper.replace("-", "")] = user
        }
        if (user.email.isNotBlank()) {
            registeredUsersMap[user.email.trim().lowercase()] = user
            registeredUsersMap[cleanEmail(user.email)] = user
        }
        _registeredUsers.value = registeredUsersMap.toMap()
    }

    fun publishGroupInfo(group: Group) {
        val payload = gson.toJson(group)
        publishRetained("pinpulse/v1/groups/${group.code}/info", payload, qosLevel = 1)
    }

    fun broadcastAlert(targetCodeOrId: String, alert: AlertMessage) {
        val payload = gson.toJson(alert)
        publish("pinpulse/v1/alerts/$targetCodeOrId", payload, qosLevel = 1)
        publish("pinpulse/v1/code/$targetCodeOrId", payload, qosLevel = 1)
    }

    fun cleanEmail(email: String): String {
        return email.trim().lowercase().replace("@", "_at_").replace(".", "_dot_")
    }

    fun publishAccountBackup(email: String, accountJson: String) {
        if (email.isBlank()) return
        val topic = "pinpulse/v1/accounts/${cleanEmail(email)}/backup"
        publishRetained(topic, accountJson, qosLevel = 1)
    }

    fun sendRingCommand(targetUserId: String, targetFriendCode: String, callerName: String) {
        val payload = """{"type":"RING_PHONE","callerName":"$callerName","timestamp":${System.currentTimeMillis()}}"""
        if (targetUserId.isNotBlank()) {
            publish("pinpulse/v1/ring/$targetUserId", payload, qosLevel = 1)
        }
        if (targetFriendCode.isNotBlank()) {
            publish("pinpulse/v1/ring/$targetFriendCode", payload, qosLevel = 1)
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
                        if (packet != null && packet.userId.isNotBlank()) {
                            val u = User(
                                id = packet.userId,
                                displayName = packet.displayName,
                                email = packet.email,
                                friendCode = packet.friendCode,
                                avatarBase64 = packet.avatarBase64,
                                latitude = packet.latitude,
                                longitude = packet.longitude,
                                speedKmh = packet.speedKmh,
                                batteryPercent = packet.batteryPercent,
                                isCharging = packet.isCharging,
                                isSharingLocation = packet.isSharingLocation,
                                lastUpdated = packet.timestamp
                            )
                            registerKnownUser(u)
                        }
                    }
                    topic.contains("/invites/") || (topic.contains("/code/") && payload.contains("\"status\":\"PENDING\"")) -> {
                        if (payload.isNotBlank() && payload != "{}" && payload.contains("\"fromUserId\"")) {
                            val req = gson.fromJson(payload, FriendRequest::class.java)
                            if (req != null && req.fromUserId.isNotBlank()) {
                                _friendRequests.emit(req)
                                registerKnownUser(
                                    User(
                                        id = req.fromUserId,
                                        displayName = req.fromUserName,
                                        email = req.fromUserEmail,
                                        avatarBase64 = req.fromUserAvatar,
                                        friendCode = req.fromFriendCode
                                    )
                                )
                                // Direct persistent storage so timing never drops the invite
                                val me = currentUser
                                val appPrefs = AppPreferences(context)
                                val currentFriends = appPrefs.loadFriends()
                                val isAlreadyFriend = currentFriends.any {
                                    it.id == req.fromUserId || (req.fromFriendCode.isNotBlank() && it.friendCode.equals(req.fromFriendCode, ignoreCase = true))
                                }
                                val isForMe = me != null && (
                                    req.toFriendCode.equals(me.friendCode, ignoreCase = true) ||
                                    req.toUserId == me.id ||
                                    (me.displayName.isNotBlank() && req.toFriendCode.equals(me.displayName, ignoreCase = true)) ||
                                    (me.email.isNotBlank() && req.toFriendCode.equals(me.email, ignoreCase = true)) ||
                                    (me.email.isNotBlank() && req.toUserId.equals(me.email, ignoreCase = true)) ||
                                    (me.email.isNotBlank() && req.toFriendCode.equals(cleanEmail(me.email), ignoreCase = true)) ||
                                    (me.email.isNotBlank() && req.toUserId.equals(cleanEmail(me.email), ignoreCase = true))
                                )
                                if (!isAlreadyFriend && isForMe) {
                                    appPrefs.addPendingRequest(req)
                                    Log.d(TAG, "Friend request saved to pending: from ${req.fromUserName} (${req.fromFriendCode})")
                                }
                            }
                        }
                    }
                    topic.startsWith("pinpulse/v1/registry/code/") -> {
                        val codeFromTopic = topic.removePrefix("pinpulse/v1/registry/code/").trim().uppercase()
                        if (payload.isNotBlank() && payload != "{}") {
                            try {
                                val regUser = gson.fromJson(payload, User::class.java)
                                if (regUser != null) {
                                    val finalUser = if (regUser.friendCode.isBlank()) regUser.copy(friendCode = codeFromTopic) else regUser
                                    registerKnownUser(finalUser)
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    topic.startsWith("pinpulse/v1/registry/") -> {
                        if (payload.isNotBlank() && payload != "{}") {
                            try {
                                val regUser = gson.fromJson(payload, User::class.java)
                                if (regUser != null && regUser.id.isNotBlank()) {
                                    registerKnownUser(regUser)
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    topic.contains("/groups/") && topic.endsWith("/info") -> {
                        val group = gson.fromJson(payload, Group::class.java)
                        _groupUpdates.emit(group)
                    }
                    topic.contains("/alerts/") -> {
                        try {
                            val alert = gson.fromJson(payload, AlertMessage::class.java)
                            _alerts.emit(alert)
                        } catch (e: Exception) {
                            val map = gson.fromJson(payload, Map::class.java)
                            val senderName = map["senderName"]?.toString() ?: map["name"]?.toString() ?: "Friend"
                            val alert = AlertMessage(
                                senderId = map["userId"]?.toString() ?: map["senderId"]?.toString() ?: "",
                                senderName = senderName,
                                type = map["type"]?.toString() ?: "ALERT",
                                message = map["message"]?.toString() ?: "⚠️ Alert received."
                            )
                            _alerts.emit(alert)
                        }
                    }
                    topic.endsWith("/status") -> {
                        // Pass status packets to rawMessages for friend state updates without triggering noisy alert popups
                        _rawMessages.emit(Pair(topic, payload))
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

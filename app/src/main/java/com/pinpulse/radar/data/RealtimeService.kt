package com.pinpulse.radar.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.gson.Gson
import com.pinpulse.radar.model.AlertMessage
import com.pinpulse.radar.model.FriendRequest
import com.pinpulse.radar.model.Group
import com.pinpulse.radar.model.LocationPacket
import com.pinpulse.radar.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        // All fallback endpoints point to the exact same EMQX cluster over alternate ports/transports
        // (1883 raw TCP -> 8883 TLS -> 8083 WS -> 8084 WSS) so port-blocked networks never split-brain!
        private val BROKER_URLS = listOf(
            "tcp://broker.emqx.io:1883",
            "ssl://broker.emqx.io:8883",
            "ws://broker.emqx.io:8083/mqtt",
            "wss://broker.emqx.io:8084/mqtt"
        )
        private const val PENDING_RING_TTL_MS = 45_000L

        @Volatile
        private var INSTANCE: RealtimeService? = null

        fun getInstance(context: Context): RealtimeService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RealtimeService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    @Volatile
    private var client: MqttClient? = null
    // Desired extra dynamic subscriptions requested by callers (friends, groups, backup, etc.)
    private val desiredTopics = LinkedHashSet<String>()
    // Topics confirmed subscribed on the currently active MqttClient session
    private val subscribedTopics = mutableSetOf<String>()

    @Volatile
    private var currentUser: User? = null

    @Volatile
    private var connectedUserId: String = ""

    @Volatile
    private var connectedFriendCode: String = ""

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

    private val ringDedupLock = Any()
    private val handledRingIds = LinkedHashSet<String>()
    private val handledSosAlertIds = LinkedHashSet<String>()

    @Volatile
    private var lastIncomingRingTime: Long = 0L

    @Volatile
    private var lastStopRingTime: Long = 0L

    @Volatile
    private var lastStopAlertEmittedTime: Long = 0L

    init {
        registerNetworkMonitor()
    }

    private fun pruneBoundedSet(set: LinkedHashSet<String>, maxEntries: Int = 200) {
        while (set.size > maxEntries) {
            val first = set.firstOrNull() ?: break
            set.remove(first)
        }
    }

    private fun registerNetworkMonitor() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val user = currentUser ?: AppPreferences(context).loadCurrentUser()
                    if (user != null) {
                        Log.d(TAG, "Network available — ensuring MQTT connection is active")
                        ensureConnected(user)
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun isConnected(): Boolean = client?.isConnected == true

    fun connect(user: User) {
        currentUser = user
        scope.launch {
            connectMutex.withLock {
                connectInternalLocked(user, forceResubscribe = true)
            }
        }
    }

    fun disconnect() {
        currentUser = null
        connectedUserId = ""
        connectedFriendCode = ""
        synchronized(desiredTopics) {
            desiredTopics.clear()
        }
        synchronized(subscribedTopics) {
            subscribedTopics.clear()
        }
        scope.launch {
            connectMutex.withLock {
                closeStaleClientLocked()
            }
        }
    }

    fun ensureConnected(user: User? = currentUser) {
        val targetUser = user ?: currentUser ?: AppPreferences(context).loadCurrentUser() ?: return
        currentUser = targetUser
        val activeClient = client
        val isActiveConnected = activeClient?.isConnected == true
        val identityChanged = isActiveConnected && (
            connectedUserId != targetUser.id ||
            !connectedFriendCode.equals(targetUser.friendCode.trim(), ignoreCase = true)
        )
        val hasCoreRingSub = isActiveConnected && synchronized(subscribedTopics) {
            targetUser.id.isBlank() || subscribedTopics.contains("pinpulse/v1/ring/${targetUser.id}")
        }
        if (isActiveConnected && !identityChanged && hasCoreRingSub) return
        if (connectMutex.isLocked && !identityChanged) return
        scope.launch {
            connectMutex.withLock {
                connectInternalLocked(targetUser, forceResubscribe = identityChanged || !hasCoreRingSub)
            }
        }
    }

    private fun connectInternalLocked(user: User, forceResubscribe: Boolean): Boolean {
        currentUser = user
        val existing = client
        if (existing?.isConnected == true) {
            val identityChanged = connectedUserId != user.id ||
                !connectedFriendCode.equals(user.friendCode.trim(), ignoreCase = true)
            val missingCore = synchronized(subscribedTopics) {
                user.id.isNotBlank() && !subscribedTopics.contains("pinpulse/v1/ring/${user.id}")
            }
            if (forceResubscribe || identityChanged || missingCore) {
                connectedUserId = user.id
                connectedFriendCode = user.friendCode.trim()
                resubscribeUserChannelsLocked(existing, user)
            }
            return true
        }

        closeStaleClientLocked()

        for (brokerUrl in BROKER_URLS) {
            try {
                val suffix = java.util.UUID.randomUUID().toString().replace("-", "").take(6)
                val clientId = "pinpulse_${user.id}_$suffix"
                val newClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
                newClient.setCallback(createMqttCallback(user))

                val options = buildConnectOptions(user, brokerUrl)
                newClient.connect(options)
                client = newClient
                connectedUserId = user.id
                connectedFriendCode = user.friendCode.trim()
                Log.d(TAG, "MQTT Connected to EMQX endpoint: $brokerUrl")
                resubscribeUserChannelsLocked(newClient, user)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "EMQX endpoint $brokerUrl connect failed (${e.message}), trying next transport...")
                closeStaleClientLocked()
            }
        }
        Log.e(TAG, "All EMQX transport endpoints failed to connect")
        return false
    }

    private fun closeStaleClientLocked() {
        val old = client
        client = null
        synchronized(subscribedTopics) {
            subscribedTopics.clear()
        }
        if (old != null) {
            try {
                old.setCallback(null)
                if (old.isConnected) {
                    old.disconnectForcibly(100, 100)
                }
                old.close(true)
            } catch (_: Exception) {}
        }
    }

    private fun buildConnectOptions(user: User, brokerUrl: String): MqttConnectOptions {
        val lwtPayload = org.json.JSONObject().apply {
            put("userId", user.id)
            put("senderId", user.id)
            put("name", user.displayName)
            put("senderName", user.displayName)
            put("status", "OFFLINE")
            put("type", "PHONE_OFFLINE")
            put("timestamp", System.currentTimeMillis())
            put("message", "⚠️ ${user.displayName}'s phone lost connection / went offline.")
        }.toString()

        return MqttConnectOptions().apply {
            // Disabled so Paho's internal timer never races closeStaleClientLocked() or wipes subscriptions
            isAutomaticReconnect = false
            isCleanSession = true
            connectionTimeout = 8
            keepAliveInterval = 25
            maxInflight = 100
            if (brokerUrl.startsWith("ssl://") || brokerUrl.startsWith("wss://")) {
                try {
                    socketFactory = javax.net.ssl.SSLSocketFactory.getDefault()
                } catch (_: Exception) {}
            }
            // LWT is non-retained so brief cellular handoffs never leave a permanent OFFLINE flag
            setWill("pinpulse/v1/user/${user.id}/status", lwtPayload.toByteArray(StandardCharsets.UTF_8), 1, false)
        }
    }

    private fun createMqttCallback(user: User): MqttCallbackExtended {
        return object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.d(TAG, "MQTT connectComplete to $serverURI (reconnect=$reconnect)")
            }

            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "MQTT Connection lost: ${cause?.message} — clearing subscriptions and scheduling reconnect")
                synchronized(subscribedTopics) {
                    subscribedTopics.clear()
                }
                scope.launch {
                    kotlinx.coroutines.delay(2000L)
                    val activeUser = currentUser ?: AppPreferences(context).loadCurrentUser() ?: user
                    if (currentUser != null || AppPreferences(context).loadCurrentUser() != null) {
                        ensureConnected(activeUser)
                    }
                }
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (topic == null || message == null) return
                val payload = String(message.payload, StandardCharsets.UTF_8)
                handleIncomingMessage(topic, payload)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        }
    }

    private fun buildCoreUserTopics(user: User): List<String> {
        val emailTag = if (user.email.isNotBlank()) cleanEmail(user.email) else ""
        val codeUpper = user.friendCode.trim().uppercase()
        val rawEmailLower = user.email.trim().lowercase()

        val coreTopics = mutableListOf(
            "pinpulse/v1/user/${user.id}/loc",
            "pinpulse/v1/user/${user.id}/status",
            "pinpulse/v1/direct/${user.id}",
            "pinpulse/v1/alerts/${user.id}",
            "pinpulse/v1/alerts/broadcast",
            "pinpulse/v1/ring/${user.id}",
            "pinpulse/v1/ring_pending/${user.id}",
            "pinpulse/v1/ring/broadcast",
            "pinpulse/v1/invites/${user.id}",
            "pinpulse/v1/invites/broadcast",
            "pinpulse/v1/network/live",
            "pinpulse/v1/watch/${user.id}",
            "pinpulse/v1/watch/all",
            "pinpulse/v1/admin/broadcast/force_update",
            "pinpulse/v1/admin/broadcast/user_update",
            "pinpulse/v1/admin/broadcast/silence",
            "pinpulse/v1/admin/users/${user.id}",
            "pinpulse/v1/admin/broadcast"
        )

        if (codeUpper.isNotBlank()) {
            coreTopics.add("pinpulse/v1/code/$codeUpper")
            coreTopics.add("pinpulse/v1/code/${codeUpper.replace("-", "")}")
            coreTopics.add("pinpulse/v1/code/${codeUpper.lowercase()}")
            coreTopics.add("pinpulse/v1/invites/$codeUpper")
            coreTopics.add("pinpulse/v1/alerts/$codeUpper")
            coreTopics.add("pinpulse/v1/ring/$codeUpper")
            coreTopics.add("pinpulse/v1/ring/${codeUpper.replace("-", "")}")
            coreTopics.add("pinpulse/v1/ring/${codeUpper.lowercase()}")
            coreTopics.add("pinpulse/v1/ring_pending/$codeUpper")
            coreTopics.add("pinpulse/v1/registry/code/$codeUpper")
            coreTopics.add("pinpulse/v1/registry/code/${codeUpper.replace("-", "")}")
            coreTopics.add("pinpulse/v1/admin/users/$codeUpper")
        }

        if (emailTag.isNotBlank()) {
            coreTopics.add("pinpulse/v1/code/$emailTag")
            coreTopics.add("pinpulse/v1/invites/$emailTag")
            coreTopics.add("pinpulse/v1/alerts/$emailTag")
            coreTopics.add("pinpulse/v1/ring/$emailTag")
            coreTopics.add("pinpulse/v1/ring_pending/$emailTag")
            coreTopics.add("pinpulse/v1/registry/email/$emailTag")
            coreTopics.add("pinpulse/v1/admin/users/$emailTag")
            if (rawEmailLower.isNotBlank() && rawEmailLower != emailTag) {
                coreTopics.add("pinpulse/v1/ring/$rawEmailLower")
                coreTopics.add("pinpulse/v1/alerts/$rawEmailLower")
            }
        }
        return coreTopics
    }

    private fun subscribeTopicsLocked(activeClient: MqttClient, topics: List<String>, qos: Int = 1): Boolean {
        val clean = topics.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return true
        return try {
            if (!activeClient.isConnected) {
                synchronized(subscribedTopics) { subscribedTopics.clear() }
                false
            } else {
                val qosArray = IntArray(clean.size) { qos }
                activeClient.subscribe(clean.toTypedArray(), qosArray)
                synchronized(subscribedTopics) {
                    subscribedTopics.addAll(clean)
                }
                Log.d(TAG, "Subscribed to ${clean.size} topics successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Subscribe error (${clean.size} topics): ${e.message}")
            synchronized(subscribedTopics) {
                subscribedTopics.removeAll(clean.toSet())
            }
            false
        }
    }

    private fun resubscribeUserChannelsLocked(activeClient: MqttClient, user: User) {
        val allTopics = mutableListOf<String>()
        allTopics.addAll(buildCoreUserTopics(user))
        synchronized(desiredTopics) {
            allTopics.addAll(desiredTopics)
        }
        val ok = subscribeTopicsLocked(activeClient, allTopics.distinct(), qos = 1)
        if (!ok) {
            synchronized(subscribedTopics) {
                subscribedTopics.clear()
            }
        }
        publishUserRegistry(user)
    }

    fun publishUserRegistry(user: User) {
        currentUser = user
        registerKnownUser(user)
        val emailTag = if (user.email.isNotBlank()) cleanEmail(user.email) else ""
        val codeUpper = user.friendCode.trim().uppercase()
        val registryPayload = gson.toJson(user)

        if (codeUpper.isNotBlank()) {
            publishRetained("pinpulse/v1/registry/code/$codeUpper", registryPayload, qosLevel = 1)
            publishRetained("pinpulse/v1/registry/code/${codeUpper.replace("-", "")}", registryPayload, qosLevel = 1)
        }
        publishRetained("pinpulse/v1/registry/user/${user.id}", registryPayload, qosLevel = 1)
        publishFriendsList(user, AppPreferences(context).loadFriends())
        if (emailTag.isNotBlank()) {
            publishRetained("pinpulse/v1/registry/email/$emailTag", registryPayload, qosLevel = 1)
        }

        val onlinePayload = org.json.JSONObject().apply {
            put("userId", user.id)
            put("senderId", user.id)
            put("name", user.displayName)
            put("senderName", user.displayName)
            put("status", "ONLINE")
            put("type", "PHONE_ONLINE")
            put("timestamp", System.currentTimeMillis())
        }.toString()
        publishRetained("pinpulse/v1/user/${user.id}/status", onlinePayload, qosLevel = 1)
    }

    fun subscribeBatch(topics: List<String>, qos: Int = 1) {
        val clean = topics.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return
        synchronized(desiredTopics) {
            desiredTopics.addAll(clean)
        }
        scope.launch {
            connectMutex.withLock {
                val c = client
                if (c?.isConnected == true) {
                    subscribeTopicsLocked(c, clean, qos)
                } else {
                    val u = currentUser ?: AppPreferences(context).loadCurrentUser()
                    if (u != null) {
                        connectInternalLocked(u, forceResubscribe = false)
                    }
                }
            }
        }
    }

    fun subscribe(topic: String) {
        val clean = topic.trim()
        if (clean.isBlank()) return
        synchronized(desiredTopics) {
            desiredTopics.add(clean)
        }
        scope.launch {
            connectMutex.withLock {
                val c = client
                if (c?.isConnected == true) {
                    val already = synchronized(subscribedTopics) { subscribedTopics.contains(clean) }
                    if (!already) {
                        subscribeTopicsLocked(c, listOf(clean), qos = 1)
                    }
                } else {
                    val u = currentUser ?: AppPreferences(context).loadCurrentUser()
                    if (u != null) {
                        connectInternalLocked(u, forceResubscribe = false)
                    }
                }
            }
        }
    }

    fun unsubscribe(topic: String) {
        val clean = topic.trim()
        if (clean.isBlank()) return
        synchronized(desiredTopics) {
            desiredTopics.remove(clean)
        }
        synchronized(subscribedTopics) {
            subscribedTopics.remove(clean)
        }
        scope.launch {
            connectMutex.withLock {
                try {
                    if (client?.isConnected == true) {
                        client?.unsubscribe(clean)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unsubscribe error: ${e.message}")
                }
            }
        }
    }

    private suspend fun ensureConnectedForPublish(): MqttClient? {
        client?.takeIf { it.isConnected }?.let { return it }
        return connectMutex.withLock {
            client?.takeIf { it.isConnected } ?: run {
                val u = currentUser ?: AppPreferences(context).loadCurrentUser()
                if (u != null && connectInternalLocked(u, forceResubscribe = false)) {
                    client?.takeIf { it.isConnected }
                } else {
                    null
                }
            }
        }
    }

    private fun publishDirectOnClient(
        activeClient: MqttClient,
        topic: String,
        payload: String,
        retained: Boolean,
        qosLevel: Int
    ): Boolean {
        return try {
            if (!activeClient.isConnected) return false
            val message = MqttMessage(payload.toByteArray(StandardCharsets.UTF_8)).apply {
                qos = qosLevel
                isRetained = retained
            }
            activeClient.publish(topic, message)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Publish error to $topic: ${e.message}")
            false
        }
    }

    fun publish(topic: String, payload: String, retained: Boolean = false, qosLevel: Int = 0) {
        scope.launch {
            val activeClient = ensureConnectedForPublish() ?: return@launch
            publishDirectOnClient(activeClient, topic, payload, retained, qosLevel)
        }
    }

    fun publishRetained(topic: String, payload: String, qosLevel: Int = 0) {
        publish(topic, payload, retained = true, qosLevel = qosLevel)
    }

    @Volatile
    private var lastLocationPublishedAtRealtime: Long = 0L

    fun publishLocation(packet: LocationPacket, force: Boolean = false) {
        val statusUpper = packet.accountStatus.uppercase()
        if (!force && (statusUpper == "BANNED" || statusUpper == "DEACTIVATED" || statusUpper == "DELETED")) {
            return
        }
        val rateSec = packet.rateLimitSec.coerceIn(1, 300)
        val minIntervalMs = (rateSec * 1000L) - 150L
        val nowRealtime = android.os.SystemClock.elapsedRealtime()
        if (!force && lastLocationPublishedAtRealtime > 0L && (nowRealtime - lastLocationPublishedAtRealtime) < minIntervalMs) {
            return
        }
        lastLocationPublishedAtRealtime = nowRealtime

        val payload = gson.toJson(packet)
        // Publish retained location on user channel with QoS 0 for instant caching
        publishRetained("pinpulse/v1/user/${packet.userId}/loc", payload, qosLevel = 0)
        // Broadcast location on live network with QoS 0 for immediate zero-delay streaming
        publish("pinpulse/v1/network/live", payload, qosLevel = 0)
    }

    fun publishWatchPing(targetUserId: String) {
        if (targetUserId.isBlank()) return
        val me = currentUser ?: AppPreferences(context).loadCurrentUser()
        val payload = """{"watcherId":"${me?.id ?: ""}","timestamp":${System.currentTimeMillis()}}"""
        publish("pinpulse/v1/watch/$targetUserId", payload, qosLevel = 0)
    }

    fun publishWatchAllPing() {
        val me = currentUser ?: AppPreferences(context).loadCurrentUser()
        val payload = """{"watcherId":"${me?.id ?: "admin"}","timestamp":${System.currentTimeMillis()}}"""
        publish("pinpulse/v1/watch/all", payload, qosLevel = 0)
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
        val existing = registeredUsersMap[user.id]
        val merged = if (existing != null) {
            user.copy(
                friendCode = user.friendCode.ifBlank { existing.friendCode },
                email = user.email.ifBlank { existing.email },
                avatarBase64 = user.avatarBase64 ?: existing.avatarBase64,
                statusMessage = user.statusMessage ?: existing.statusMessage
            )
        } else {
            user
        }
        registeredUsersMap[merged.id] = merged
        if (merged.friendCode.isNotBlank()) {
            val codeUpper = merged.friendCode.trim().uppercase()
            registeredUsersMap[codeUpper] = merged
            registeredUsersMap[codeUpper.replace("-", "")] = merged
        }
        if (merged.email.isNotBlank()) {
            registeredUsersMap[merged.email.trim().lowercase()] = merged
            registeredUsersMap[cleanEmail(merged.email)] = merged
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

    private fun buildTargetRingTopics(
        targetUserId: String,
        targetFriendCode: String,
        targetEmail: String
    ): Pair<List<String>, List<String>> {
        val cleanUserId = targetUserId.trim()
        val cleanCode = targetFriendCode.trim().uppercase()
        val cleanEmailTag = if (targetEmail.contains("@")) cleanEmail(targetEmail) else ""
        val rawEmailLower = targetEmail.trim().lowercase()

        val liveTopics = mutableListOf<String>()
        val pendingTopics = mutableListOf<String>()

        if (cleanUserId.isNotBlank()) {
            liveTopics.add("pinpulse/v1/ring/$cleanUserId")
            liveTopics.add("pinpulse/v1/alerts/$cleanUserId")
            pendingTopics.add("pinpulse/v1/ring_pending/$cleanUserId")
        }
        if (cleanCode.isNotBlank() && cleanCode != "----") {
            liveTopics.add("pinpulse/v1/ring/$cleanCode")
            liveTopics.add("pinpulse/v1/ring/${cleanCode.replace("-", "")}")
            liveTopics.add("pinpulse/v1/ring/${cleanCode.lowercase()}")
            liveTopics.add("pinpulse/v1/alerts/$cleanCode")
            pendingTopics.add("pinpulse/v1/ring_pending/$cleanCode")
        }
        if (cleanEmailTag.isNotBlank()) {
            liveTopics.add("pinpulse/v1/ring/$cleanEmailTag")
            liveTopics.add("pinpulse/v1/alerts/$cleanEmailTag")
            pendingTopics.add("pinpulse/v1/ring_pending/$cleanEmailTag")
            if (rawEmailLower.isNotBlank() && rawEmailLower != cleanEmailTag) {
                liveTopics.add("pinpulse/v1/ring/$rawEmailLower")
                liveTopics.add("pinpulse/v1/alerts/$rawEmailLower")
            }
        }
        return Pair(liveTopics.distinct(), pendingTopics.distinct())
    }

    private fun clearOwnPendingRingTopics() {
        val me = currentUser ?: AppPreferences(context).loadCurrentUser() ?: return
        val topicsToClear = mutableListOf<String>()
        if (me.id.isNotBlank()) {
            topicsToClear.add("pinpulse/v1/ring_pending/${me.id.trim()}")
        }
        val codeUpper = me.friendCode.trim().uppercase()
        if (codeUpper.isNotBlank()) {
            topicsToClear.add("pinpulse/v1/ring_pending/$codeUpper")
        }
        if (me.email.contains("@")) {
            topicsToClear.add("pinpulse/v1/ring_pending/${cleanEmail(me.email)}")
        }
        topicsToClear.distinct().forEach { t ->
            publishRetained(t, "", qosLevel = 1)
        }
    }

    fun sendRingCommand(
        targetUserId: String,
        targetFriendCode: String,
        callerName: String,
        targetEmail: String = "",
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val savedMe = currentUser ?: AppPreferences(context).loadCurrentUser()
        val callerId = savedMe?.id ?: ""
        val callerCode = savedMe?.friendCode ?: ""
        val callerEmail = savedMe?.email ?: ""
        val now = System.currentTimeMillis()
        val ringId = "ring_${now}_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)

        val payload = org.json.JSONObject().apply {
            put("type", "RING_PHONE")
            put("ringId", ringId)
            put("callerName", callerName)
            put("callerId", callerId)
            put("callerCode", callerCode)
            put("callerEmail", callerEmail)
            put("timestamp", now)
        }.toString()

        val (liveTopics, pendingTopics) = buildTargetRingTopics(targetUserId, targetFriendCode, targetEmail)
        if (liveTopics.isEmpty()) {
            onResult?.invoke(false)
            return
        }

        scope.launch {
            val activeClient = ensureConnectedForPublish()
            if (activeClient == null) {
                Log.w(TAG, "sendRingCommand failed: MQTT broker unreachable")
                onResult?.invoke(false)
                return@launch
            }
            var publishedAny = false
            for (t in liveTopics) {
                if (publishDirectOnClient(activeClient, t, payload, retained = false, qosLevel = 1)) {
                    publishedAny = true
                }
            }
            for (pt in pendingTopics) {
                if (publishDirectOnClient(activeClient, pt, payload, retained = true, qosLevel = 1)) {
                    publishedAny = true
                }
            }
            onResult?.invoke(publishedAny)
        }
    }

    fun sendStopRingCommand(
        targetUserId: String,
        targetFriendCode: String,
        callerName: String,
        targetEmail: String = "",
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val savedMe = currentUser ?: AppPreferences(context).loadCurrentUser()
        val callerId = savedMe?.id ?: ""
        val callerCode = savedMe?.friendCode ?: ""
        val callerEmail = savedMe?.email ?: ""
        val now = System.currentTimeMillis()
        val stopId = "stop_${now}_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)

        val payload = org.json.JSONObject().apply {
            put("type", "STOP_RING")
            put("ringId", stopId)
            put("callerName", callerName)
            put("callerId", callerId)
            put("callerCode", callerCode)
            put("callerEmail", callerEmail)
            put("timestamp", now)
        }.toString()

        val (liveTopics, pendingTopics) = buildTargetRingTopics(targetUserId, targetFriendCode, targetEmail)
        if (liveTopics.isEmpty() && pendingTopics.isEmpty()) {
            onResult?.invoke(false)
            return
        }

        scope.launch {
            val activeClient = ensureConnectedForPublish()
            if (activeClient == null) {
                Log.w(TAG, "sendStopRingCommand failed: MQTT broker unreachable")
                onResult?.invoke(false)
                return@launch
            }
            var publishedAny = false
            // Wipe retained ring_pending first so reconnecting target never replays a stopped ring
            for (pt in pendingTopics) {
                if (publishDirectOnClient(activeClient, pt, "", retained = true, qosLevel = 1)) {
                    publishedAny = true
                }
            }
            for (t in liveTopics) {
                if (publishDirectOnClient(activeClient, t, payload, retained = false, qosLevel = 1)) {
                    publishedAny = true
                }
            }
            onResult?.invoke(publishedAny)
        }
    }

    private suspend fun handleIncomingRingOrStop(topic: String, payload: String) {
        val trimmed = payload.trim()
        // Empty payload is an MQTT retained-clear event — never treat as a ring or re-clear in a loop!
        if (trimmed.isEmpty() || trimmed == "{}") return

        val isPendingTopic = topic.contains("/ring_pending/")
        val json = try {
            org.json.JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }

        val type = json?.optString("type", "")?.trim().orEmpty()
        val ringId = json?.optString("ringId", "")?.trim().orEmpty()
        val caller = json?.optString("callerName", "")?.trim()?.ifBlank { "A friend" } ?: "A friend"
        val callerId = json?.optString("callerId", "")?.trim().orEmpty()
        val callerCode = json?.optString("callerCode", "")?.trim().orEmpty()
        val callerEmail = json?.optString("callerEmail", "")?.trim().orEmpty()
        val packetTs = json?.optLong("timestamp", 0L) ?: 0L

        val isStop = type == "STOP_RING" ||
            type == "STOP_SIREN" ||
            trimmed.contains("\"STOP_RING\"") ||
            trimmed.contains("\"STOP_SIREN\"")

        // Always clear consumed retained ring_pending topic immediately
        if (isPendingTopic) {
            publishRetained(topic, "", qosLevel = 1)
        }

        // 1. Process STOP_RING / STOP_SIREN BEFORE VIP caller filter so remote silence always succeeds!
        if (isStop) {
            val now = System.currentTimeMillis()
            val shouldEmitStopAlert: Boolean
            synchronized(ringDedupLock) {
                lastStopRingTime = now
                if (ringId.isNotBlank() && !handledRingIds.add(ringId)) {
                    shouldEmitStopAlert = false
                } else if (now - lastStopAlertEmittedTime < 2500L) {
                    shouldEmitStopAlert = false
                } else {
                    lastStopAlertEmittedTime = now
                    shouldEmitStopAlert = true
                }
                pruneBoundedSet(handledRingIds)
            }
            Log.d(TAG, "Remote silence command received from $caller on $topic")
            com.pinpulse.radar.util.SirenHelper.stop(context)
            clearOwnPendingRingTopics()
            if (shouldEmitStopAlert) {
                _alerts.emit(
                    AlertMessage(
                        senderId = "system",
                        senderName = caller,
                        type = "SIREN_STOPPED_REMOTELY",
                        message = "🛑 Alarm silenced remotely by $caller."
                    )
                )
            }
            return
        }

        // 2. Enforce VIP Ring Allow-List / Block-List (even if JSON was malformed), honoring Admin Console override
        val isAdminOverride = json?.optBoolean("adminOverride", false) == true || json?.optBoolean("isAdmin", false) == true
        val appPrefs = AppPreferences(context)
        val me = currentUser ?: appPrefs.loadCurrentUser()
        if (!isAdminOverride && me?.email?.trim()?.lowercase() == "minod9349@gmail.com") {
            val effectiveCallerName = if (json != null) caller else ""
            if (!appPrefs.isUserAllowedToRing(callerId, callerCode, callerEmail, effectiveCallerName)) {
                Log.d(TAG, "Silently dropped ring command from blocked/unallowed caller: $caller ($callerId)")
                return
            }
        }

        // 3. Enforce strict 45s TTL on retained ring_pending catch-up packets
        val now = System.currentTimeMillis()
        if (isPendingTopic) {
            if (packetTs <= 0L || kotlin.math.abs(now - packetTs) > PENDING_RING_TTL_MS) {
                Log.d(TAG, "Ignored expired ring_pending packet on $topic (ts=$packetTs, now=$now)")
                return
            }
        }

        // 4. Synchronized ringId + timestamp deduplication (never re-triggering after local/remote Stop!)
        val shouldTriggerRing: Boolean = synchronized(ringDedupLock) {
            val withinPostStopGuard = (now - lastStopRingTime) < 2500L &&
                (packetTs <= 0L || packetTs <= lastStopRingTime)
            if (ringId.isNotBlank()) {
                if (handledRingIds.contains(ringId) || withinPostStopGuard) {
                    false
                } else {
                    handledRingIds.add(ringId)
                    pruneBoundedSet(handledRingIds)
                    lastIncomingRingTime = now
                    true
                }
            } else {
                if (withinPostStopGuard || (now - lastIncomingRingTime) < 3000L) {
                    false
                } else {
                    lastIncomingRingTime = now
                    true
                }
            }
        }

        if (shouldTriggerRing) {
            com.pinpulse.radar.util.SirenHelper.ring(context, caller)
        }
    }

    private fun handleIncomingMessage(topic: String, payload: String) {
        scope.launch {
            try {
                when {
                    topic.contains("/ring/") ||
                        topic.contains("/ring_pending/") ||
                        payload.contains("\"type\":\"RING_PHONE\"") ||
                        payload.contains("\"RING_PHONE\"") ||
                        payload.contains("\"STOP_RING\"") ||
                        payload.contains("\"STOP_SIREN\"") -> {
                        handleIncomingRingOrStop(topic, payload)
                    }
                    topic.contains("/loc") || topic.contains("/network/live") || (topic.contains("/direct/") && payload.contains("latitude")) -> {
                        val packet = gson.fromJson(payload, LocationPacket::class.java)?.sanitize()
                        if (packet != null && packet.userId.isNotBlank()) {
                            _locationUpdates.emit(packet)
                            val u = User(
                                id = packet.userId,
                                displayName = packet.displayName,
                                email = packet.email,
                                friendCode = packet.friendCode,
                                avatarBase64 = packet.avatarBase64,
                                statusMessage = packet.statusMessage,
                                latitude = packet.latitude,
                                longitude = packet.longitude,
                                speedKmh = packet.speedKmh,
                                batteryPercent = packet.batteryPercent,
                                isCharging = packet.isCharging,
                                isSharingLocation = packet.isSharingLocation,
                                isGpsEnabled = packet.isGpsEnabled,
                                lastUpdated = packet.timestamp,
                                subscriptionPlan = packet.subscriptionPlan,
                                accountStatus = packet.accountStatus,
                                subscriptionExpiresAt = packet.subscriptionExpiresAt,
                                maxFriends = packet.maxFriends,
                                maxGroups = packet.maxGroups,
                                rateLimitSec = packet.rateLimitSec,
                                monthlyRateUsd = packet.monthlyRateUsd,
                                friendsCount = packet.friendsCount,
                                groupsCount = packet.groupsCount
                            ).sanitize()
                            registerKnownUser(u)
                        }
                    }
                    topic.startsWith("pinpulse/v1/watch/") -> {
                        com.pinpulse.radar.service.LocationTrackingService.onViewerWatching()
                    }
                    topic == "pinpulse/v1/admin/broadcast/force_update" -> {
                        try {
                            val json = org.json.JSONObject(payload)
                            val minVer = json.optString("minVersion", "").trim()
                            val mandatory = json.optBoolean("mandatory", false)
                            val appPrefs = AppPreferences(context)
                            appPrefs.setMinRequiredVersion(minVer)
                            val isOutdated = minVer.isNotBlank() && com.pinpulse.radar.util.UpdateManager.compareVersions(minVer, com.pinpulse.radar.BuildConfig.VERSION_NAME)
                            appPrefs.isMandatoryUpdateEnforced = mandatory && isOutdated
                            if (appPrefs.isMandatoryUpdateEnforced) {
                                com.pinpulse.radar.util.UpdateManager.setMandatoryEnforced(minVer, true)
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    com.pinpulse.radar.util.UpdateManager.checkForUpdates(context)
                                }
                            } else if (!mandatory) {
                                com.pinpulse.radar.util.UpdateManager.setMandatoryEnforced("", false)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Force update broadcast parse error: ${e.message}")
                        }
                    }
                    topic.contains("/admin/") -> {
                        applyAdminCommandIfTarget(topic, payload)
                        _rawMessages.emit(Pair(topic, payload))
                    }
                    topic.contains("/invites/") || (topic.contains("/code/") && payload.contains("\"status\":\"PENDING\"")) -> {
                        if (payload.isNotBlank() && payload != "{}" && payload.contains("\"fromUserId\"")) {
                            val req = gson.fromJson(payload, FriendRequest::class.java)?.sanitize()
                            if (req != null && req.fromUserId.isNotBlank()) {
                                registerKnownUser(
                                    User(
                                        id = req.fromUserId,
                                        displayName = req.fromUserName,
                                        email = req.fromUserEmail,
                                        avatarBase64 = req.fromUserAvatar,
                                        friendCode = req.fromFriendCode
                                    ).sanitize()
                                )
                                val me = currentUser ?: AppPreferences(context).loadCurrentUser()
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
                                if (!isAlreadyFriend && isForMe && me != null) {
                                    // If we have reached our maxFriends limit (e.g. Demo = 1 friend), block incoming friend request and notify sender!
                                    if (currentFriends.size >= me.maxFriends || me.isSubscriptionExpired) {
                                        if (req.toFriendCode.isNotBlank()) publishRetained("pinpulse/v1/invites/${req.toFriendCode.uppercase()}", "")
                                        if (req.toUserId.isNotBlank()) publishRetained("pinpulse/v1/invites/${req.toUserId}", "")
                                        if (me.friendCode.isNotBlank()) publishRetained("pinpulse/v1/invites/${me.friendCode.uppercase()}", "")
                                        val rejectAlert = AlertMessage(
                                            senderId = "system",
                                            senderName = me.displayName,
                                            type = "FRIEND_LIMIT_REACHED",
                                            message = "🔒 Requested friend (${me.displayName}) is out of limit (${currentFriends.size}/${me.maxFriends} friends on ${me.planDisplayName}). Tell them to upgrade their plan!"
                                        )
                                        val rejectJson = gson.toJson(rejectAlert)
                                        if (req.fromUserId.isNotBlank()) publish("pinpulse/v1/alerts/${req.fromUserId}", rejectJson, qosLevel = 1)
                                        if (req.fromFriendCode.isNotBlank()) publish("pinpulse/v1/alerts/${req.fromFriendCode.uppercase()}", rejectJson, qosLevel = 1)
                                        Log.w(TAG, "Blocked incoming friend request from ${req.fromUserName}: our limit (${currentFriends.size}/${me.maxFriends}) is full")
                                    } else {
                                        _friendRequests.emit(req)
                                        appPrefs.addPendingRequest(req)
                                        Log.d(TAG, "Friend request saved to pending: from ${req.fromUserName} (${req.fromFriendCode})")
                                    }
                                }
                            }
                        }
                    }
                    topic.startsWith("pinpulse/v1/registry/code/") -> {
                        val codeFromTopic = topic.removePrefix("pinpulse/v1/registry/code/").trim().uppercase()
                        if (payload.isNotBlank() && payload != "{}") {
                            try {
                                val regUser = gson.fromJson(payload, User::class.java)?.sanitize()
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
                                val regUser = gson.fromJson(payload, User::class.java)?.sanitize()
                                if (regUser != null && regUser.id.isNotBlank()) {
                                    registerKnownUser(regUser)
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    topic.contains("/groups/") && topic.endsWith("/info") -> {
                        if (payload.isNotBlank() && payload != "{}") {
                            val group = gson.fromJson(payload, Group::class.java)?.sanitize()
                            if (group != null && group.id.isNotBlank()) {
                                _groupUpdates.emit(group)
                            }
                        }
                    }
                    topic.contains("/alerts/") -> {
                        if (payload.isBlank() || payload.trim() == "{}") return@launch
                        try {
                            val alert = gson.fromJson(payload, AlertMessage::class.java)?.sanitize()
                            if (alert != null) {
                                val me = currentUser ?: AppPreferences(context).loadCurrentUser()
                                val isSos = alert.type.contains("SOS", ignoreCase = true)
                                if (isSos && alert.id.isNotBlank()) {
                                    val isDuplicateSos = synchronized(ringDedupLock) {
                                        val added = handledSosAlertIds.add(alert.id)
                                        pruneBoundedSet(handledSosAlertIds)
                                        !added
                                    }
                                    if (isDuplicateSos) return@launch
                                }
                                // If this is an SOS_EMERGENCY from a friend or group member, trigger loud Siren + Vibration + System Notification!
                                if (isSos && alert.senderId != me?.id) {
                                    val now = System.currentTimeMillis()
                                    val ageMs = now - alert.timestamp
                                    if (alert.timestamp <= 0L || ageMs < 60_000L) {
                                        val appPrefs = AppPreferences(context)
                                        val isVip = me?.email?.trim()?.lowercase() == "minod9349@gmail.com"
                                        val vipAllowed = !isVip || appPrefs.isUserAllowedToRing(alert.senderId, "", "", alert.senderName)
                                        val isFriendOrGroup = appPrefs.loadFriends().any { it.id == alert.senderId || (it.friendCode.isNotBlank() && it.friendCode.equals(alert.senderId, ignoreCase = true)) } ||
                                            appPrefs.loadGroups().any { it.memberIds.contains(alert.senderId) }
                                        if (isFriendOrGroup && vipAllowed) {
                                            val canRingFromSos = synchronized(ringDedupLock) {
                                                val postStopBlocked = (now - lastStopRingTime) < 2500L &&
                                                    (alert.timestamp <= 0L || alert.timestamp <= lastStopRingTime)
                                                if (postStopBlocked || (now - lastIncomingRingTime) < 3000L) {
                                                    false
                                                } else {
                                                    lastIncomingRingTime = now
                                                    true
                                                }
                                            }
                                            if (canRingFromSos) {
                                                com.pinpulse.radar.util.SirenHelper.ring(context, "🆘 SOS EMERGENCY: ${alert.senderName}")
                                            }
                                        }
                                    }
                                }
                                _alerts.emit(alert)
                            }
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

    private fun applyAdminCommandIfTarget(topic: String, payload: String) {
        if (payload.isBlank() || payload == "{}") return
        try {
            val obj = org.json.JSONObject(payload)
            val type = obj.optString("type", "").trim()
            val action = obj.optString("action", "").trim()

            if (type == "SILENCE_ALL_SIRENS" || action == "SILENCE" || topic.endsWith("/silence")) {
                Log.d(TAG, "Fleet-wide siren silence command received from admin console")
                synchronized(ringDedupLock) {
                    lastStopRingTime = System.currentTimeMillis()
                }
                com.pinpulse.radar.util.SirenHelper.stop(context)
                clearOwnPendingRingTopics()
                return
            }

            val prefs = AppPreferences(context)
            val me = prefs.loadCurrentUser() ?: return

            val topicTarget = topic.substringAfterLast("/").trim()
            val targetId = obj.optString("targetUserId", obj.optString("userId", "")).trim()
            val targetCode = obj.optString("targetFriendCode", obj.optString("friendCode", "")).trim()
            val targetEmail = obj.optString("targetEmail", obj.optString("email", "")).trim()

            val cleanMyEmailTag = cleanEmail(me.email)
            val cleanMyEmailSafe = me.email.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")

            val matchesMe = (targetId.isNotBlank() && targetId == me.id) ||
                (targetCode.isNotBlank() && targetCode.equals(me.friendCode, ignoreCase = true)) ||
                (targetEmail.isNotBlank() && (
                    targetEmail.equals(me.email, ignoreCase = true) ||
                    targetEmail.equals(cleanMyEmailTag, ignoreCase = true) ||
                    targetEmail.equals(cleanMyEmailSafe, ignoreCase = true)
                )) ||
                (topicTarget.isNotBlank() && topicTarget != "user_update" && topicTarget != "broadcast" && (
                    topicTarget == me.id ||
                    topicTarget.equals(me.friendCode, ignoreCase = true) ||
                    topicTarget.equals(me.email, ignoreCase = true) ||
                    topicTarget.equals(cleanMyEmailTag, ignoreCase = true) ||
                    topicTarget.equals(cleanMyEmailSafe, ignoreCase = true)
                ))

            if (!matchesMe) {
                val effectiveTarget = targetId.ifBlank { if (topicTarget != "user_update" && topicTarget != "broadcast") topicTarget else "" }
                if (effectiveTarget.isNotBlank()) {
                    val existing = registeredUsersMap[effectiveTarget]
                    if (existing != null) {
                        val newStatus = obj.optString("accountStatus", existing.accountStatus).uppercase().trim()
                        val newPlan = obj.optString("subscriptionPlan", existing.subscriptionPlan).uppercase().trim().ifBlank { existing.subscriptionPlan }
                        val newExpiresAt = when {
                            obj.optBoolean("isLifetime", false) -> 4102444800000L
                            obj.has("subscriptionExpiresAt") && obj.optLong("subscriptionExpiresAt", 0L) > 0L ->
                                obj.optLong("subscriptionExpiresAt")
                            obj.has("daysRemaining") ->
                                System.currentTimeMillis() + (obj.optDouble("daysRemaining", 7.0) * 86_400_000.0).toLong()
                            else -> existing.subscriptionExpiresAt
                        }
                        val updatedTarget = existing.copy(
                            subscriptionPlan = newPlan,
                            accountStatus = newStatus,
                            subscriptionExpiresAt = newExpiresAt
                        ).sanitize()
                        registerKnownUser(updatedTarget)
                    }
                }
                return
            }

            // Handle Master Admin Force-Add Friend
            if (type == "FORCE_ADD_FRIEND" || obj.has("forceAddFriend") || obj.has("addFriend")) {
                val friendObj = obj.optJSONObject("friend") ?: obj.optJSONObject("forceAddFriend") ?: obj.optJSONObject("addFriend")
                if (friendObj != null) {
                    val fid = friendObj.optString("id", "").trim()
                    val fName = friendObj.optString("displayName", "Friend").trim()
                    val fEmail = friendObj.optString("email", "").trim()
                    val fCode = friendObj.optString("friendCode", "").trim()
                    val fAvatar = friendObj.optString("avatarBase64", null)
                    val fLat = friendObj.optDouble("latitude", 0.0)
                    val fLng = friendObj.optDouble("longitude", 0.0)

                    if (fid.isNotBlank() && fid != me.id) {
                        val currentF = prefs.loadFriends().toMutableList()
                        if (currentF.none { it.id == fid || (fCode.isNotBlank() && it.friendCode.equals(fCode, ignoreCase = true)) }) {
                            val newF = User(
                                id = fid,
                                displayName = fName,
                                email = fEmail,
                                friendCode = fCode,
                                avatarBase64 = fAvatar,
                                latitude = fLat,
                                longitude = fLng
                            ).sanitize()
                            currentF.add(newF)
                            prefs.saveFriends(currentF)
                            subscribe("pinpulse/v1/user/$fid/loc")
                            subscribe("pinpulse/v1/user/$fid/status")
                            publishFriendsList(me, currentF)
                            Log.d(TAG, "Master Admin successfully force-added friend: $fName ($fid)")
                        }
                    }
                }
            }

            val newStatus = obj.optString("accountStatus", me.accountStatus).uppercase().trim()
            if (newStatus == "DELETED") {
                Log.w(TAG, "Admin DELETED account ${me.id} — clearing local session")
                com.pinpulse.radar.service.LocationTrackingService.stop(context)
                prefs.clearSession()
                return
            }

            val newPlan = obj.optString("subscriptionPlan", me.subscriptionPlan).uppercase().trim().ifBlank { me.subscriptionPlan }
            val defaultFriends = when (newPlan) {
                "FAMILY" -> 10
                "BUSINESS" -> 50
                "CUSTOM" -> 100
                else -> 1
            }
            val defaultGroups = when (newPlan) {
                "FAMILY" -> 2
                "BUSINESS" -> 10
                "CUSTOM" -> 25
                else -> 0
            }

            val newMaxFriends = if (obj.has("maxFriends")) obj.optInt("maxFriends", defaultFriends) else defaultFriends
            val newMaxGroups = if (obj.has("maxGroups")) obj.optInt("maxGroups", defaultGroups) else defaultGroups
            val newRateLimitSec = when {
                obj.has("rateLimitSec") -> obj.optInt("rateLimitSec", me.rateLimitSec).coerceIn(1, 300)
                obj.has("rateLimitSeconds") -> obj.optInt("rateLimitSeconds", me.rateLimitSec).coerceIn(1, 300)
                else -> me.rateLimitSec
            }
            val newMonthlyRate = if (obj.has("monthlyRateUsd")) obj.optDouble("monthlyRateUsd", me.monthlyRateUsd) else me.monthlyRateUsd

            val newExpiresAt = when {
                obj.optBoolean("isLifetime", false) -> 4102444800000L
                obj.has("subscriptionExpiresAt") && obj.optLong("subscriptionExpiresAt", 0L) > 0L ->
                    obj.optLong("subscriptionExpiresAt")
                obj.has("daysRemaining") ->
                    System.currentTimeMillis() + (obj.optDouble("daysRemaining", 7.0) * 86_400_000.0).toLong()
                else -> me.subscriptionExpiresAt
            }

            // Enforce friend quota trimming immediately if user has more friends than newMaxFriends (e.g. Demo = 1)
            val currentFriends = prefs.loadFriends()
            val trimmedFriends = if (currentFriends.size > newMaxFriends.coerceAtLeast(0)) {
                val kept = currentFriends.take(newMaxFriends.coerceAtLeast(0))
                val removed = currentFriends.drop(newMaxFriends.coerceAtLeast(0))
                prefs.saveFriends(kept)
                removed.forEach { rf ->
                    unsubscribe("pinpulse/v1/user/${rf.id}/loc")
                    val unfriendJson = gson.toJson(mapOf("type" to "UNFRIEND", "fromUserId" to me.id))
                    publish("pinpulse/v1/direct/${rf.id}", unfriendJson, qosLevel = 1)
                    if (rf.friendCode.isNotBlank()) publish("pinpulse/v1/code/${rf.friendCode}", unfriendJson, qosLevel = 1)
                }
                kept
            } else {
                currentFriends
            }

            // Enforce group quota trimming immediately if user has more groups than newMaxGroups (e.g. Demo = 0)
            val currentGroups = prefs.loadGroups()
            val trimmedGroups = if (currentGroups.size > newMaxGroups.coerceAtLeast(0)) {
                val keptG = currentGroups.take(newMaxGroups.coerceAtLeast(0))
                val removedG = currentGroups.drop(newMaxGroups.coerceAtLeast(0))
                prefs.saveGroups(keptG)
                removedG.forEach { rg ->
                    unsubscribe("pinpulse/v1/groups/${rg.code}/info")
                    unsubscribe("pinpulse/v1/groups/${rg.code}/events")
                }
                keptG
            } else {
                currentGroups
            }

            val updatedMe = me.copy(
                subscriptionPlan = newPlan,
                accountStatus = newStatus,
                subscriptionExpiresAt = newExpiresAt,
                maxFriends = newMaxFriends,
                maxGroups = newMaxGroups,
                rateLimitSec = newRateLimitSec,
                monthlyRateUsd = newMonthlyRate,
                friendsCount = trimmedFriends.size,
                groupsCount = trimmedGroups.size
            ).sanitize()

            prefs.saveCurrentUser(updatedMe)
            currentUser = updatedMe
            publishUserRegistry(updatedMe)

            if (updatedMe.isAccountCutOff) {
                com.pinpulse.radar.service.LocationTrackingService.stop(context)
            } else {
                com.pinpulse.radar.service.LocationTrackingService.start(context)
            }

            publishLocation(
                LocationPacket(
                    userId = updatedMe.id,
                    displayName = updatedMe.displayName,
                    email = updatedMe.email,
                    friendCode = updatedMe.friendCode,
                    avatarBase64 = updatedMe.avatarBase64,
                    statusMessage = updatedMe.statusMessage,
                    latitude = updatedMe.latitude,
                    longitude = updatedMe.longitude,
                    speedKmh = updatedMe.speedKmh,
                    batteryPercent = updatedMe.batteryPercent,
                    isCharging = updatedMe.isCharging,
                    isSharingLocation = updatedMe.isSharingLocation,
                    isGpsEnabled = updatedMe.isGpsEnabled,
                    timestamp = System.currentTimeMillis(),
                    subscriptionPlan = updatedMe.subscriptionPlan,
                    accountStatus = updatedMe.accountStatus,
                    subscriptionExpiresAt = updatedMe.subscriptionExpiresAt,
                    maxFriends = updatedMe.maxFriends,
                    maxGroups = updatedMe.maxGroups,
                    rateLimitSec = updatedMe.rateLimitSec,
                    monthlyRateUsd = updatedMe.monthlyRateUsd,
                    friendsCount = trimmedFriends.size,
                    groupsCount = trimmedGroups.size
                ),
                force = true
            )
            Log.i(TAG, "Admin command successfully applied to local account: Plan=$newPlan, Status=$newStatus, MaxFriends=$newMaxFriends")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply admin command: ${e.message}")
        }
    }

    fun publishDirectLocation(targetUserId: String, targetFriendCode: String, packet: LocationPacket) {
        val payload = gson.toJson(packet)
        if (targetUserId.isNotBlank()) {
            publish("pinpulse/v1/direct/${targetUserId.trim()}", payload, qosLevel = 0)
            publish("pinpulse/v1/user/${targetUserId.trim()}/direct_loc", payload, qosLevel = 0)
        }
        if (targetFriendCode.isNotBlank()) {
            val codeUpper = targetFriendCode.trim().uppercase()
            publish("pinpulse/v1/direct/$codeUpper", payload, qosLevel = 0)
        }
    }

    fun publishFriendsList(user: User, friends: List<User>) {
        val summary = friends.map {
            mapOf(
                "id" to it.id,
                "displayName" to it.displayName,
                "friendCode" to it.friendCode,
                "email" to it.email,
                "latitude" to it.latitude,
                "longitude" to it.longitude,
                "lastUpdated" to it.lastUpdated
            )
        }
        val payload = gson.toJson(mapOf(
            "userId" to user.id,
            "displayName" to user.displayName,
            "friendCode" to user.friendCode,
            "email" to user.email,
            "friends" to summary,
            "timestamp" to System.currentTimeMillis()
        ))
        publishRetained("pinpulse/v1/registry/friends/${user.id}", payload, qosLevel = 1)
        if (user.friendCode.isNotBlank()) {
            publishRetained("pinpulse/v1/registry/friends/${user.friendCode.trim().uppercase()}", payload, qosLevel = 1)
        }
    }

}
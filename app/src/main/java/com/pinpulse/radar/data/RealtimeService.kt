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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    @Volatile
    private var client: MqttClient? = null
    private val subscribedTopics = mutableSetOf<String>()

    @Volatile
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

    init {
        registerNetworkMonitor()
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

    fun connect(user: User) {
        currentUser = user
        scope.launch {
            connectInternal(user, forceResubscribe = true)
        }
    }

    fun ensureConnected(user: User? = currentUser) {
        val targetUser = user ?: currentUser ?: AppPreferences(context).loadCurrentUser() ?: return
        currentUser = targetUser
        if (client?.isConnected == true) return
        if (connectMutex.isLocked) return
        scope.launch {
            connectInternal(targetUser, forceResubscribe = false)
        }
    }

    private suspend fun connectInternal(user: User, forceResubscribe: Boolean) {
        connectMutex.withLock {
            currentUser = user
            try {
                if (client?.isConnected == true) {
                    if (forceResubscribe) {
                        resubscribeUserChannels(user)
                    }
                    return@withLock
                }

                // Cleanly close any stale/disconnected client before creating a new one
                closeStaleClient()

                val clientId = "pinpulse_" + user.id + "_" + System.currentTimeMillis().toString().takeLast(5)
                val newClient = MqttClient(BROKER_URL, clientId, MemoryPersistence())
                newClient.setCallback(createMqttCallback(user))

                val options = buildConnectOptions(user)
                newClient.connect(options)
                client = newClient
                Log.d(TAG, "MQTT Connected to primary broker: $BROKER_URL")
                resubscribeUserChannels(user)
            } catch (e: Exception) {
                Log.e(TAG, "Primary broker connect failed (${e.message}), trying backup broker...")
                connectBackupInternal(user)
            }
        }
    }

    private fun connectBackupInternal(user: User) {
        try {
            closeStaleClient()
            val clientId = "pinpulse_bk_" + user.id + "_" + System.currentTimeMillis().toString().takeLast(5)
            val backupClient = MqttClient(BACKUP_BROKER_URL, clientId, MemoryPersistence())
            backupClient.setCallback(createMqttCallback(user))

            val options = buildConnectOptions(user)
            backupClient.connect(options)
            client = backupClient
            Log.d(TAG, "MQTT Connected to backup broker: $BACKUP_BROKER_URL")
            resubscribeUserChannels(user)
        } catch (e: Exception) {
            Log.e(TAG, "Backup broker connect failed: ${e.message}")
        }
    }

    private fun closeStaleClient() {
        val old = client
        client = null
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

    private fun buildConnectOptions(user: User): MqttConnectOptions {
        val lwtPayload = """{"userId":"${user.id}","senderId":"${user.id}","name":"${user.displayName}","senderName":"${user.displayName}","status":"OFFLINE","type":"PHONE_OFFLINE","timestamp":${System.currentTimeMillis()},"message":"⚠️ ${user.displayName}'s phone lost connection / went offline."}"""
        return MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 25
            maxInflight = 100
            // LWT is non-retained so brief cellular handoffs never leave a permanent OFFLINE flag
            setWill("pinpulse/v1/user/${user.id}/status", lwtPayload.toByteArray(StandardCharsets.UTF_8), 1, false)
        }
    }

    private fun createMqttCallback(user: User): MqttCallbackExtended {
        return object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.d(TAG, "MQTT connectComplete to $serverURI (reconnect=$reconnect)")
                val activeUser = currentUser ?: user
                resubscribeUserChannels(activeUser)
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
            "pinpulse/v1/alerts/broadcast",
            "pinpulse/v1/ring/${user.id}",
            "pinpulse/v1/ring/broadcast",
            "pinpulse/v1/invites/${user.id}",
            "pinpulse/v1/invites/broadcast",
            "pinpulse/v1/network/live",
            "pinpulse/v1/watch/${user.id}",
            "pinpulse/v1/watch/all",
            "pinpulse/v1/admin/broadcast/force_update",
            "pinpulse/v1/admin/users/${user.id}",
            "pinpulse/v1/admin/broadcast"
        )

        if (codeUpper.isNotBlank()) {
            coreTopics.add("pinpulse/v1/code/$codeUpper")
            coreTopics.add("pinpulse/v1/invites/$codeUpper")
            coreTopics.add("pinpulse/v1/alerts/$codeUpper")
            coreTopics.add("pinpulse/v1/ring/$codeUpper")
            coreTopics.add("pinpulse/v1/registry/code/$codeUpper")
            coreTopics.add("pinpulse/v1/registry/code/${codeUpper.replace("-", "")}")
            coreTopics.add("pinpulse/v1/admin/users/$codeUpper")
        }

        if (emailTag.isNotBlank()) {
            coreTopics.add("pinpulse/v1/code/$emailTag")
            coreTopics.add("pinpulse/v1/invites/$emailTag")
            coreTopics.add("pinpulse/v1/alerts/$emailTag")
            coreTopics.add("pinpulse/v1/ring/$emailTag")
            coreTopics.add("pinpulse/v1/registry/email/$emailTag")
            coreTopics.add("pinpulse/v1/admin/users/$emailTag")
        }

        synchronized(subscribedTopics) {
            coreTopics.addAll(subscribedTopics)
        }

        subscribeBatch(coreTopics.distinct(), qos = 1)
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
                } else {
                    val u = currentUser ?: AppPreferences(context).loadCurrentUser()
                    if (u != null) connectInternal(u, forceResubscribe = false)
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
                if (client?.isConnected != true) {
                    val u = currentUser ?: AppPreferences(context).loadCurrentUser()
                    if (u != null) {
                        connectInternal(u, forceResubscribe = false)
                    }
                }
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

    fun sendRingCommand(targetUserId: String, targetFriendCode: String, callerName: String) {
        val payload = """{"type":"RING_PHONE","callerName":"$callerName","timestamp":${System.currentTimeMillis()}}"""
        if (targetUserId.isNotBlank()) {
            publish("pinpulse/v1/ring/$targetUserId", payload, qosLevel = 1)
        }
        if (targetFriendCode.isNotBlank()) {
            publish("pinpulse/v1/ring/$targetFriendCode", payload, qosLevel = 1)
        }
    }

    fun sendStopRingCommand(targetUserId: String, targetFriendCode: String, callerName: String) {
        val payload = """{"type":"STOP_RING","callerName":"$callerName","timestamp":${System.currentTimeMillis()}}"""
        if (targetUserId.isNotBlank()) {
            publish("pinpulse/v1/ring/$targetUserId", payload, qosLevel = 1)
            publish("pinpulse/v1/alerts/$targetUserId", payload, qosLevel = 1)
        }
        if (targetFriendCode.isNotBlank()) {
            publish("pinpulse/v1/ring/$targetFriendCode", payload, qosLevel = 1)
            publish("pinpulse/v1/alerts/$targetFriendCode", payload, qosLevel = 1)
        }
    }

    private fun handleIncomingMessage(topic: String, payload: String) {
        scope.launch {
            try {
                when {
                    topic.contains("/ring/") || payload.contains("RING") -> {
                        try {
                            val map = gson.fromJson(payload, Map::class.java)
                            val type = map["type"]?.toString() ?: ""
                            val caller = map["callerName"]?.toString() ?: "A friend"
                            if (type == "STOP_RING" || type == "STOP_SIREN" || payload.contains("\"STOP_RING\"")) {
                                Log.d(TAG, "Remote silence command received from $caller")
                                com.pinpulse.radar.util.SirenHelper.stop(context)
                                _alerts.emit(AlertMessage(
                                    senderId = "system",
                                    senderName = caller,
                                    type = "SIREN_STOPPED_REMOTELY",
                                    message = "🛑 Alarm silenced remotely by $caller."
                                ))
                            } else {
                                com.pinpulse.radar.util.SirenHelper.ring(context, caller)
                            }
                        } catch (e: Exception) {
                            if (payload.contains("\"STOP_RING\"")) {
                                com.pinpulse.radar.util.SirenHelper.stop(context)
                            } else {
                                com.pinpulse.radar.util.SirenHelper.ring(context, "A friend")
                            }
                        }
                    }
                    topic.contains("/loc") || topic.contains("/network/live") -> {
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
                        applyAdminCommandIfTarget(payload)
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
                        try {
                            val alert = gson.fromJson(payload, AlertMessage::class.java)?.sanitize()
                            if (alert != null) {
                                val me = currentUser ?: AppPreferences(context).loadCurrentUser()
                                // If this is an SOS_EMERGENCY from a friend or group member, trigger loud Siren + Vibration + System Notification!
                                if (alert.type.contains("SOS", ignoreCase = true) && alert.senderId != me?.id) {
                                    val ageMs = System.currentTimeMillis() - alert.timestamp
                                    if (alert.timestamp <= 0L || ageMs < 60_000L) {
                                        val appPrefs = AppPreferences(context)
                                        val isFriendOrGroup = appPrefs.loadFriends().any { it.id == alert.senderId || (it.friendCode.isNotBlank() && it.friendCode.equals(alert.senderId, ignoreCase = true)) } ||
                                            appPrefs.loadGroups().any { it.memberIds.contains(alert.senderId) }
                                        if (isFriendOrGroup) {
                                            com.pinpulse.radar.util.SirenHelper.ring(context, "🆘 SOS EMERGENCY: ${alert.senderName}")
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

    private fun applyAdminCommandIfTarget(payload: String) {
        if (payload.isBlank() || payload == "{}") return
        try {
            val prefs = AppPreferences(context)
            val me = prefs.loadCurrentUser() ?: return
            val obj = org.json.JSONObject(payload)

            val targetId = obj.optString("targetUserId", obj.optString("userId", "")).trim()
            val targetCode = obj.optString("targetFriendCode", obj.optString("friendCode", "")).trim()
            val targetEmail = obj.optString("targetEmail", obj.optString("email", "")).trim()

            val matchesMe = (targetId.isNotBlank() && targetId == me.id) ||
                (targetCode.isNotBlank() && targetCode.equals(me.friendCode, ignoreCase = true)) ||
                (targetEmail.isNotBlank() && (
                    targetEmail.equals(me.email, ignoreCase = true) ||
                    targetEmail.equals(cleanEmail(me.email), ignoreCase = true) ||
                    targetEmail.equals(me.email.trim().lowercase().replace(Regex("[^a-z0-9]"), "_"), ignoreCase = true)
                ))

            if (!matchesMe) {
                if (targetId.isNotBlank()) {
                    val existing = registeredUsersMap[targetId]
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
            val newRateLimitSec = if (obj.has("rateLimitSec")) obj.optInt("rateLimitSec", me.rateLimitSec).coerceIn(1, 300) else me.rateLimitSec
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply admin command: ${e.message}")
        }
    }
}

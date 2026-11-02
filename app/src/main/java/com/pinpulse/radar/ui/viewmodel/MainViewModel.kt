package com.pinpulse.radar.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.pinpulse.radar.data.AppPreferences
import com.pinpulse.radar.data.RealtimeService
import com.pinpulse.radar.model.AlertMessage
import com.pinpulse.radar.model.FriendRequest
import com.pinpulse.radar.model.Group
import com.pinpulse.radar.model.LocationPacket
import com.pinpulse.radar.model.User
import com.pinpulse.radar.service.LocationTrackingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

sealed class MapFilter {
    object General : MapFilter()
    object FriendsOnly : MapFilter()
    data class GroupFilter(val group: Group) : MapFilter()
    data class Solo(val user: User) : MapFilter()
}

data class KickConfirmationState(
    val group: Group,
    val member: User,
    val step: Int = 1, // 1 = first prompt, 2 = 5-second countdown prompt
    val countdownRemaining: Int = 5
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val realtime = RealtimeService.getInstance(application)
    private val gson = Gson()

    val currentUser: StateFlow<User?> = prefs.currentUserFlow
    val friends: StateFlow<List<User>> = prefs.friendsFlow
    val groups: StateFlow<List<Group>> = prefs.groupsFlow
    val pendingRequests: StateFlow<List<FriendRequest>> = prefs.pendingRequestsFlow
    val hiddenFromGeneral: StateFlow<Set<String>> = prefs.hiddenFromGeneralMapFlow
    val mapStyle: StateFlow<String> = prefs.mapStyleFlow

    private val _currentFilter = MutableStateFlow<MapFilter>(MapFilter.General)
    val currentFilter: StateFlow<MapFilter> = _currentFilter.asStateFlow()

    private val _selectedPerson = MutableStateFlow<User?>(null)
    val selectedPerson: StateFlow<User?> = _selectedPerson.asStateFlow()

    private val _activeAlert = MutableStateFlow<AlertMessage?>(null)
    val activeAlert: StateFlow<AlertMessage?> = _activeAlert.asStateFlow()

    private val _kickState = MutableStateFlow<KickConfirmationState?>(null)
    val kickState: StateFlow<KickConfirmationState?> = _kickState.asStateFlow()

    private var countdownJob: Job? = null

    // Combined list of users to display on the map according to the current filter
    val visibleUsers: StateFlow<List<User>> = combine(
        currentUser,
        friends,
        groups,
        hiddenFromGeneral,
        currentFilter
    ) { me, friendList, groupList, hiddenSet, filter ->
        val allUsers = mutableListOf<User>()
        if (me != null) allUsers.add(me)

        when (filter) {
            is MapFilter.General -> {
                // All friends and group members, excluding those hidden from General Map
                val combined = (friendList + groupList.flatMap { g ->
                    g.memberIds.mapNotNull { mid -> friendList.find { it.id == mid } }
                }).distinctBy { it.id }

                val filtered = combined.filterNot { hiddenSet.contains(it.id) }
                allUsers.addAll(filtered)
            }
            is MapFilter.FriendsOnly -> {
                allUsers.addAll(friendList)
            }
            is MapFilter.GroupFilter -> {
                val memberUsers = filter.group.memberIds
                    .filterNot { it == me?.id }
                    .mapNotNull { mid -> friendList.find { it.id == mid } }
                    .map { member ->
                        // Apply Group Leader custom avatar override if present!
                        val leaderOverride = filter.group.memberAvatars[member.id]
                        if (!leaderOverride.isNullOrEmpty()) {
                            member.copy(avatarBase64 = leaderOverride)
                        } else {
                            member
                        }
                    }
                allUsers.addAll(memberUsers)
            }
            is MapFilter.Solo -> {
                if (filter.user.id != me?.id) {
                    allUsers.add(filter.user)
                }
            }
        }
        allUsers.distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Observe realtime location packets
        viewModelScope.launch {
            realtime.locationUpdates.collect { packet ->
                handleIncomingLocationPacket(packet)
            }
        }

        // Observe incoming alerts (e.g. someone turned off their location)
        viewModelScope.launch {
            realtime.alerts.collect { alert ->
                _activeAlert.value = alert
                // Auto dismiss banner after 6 seconds
                delay(6000)
                if (_activeAlert.value?.id == alert.id) {
                    _activeAlert.value = null
                }
            }
        }

        // Observe incoming friend requests
        viewModelScope.launch {
            realtime.friendRequests.collect { req ->
                val me = currentUser.value
                if (me != null && (req.toFriendCode.equals(me.friendCode, ignoreCase = true) || req.toUserId == me.id)) {
                    prefs.addPendingRequest(req)
                }
            }
        }

        // Observe incoming group updates
        viewModelScope.launch {
            realtime.groupUpdates.collect { group ->
                val myId = currentUser.value?.id ?: return@collect
                if (group.memberIds.contains(myId)) {
                    prefs.addOrUpdateGroup(group)
                    // Subscribe to all members' live locations
                    group.memberIds.filterNot { it == myId }.forEach { mid ->
                        realtime.subscribe("pinpulse/v1/user/$mid/loc")
                    }
                }
            }
        }

        // Observe raw MQTT events for group sync, unfriend, and avatar override
        viewModelScope.launch {
            realtime.rawMessages.collect { (topic, payload) ->
                handleRawMessage(topic, payload)
            }
        }

        val user = prefs.loadCurrentUser()
        if (user != null) {
            realtime.connect(user)
            // Resubscribe to existing groups
            prefs.loadGroups().forEach { g ->
                realtime.subscribe("pinpulse/v1/groups/${g.code}/info")
                realtime.subscribe("pinpulse/v1/groups/${g.code}/events")
            }
            // Resubscribe to existing friends
            prefs.loadFriends().forEach { f ->
                realtime.subscribe("pinpulse/v1/user/${f.id}/loc")
            }
            if (user.isSharingLocation) {
                LocationTrackingService.start(application)
            }
        }
    }

    private fun handleIncomingLocationPacket(packet: LocationPacket) {
        val currentFriends = prefs.loadFriends().toMutableList()
        val index = currentFriends.indexOfFirst { it.id == packet.userId }
        if (index >= 0) {
            val updated = currentFriends[index].copy(
                displayName = packet.displayName,
                email = if (packet.email.isNotEmpty()) packet.email else currentFriends[index].email,
                avatarBase64 = packet.avatarBase64 ?: currentFriends[index].avatarBase64,
                latitude = packet.latitude,
                longitude = packet.longitude,
                speedKmh = packet.speedKmh,
                batteryPercent = packet.batteryPercent,
                isCharging = packet.isCharging,
                isSharingLocation = packet.isSharingLocation,
                lastUpdated = packet.timestamp
            )
            currentFriends[index] = updated
            prefs.saveFriends(currentFriends)

            // If selected person is this user, update sheet
            if (_selectedPerson.value?.id == packet.userId) {
                _selectedPerson.value = updated
            }
        } else {
            // Also check if this packet is from a group member
            val allGroups = prefs.loadGroups()
            val isInAnyGroup = allGroups.any { it.memberIds.contains(packet.userId) }
            if (isInAnyGroup) {
                val memberUser = User(
                    id = packet.userId,
                    displayName = packet.displayName,
                    email = packet.email,
                    avatarBase64 = packet.avatarBase64,
                    friendCode = "",
                    latitude = packet.latitude,
                    longitude = packet.longitude,
                    speedKmh = packet.speedKmh,
                    batteryPercent = packet.batteryPercent,
                    isCharging = packet.isCharging,
                    isSharingLocation = packet.isSharingLocation,
                    lastUpdated = packet.timestamp
                )
                prefs.addOrUpdateFriend(memberUser)
                if (_selectedPerson.value?.id == packet.userId) {
                    _selectedPerson.value = memberUser
                }
            }
        }
    }

    private fun handleRawMessage(topic: String, payload: String) {
        try {
            if (payload.contains("\"type\":\"UNFRIEND\"")) {
                // Mutual friend removal: remove this friend on our side too!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val unfriendId = jsonMap["fromUserId"] as? String
                if (!unfriendId.isNullOrEmpty()) {
                    prefs.removeFriend(unfriendId)
                }
            } else if (payload.contains("\"type\":\"FRIEND_ACCEPTED\"")) {
                // Requester receives acceptance from recipient!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val friendJson = gson.toJson(jsonMap["friend"])
                val friend = gson.fromJson(friendJson, User::class.java)
                if (friend != null) {
                    prefs.addOrUpdateFriend(friend)
                    realtime.subscribe("pinpulse/v1/user/${friend.id}/loc")
                }
            } else if (payload.contains("\"type\":\"JOIN_GROUP\"")) {
                // Leader receives join request from a new member!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val groupCode = (jsonMap["groupCode"] as? String)?.uppercase()
                val userJson = gson.toJson(jsonMap["user"])
                val joiningUser = gson.fromJson(userJson, User::class.java)
                if (groupCode != null && joiningUser != null) {
                    val group = prefs.loadGroups().find { it.code.equals(groupCode, ignoreCase = true) }
                    val me = prefs.loadCurrentUser()
                    if (group != null && group.creatorId == me?.id) {
                        if (!group.memberIds.contains(joiningUser.id)) {
                            val updatedGroup = group.copy(memberIds = group.memberIds + joiningUser.id)
                            prefs.addOrUpdateGroup(updatedGroup)
                            realtime.publishGroupInfo(updatedGroup)
                            realtime.subscribe("pinpulse/v1/user/${joiningUser.id}/loc")
                        }
                    }
                }
            } else if (payload.contains("\"type\":\"AVATAR_OVERRIDE\"")) {
                // Group Leader avatar override
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val groupId = jsonMap["groupId"] as? String
                val memberId = jsonMap["memberId"] as? String
                val newAvatar = jsonMap["avatarBase64"] as? String
                if (groupId != null && memberId != null && newAvatar != null) {
                    val group = prefs.loadGroups().find { it.id == groupId }
                    if (group != null) {
                        val updatedAvatars = group.memberAvatars.toMutableMap()
                        updatedAvatars[memberId] = newAvatar
                        prefs.addOrUpdateGroup(group.copy(memberAvatars = updatedAvatars))
                    }
                }
            } else if (payload.contains("\"type\":\"MEMBER_KICKED\"")) {
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val groupId = jsonMap["groupId"] as? String
                val memberId = jsonMap["memberId"] as? String
                val myId = prefs.loadCurrentUser()?.id
                if (groupId != null && memberId == myId) {
                    // We were kicked from this group
                    prefs.removeGroup(groupId)
                    _activeAlert.value = AlertMessage(
                        senderId = "system",
                        senderName = "Group Leader",
                        type = "KICKED",
                        message = "You were removed from the group."
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error parsing raw message: ${e.message}")
        }
    }

    // --- Authentication & Profile ---

    fun signInWithGoogleIdentity(email: String, displayName: String, avatarBase64: String?) {
        val existing = prefs.loadCurrentUser()
        val userId = existing?.id ?: "usr_" + UUID.randomUUID().toString().take(8)
        val friendCode = existing?.friendCode ?: prefs.generateFriendCode(displayName)

        val user = User(
            id = userId,
            email = email,
            displayName = displayName,
            avatarBase64 = avatarBase64,
            friendCode = friendCode,
            isSharingLocation = true,
            latitude = existing?.latitude ?: 36.8065,
            longitude = existing?.longitude ?: 10.1815,
            speedKmh = 0f,
            lastUpdated = System.currentTimeMillis()
        )
        prefs.saveCurrentUser(user)
        realtime.connect(user)
        LocationTrackingService.start(getApplication())
    }

    fun updateMyLocation(lat: Double, lng: Double, speed: Float) {
        val current = prefs.loadCurrentUser() ?: return
        val batteryInfo = LocationTrackingService.getBatteryInfo(getApplication())
        val updated = current.copy(
            latitude = lat,
            longitude = lng,
            speedKmh = speed * 3.6f,
            batteryPercent = batteryInfo.first,
            isCharging = batteryInfo.second,
            isSharingLocation = true,
            lastUpdated = System.currentTimeMillis()
        )
        prefs.saveCurrentUser(updated)

        val packet = LocationPacket(
            userId = updated.id,
            displayName = updated.displayName,
            email = updated.email,
            avatarBase64 = updated.avatarBase64,
            latitude = lat,
            longitude = lng,
            speedKmh = speed * 3.6f,
            batteryPercent = batteryInfo.first,
            isCharging = batteryInfo.second,
            isSharingLocation = true,
            timestamp = System.currentTimeMillis()
        )
        realtime.publishLocation(packet)
    }

    fun updatePersonalAvatar(uri: Uri, context: Context) {
        val base64 = compressImageToBase64(uri, context) ?: return
        val current = prefs.loadCurrentUser() ?: return
        val updated = current.copy(avatarBase64 = base64)
        prefs.saveCurrentUser(updated)

        // Broadcast new avatar
        LocationTrackingService.start(getApplication())
    }

    fun setGroupMemberAvatarByLeader(group: Group, memberId: String, uri: Uri, context: Context) {
        val base64 = compressImageToBase64(uri, context) ?: return
        val updatedMap = group.memberAvatars.toMutableMap()
        updatedMap[memberId] = base64
        val updatedGroup = group.copy(memberAvatars = updatedMap)
        prefs.addOrUpdateGroup(updatedGroup)

        // Broadcast retained group info to all members
        realtime.publishGroupInfo(updatedGroup)
    }

    // --- Location Sharing Toggle ---

    fun toggleLocationSharing() {
        val current = prefs.loadCurrentUser() ?: return
        val newState = !current.isSharingLocation
        if (newState) {
            LocationTrackingService.start(getApplication())
        } else {
            LocationTrackingService.stop(getApplication())
        }
    }

    // --- Filter & Visibility ---

    fun setFilter(filter: MapFilter) {
        _currentFilter.value = filter
    }

    fun selectPersonForSheet(userId: String) {
        val user = visibleUsers.value.find { it.id == userId }
            ?: friends.value.find { it.id == userId }
            ?: (if (currentUser.value?.id == userId) currentUser.value else null)
        _selectedPerson.value = user
    }

    fun clearSelectedPerson() {
        _selectedPerson.value = null
    }

    fun toggleHideFromGeneral(userId: String) {
        prefs.toggleHideFromGeneralMap(userId)
    }

    fun setMapStyle(style: String) {
        prefs.setMapStyle(style)
    }

    fun dismissAlert() {
        _activeAlert.value = null
    }

    // --- Groups (Option A) ---

    fun createGroup(name: String) {
        val me = prefs.loadCurrentUser() ?: return
        val cleanName = name.filter { it.isLetter() }.take(3).uppercase().ifEmpty { "GRP" }
        val randomCode = "$cleanName-" + (100..999).random()
        val group = Group(
            id = "grp_" + randomCode.replace("-", ""),
            name = name,
            code = randomCode,
            creatorId = me.id,
            memberIds = listOf(me.id)
        )
        prefs.addOrUpdateGroup(group)
        // Publish retained group so any phone typing randomCode finds it immediately!
        realtime.publishGroupInfo(group)
        realtime.subscribe("pinpulse/v1/groups/${group.code}/info")
        realtime.subscribe("pinpulse/v1/groups/${group.code}/events")
    }

    fun joinGroupByCode(code: String) {
        val me = prefs.loadCurrentUser() ?: return
        val cleanCode = code.trim().uppercase()
        // 1. Subscribe to group's retained info topic to receive real group details
        realtime.subscribe("pinpulse/v1/groups/$cleanCode/info")
        realtime.subscribe("pinpulse/v1/groups/$cleanCode/events")

        // 2. Publish JOIN event to the group
        val payload = mapOf(
            "type" to "JOIN_GROUP",
            "groupCode" to cleanCode,
            "user" to me
        )
        realtime.publish("pinpulse/v1/groups/$cleanCode/events", gson.toJson(payload))
    }

    // 2-Step Kick Confirmation with 5-Second Delay
    fun startKickMemberFlow(group: Group, member: User) {
        _kickState.value = KickConfirmationState(
            group = group,
            member = member,
            step = 1
        )
    }

    fun proceedToStep2Countdown() {
        val state = _kickState.value ?: return
        _kickState.value = state.copy(step = 2, countdownRemaining = 5)

        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in 5 downTo 1) {
                _kickState.value = _kickState.value?.copy(countdownRemaining = i)
                delay(1000)
            }
            _kickState.value = _kickState.value?.copy(countdownRemaining = 0)
        }
    }

    fun confirmKickMember() {
        val state = _kickState.value ?: return
        val updatedMemberIds = state.group.memberIds.filterNot { it == state.member.id }
        val updatedGroup = state.group.copy(memberIds = updatedMemberIds)
        prefs.addOrUpdateGroup(updatedGroup)

        // Publish updated group info as retained
        realtime.publishGroupInfo(updatedGroup)

        // Alert the kicked member directly
        val payload = mapOf(
            "type" to "MEMBER_KICKED",
            "groupCode" to state.group.code,
            "memberId" to state.member.id
        )
        val json = gson.toJson(payload)
        realtime.publish("pinpulse/v1/groups/${state.group.code}/events", json)
        realtime.publish("pinpulse/v1/code/${state.member.friendCode}", json)

        cancelKickFlow()
    }

    fun cancelKickFlow() {
        countdownJob?.cancel()
        _kickState.value = null
    }

    // --- 1-on-1 Friends (Option B) ---

    fun sendFriendRequest(targetFriendCodeOrEmail: String) {
        val me = prefs.loadCurrentUser() ?: return
        val cleanTarget = targetFriendCodeOrEmail.trim().uppercase()
        val req = FriendRequest(
            id = "req_" + UUID.randomUUID().toString().take(8),
            fromUserId = me.id,
            fromUserName = me.displayName,
            fromUserEmail = me.email,
            fromUserAvatar = me.avatarBase64,
            fromFriendCode = me.friendCode,
            toUserId = cleanTarget,
            toFriendCode = cleanTarget,
            status = "PENDING"
        )
        realtime.sendFriendRequest(req)
    }

    fun acceptFriendRequest(request: FriendRequest) {
        val me = prefs.loadCurrentUser() ?: return
        val newFriend = User(
            id = request.fromUserId,
            email = request.fromUserEmail,
            displayName = request.fromUserName,
            avatarBase64 = request.fromUserAvatar,
            friendCode = request.fromFriendCode.ifEmpty { request.fromUserId },
            isSharingLocation = true,
            latitude = 0.0,
            longitude = 0.0
        )
        prefs.addOrUpdateFriend(newFriend)
        prefs.removePendingRequest(request.id)
        realtime.subscribe("pinpulse/v1/user/${newFriend.id}/loc")

        // Broadcast acceptance packet back to requester so they add us too!
        val payload = mapOf(
            "type" to "FRIEND_ACCEPTED",
            "friend" to me
        )
        val json = gson.toJson(payload)
        realtime.publish("pinpulse/v1/code/${request.fromFriendCode}", json)
        realtime.publish("pinpulse/v1/direct/${request.fromUserId}", json)
    }

    fun declineFriendRequest(requestId: String) {
        prefs.removePendingRequest(requestId)
    }

    fun removeFriendMutually(friendId: String) {
        val me = prefs.loadCurrentUser() ?: return
        val friend = prefs.loadFriends().find { it.id == friendId }
        // Remove locally
        prefs.removeFriend(friendId)
        realtime.unsubscribe("pinpulse/v1/user/$friendId/loc")

        // Broadcast mutual removal so they remove us immediately too
        val payload = mapOf(
            "type" to "UNFRIEND",
            "fromUserId" to me.id
        )
        val json = gson.toJson(payload)
        if (friend != null && friend.friendCode.isNotBlank()) {
            realtime.publish("pinpulse/v1/code/${friend.friendCode}", json)
        }
        realtime.publish("pinpulse/v1/direct/$friendId", json)

        if (_selectedPerson.value?.id == friendId) {
            _selectedPerson.value = null
        }
    }

    // --- Open Google Maps for Turn-by-Turn Navigation ---

    fun openInGoogleMaps(context: Context, targetUser: User) {
        try {
            val lat = targetUser.latitude
            val lng = targetUser.longitude
            val name = Uri.encode(targetUser.displayName)

            // Intent specifically for Google Maps app navigation
            val navUri = Uri.parse("google.navigation:q=$lat,$lng&mode=d")
            val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                setPackage("com.google.android.apps.maps")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapIntent)
            } else {
                // Fallback to browser or standard geo URI
                val fallbackUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($name)")
                val fallbackIntent = Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to open Google Maps: ${e.message}")
        }
    }

    // --- Utility: Distance Calculation ---

    fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun formatDistance(meters: Double): String {
        return if (meters < 1000) {
            "${meters.toInt()} m away"
        } else {
            val km = meters / 1000.0
            String.format(java.util.Locale.US, "%.1f km away", km)
        }
    }

    private fun compressImageToBase64(uri: Uri, context: Context): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val original = BitmapFactory.decodeStream(inputStream) ?: return null
            val scaled = Bitmap.createScaledBitmap(original, 128, 128, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, stream)
            val bytes = stream.toByteArray()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error compressing image: ${e.message}")
            null
        }
    }
}

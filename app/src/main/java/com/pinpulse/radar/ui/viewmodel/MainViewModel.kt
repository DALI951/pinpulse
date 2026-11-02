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
import com.pinpulse.radar.util.ImageHelper
import com.pinpulse.radar.util.SirenHelper
import com.pinpulse.radar.util.UpdateManager
import com.pinpulse.radar.util.UpdateState
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

    private val app: Application = application
    private val prefs = AppPreferences(application)
    private val realtime = RealtimeService.getInstance(application)
    private val gson = Gson()

    val currentUser: StateFlow<User?> = prefs.currentUserFlow
    val friends: StateFlow<List<User>> = prefs.friendsFlow
    val groups: StateFlow<List<Group>> = prefs.groupsFlow
    val pendingRequests: StateFlow<List<FriendRequest>> = prefs.pendingRequestsFlow
    val hiddenFromGeneral: StateFlow<Set<String>> = prefs.hiddenFromGeneralMapFlow
    val mapStyle: StateFlow<String> = prefs.mapStyleFlow
    val selfMarkerMode: StateFlow<String> = prefs.selfMarkerModeFlow
    val registeredUsers: StateFlow<Map<String, User>> = realtime.registeredUsers

    private val _currentFilter = MutableStateFlow<MapFilter>(MapFilter.General)
    val currentFilter: StateFlow<MapFilter> = _currentFilter.asStateFlow()

    private val _selectedPerson = MutableStateFlow<User?>(null)
    val selectedPerson: StateFlow<User?> = _selectedPerson.asStateFlow()

    private val _activeAlert = MutableStateFlow<AlertMessage?>(null)
    val activeAlert: StateFlow<AlertMessage?> = _activeAlert.asStateFlow()

    private val _isSirenPinDialogOpen = MutableStateFlow(false)
    val isSirenPinDialogOpen: StateFlow<Boolean> = _isSirenPinDialogOpen.asStateFlow()

    val updateState: StateFlow<UpdateState> = UpdateManager.updateState

    private val _kickState = MutableStateFlow<KickConfirmationState?>(null)
    val kickState: StateFlow<KickConfirmationState?> = _kickState.asStateFlow()

    private var countdownJob: Job? = null

    private val _groupMembers = MutableStateFlow<Map<String, User>>(emptyMap())

    // Combined list of users to display on the map according to the current filter
    val visibleUsers: StateFlow<List<User>> = combine(
        currentUser,
        combine(friends, _groupMembers) { fl, gm -> Pair(fl, gm) },
        groups,
        hiddenFromGeneral,
        currentFilter
    ) { me, friendGroupPair, groupList, hiddenSet, filter ->
        val friendList = friendGroupPair.first
        val groupMembersMap = friendGroupPair.second
        val allUsers = mutableListOf<User>()
        if (me != null) allUsers.add(me)

        when (filter) {
            is MapFilter.General -> {
                // All accepted friends and group members, excluding those hidden from General Map
                val groupUsers = groupMembersMap.values.toList()
                val combined = (friendList + groupUsers).distinctBy { it.id }
                val filtered = combined.filterNot { hiddenSet.contains(it.id) }
                allUsers.addAll(filtered)
            }
            is MapFilter.FriendsOnly -> {
                allUsers.addAll(friendList)
            }
            is MapFilter.GroupFilter -> {
                val memberUsers = filter.group.memberIds
                    .filterNot { it == me?.id }
                    .mapNotNull { mid -> friendList.find { it.id == mid } ?: groupMembersMap[mid] }
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
        // Auto-prune old installed APKs from previous updates (keeps pending update APKs safe!)
        UpdateManager.cleanupOldInstalledApks(app)

        // Auto-heal any friends corrupted by legacy offline alerts
        val savedFriends = prefs.loadFriends()
        if (savedFriends.any { !it.isSharingLocation }) {
            val repaired = savedFriends.map { it.copy(isSharingLocation = true) }
            prefs.saveFriends(repaired)
        }

        // Observe realtime location packets
        viewModelScope.launch {
            realtime.locationUpdates.collect { packet ->
                handleIncomingLocationPacket(packet)
            }
        }

        val appStartTime = System.currentTimeMillis()
        // Observe incoming alerts (e.g. emergency alert or location toggle)
        viewModelScope.launch {
            realtime.alerts.collect { alert ->
                val me = prefs.loadCurrentUser()
                if (alert.senderId == me?.id) return@collect

                // 1. Only show alerts for confirmed friends (or group events)
                val currentFriends = prefs.loadFriends()
                val isFriend = currentFriends.any {
                    it.id == alert.senderId ||
                    (it.friendCode.isNotBlank() && it.friendCode.equals(alert.senderId, ignoreCase = true))
                }
                if (!isFriend && alert.senderId != "system" && !alert.type.contains("KICKED")) {
                    // Non-friends MUST NEVER trigger alert notifications!
                    return@collect
                }

                // 2. Suppress alerts during startup sync (first 15 seconds) so stale broker messages never popup
                if (System.currentTimeMillis() - appStartTime < 15_000L) {
                    return@collect
                }

                // 3. Suppress stale alerts older than 20 seconds
                if (alert.timestamp > 0 && (System.currentTimeMillis() - alert.timestamp > 20_000L)) {
                    return@collect
                }

                _activeAlert.value = alert
                // Auto dismiss banner after 7 seconds without corrupting saved friends
                delay(7000)
                if (_activeAlert.value?.id == alert.id) {
                    _activeAlert.value = null
                }
            }
        }

        // Observe siren state to pop up or dismiss the Security PIN dialog
        viewModelScope.launch {
            SirenHelper.isSirenActive.collect { active ->
                _isSirenPinDialogOpen.value = active
            }
        }

        // Observe incoming friend requests
        viewModelScope.launch {
            realtime.friendRequests.collect { req ->
                val me = currentUser.value
                val currentFriends = prefs.loadFriends()
                val isAlreadyFriend = currentFriends.any {
                    it.id == req.fromUserId || (req.fromFriendCode.isNotBlank() && it.friendCode.equals(req.fromFriendCode, ignoreCase = true))
                }
                if (isAlreadyFriend) {
                    // Already friends: clear broker retained invite and skip
                    realtime.publishRetained("pinpulse/v1/invites/${req.toFriendCode}", "")
                    return@collect
                }
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
                    syncCloudBackup()
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

        // Clean up any stale pending requests for people who are already friends
        val existingFriends = prefs.loadFriends()
        val staleRequests = prefs.loadPendingRequests().filter { req ->
            existingFriends.any { it.id == req.fromUserId || (req.fromFriendCode.isNotBlank() && it.friendCode.equals(req.fromFriendCode, ignoreCase = true)) }
        }
        staleRequests.forEach { prefs.removePendingRequest(it.id) }

        val user = prefs.loadCurrentUser()
        if (user != null) {
            realtime.connect(user)
            val accountTopic = "pinpulse/v1/accounts/${realtime.cleanEmail(user.email)}/backup"
            realtime.subscribe(accountTopic)
            // Resubscribe to existing groups
            prefs.loadGroups().forEach { g ->
                realtime.subscribe("pinpulse/v1/groups/${g.code}/info")
                realtime.subscribe("pinpulse/v1/groups/${g.code}/events")
            }
            // Resubscribe to existing friends
            prefs.loadFriends().forEach { f ->
                realtime.subscribe("pinpulse/v1/user/${f.id}/loc")
                realtime.subscribe("pinpulse/v1/user/${f.id}/status")
            }
            if (user.isSharingLocation) {
                LocationTrackingService.start(app)
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
            // Check if this packet is from a group member
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
                    isSharingLocation = true,
                    lastUpdated = packet.timestamp
                )
                // Update group members map only - NEVER add to friends list!
                _groupMembers.value = _groupMembers.value + (packet.userId to memberUser)
                if (_selectedPerson.value?.id == packet.userId) {
                    _selectedPerson.value = memberUser
                }
            }
        }
    }

    private fun handleRawMessage(topic: String, payload: String) {
        try {
            if (topic.contains("/accounts/") && topic.endsWith("/backup")) {
                // Cloud Account Restore from retained backup!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val userJson = gson.toJson(jsonMap["user"])
                val restoredUser = gson.fromJson(userJson, User::class.java)
                if (restoredUser != null) {
                    val current = prefs.loadCurrentUser()
                    val mergedUser = (current ?: restoredUser).copy(
                        id = restoredUser.id,
                        friendCode = restoredUser.friendCode,
                        displayName = if (current?.displayName?.isNotBlank() == true) current.displayName else restoredUser.displayName,
                        avatarBase64 = current?.avatarBase64 ?: restoredUser.avatarBase64
                    )
                    prefs.saveCurrentUser(mergedUser)
                }

                // Restore friends list
                val friendsListType = object : com.google.gson.reflect.TypeToken<List<User>>() {}.type
                val friendsJson = gson.toJson(jsonMap["friends"])
                val restoredFriends = gson.fromJson<List<User>>(friendsJson, friendsListType)
                if (!restoredFriends.isNullOrEmpty()) {
                    val currentFriends = prefs.loadFriends().toMutableList()
                    restoredFriends.forEach { rf ->
                        if (currentFriends.none { it.id == rf.id }) {
                            currentFriends.add(rf.copy(isSharingLocation = true))
                        }
                    }
                    prefs.saveFriends(currentFriends)
                    restoredFriends.forEach { f ->
                        realtime.subscribe("pinpulse/v1/user/${f.id}/loc")
                    }
                }

                // Restore groups list
                val groupsListType = object : com.google.gson.reflect.TypeToken<List<Group>>() {}.type
                val groupsJson = gson.toJson(jsonMap["groups"])
                val restoredGroups = gson.fromJson<List<Group>>(groupsJson, groupsListType)
                if (!restoredGroups.isNullOrEmpty()) {
                    val currentGroups = prefs.loadGroups().toMutableList()
                    restoredGroups.forEach { rg ->
                        if (currentGroups.none { it.id == rg.id || it.code.equals(rg.code, ignoreCase = true) }) {
                            currentGroups.add(rg)
                        }
                    }
                    prefs.saveGroups(currentGroups)
                    restoredGroups.forEach { g ->
                        realtime.subscribe("pinpulse/v1/groups/${g.code}/info")
                        realtime.subscribe("pinpulse/v1/groups/${g.code}/events")
                        g.memberIds.forEach { mid ->
                            realtime.subscribe("pinpulse/v1/user/$mid/loc")
                        }
                    }
                }
                Log.d("MainViewModel", "Account restored from cloud backup for: ${restoredUser?.email}")
            } else if (payload.contains("\"type\":\"UNFRIEND\"")) {
                // Mutual friend removal: remove this friend on our side too!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val unfriendId = jsonMap["fromUserId"] as? String
                if (!unfriendId.isNullOrEmpty()) {
                    prefs.removeFriend(unfriendId)
                    syncCloudBackup()
                }
            } else if (payload.contains("\"type\":\"FRIEND_ACCEPTED\"")) {
                // Requester receives acceptance from recipient!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val friendJson = gson.toJson(jsonMap["friend"])
                val friend = gson.fromJson(friendJson, User::class.java)
                if (friend != null) {
                    prefs.addOrUpdateFriend(friend)
                    realtime.subscribe("pinpulse/v1/user/${friend.id}/loc")
                    realtime.subscribe("pinpulse/v1/user/${friend.id}/status")
                    syncCloudBackup()
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
                            syncCloudBackup()
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
                        syncCloudBackup()
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
                    syncCloudBackup()
                    _activeAlert.value = AlertMessage(
                        senderId = "system",
                        senderName = "Group Leader",
                        type = "KICKED",
                        message = "You were removed from the group."
                    )
                }
            } else if (topic.endsWith("/status")) {
                try {
                    val jsonMap = gson.fromJson(payload, Map::class.java)
                    val uid = jsonMap["userId"]?.toString() ?: jsonMap["senderId"]?.toString() ?: ""
                    val status = jsonMap["status"]?.toString() ?: ""
                    if (uid.isNotBlank()) {
                        val currentFriends = prefs.loadFriends().toMutableList()
                        val idx = currentFriends.indexOfFirst { it.id == uid }
                        if (idx >= 0) {
                            val f = currentFriends[idx]
                            val isOnline = status.equals("ONLINE", ignoreCase = true)
                            val updated = f.copy(
                                isSharingLocation = if (isOnline) true else f.isSharingLocation,
                                lastUpdated = if (isOnline) System.currentTimeMillis() else f.lastUpdated
                            )
                            currentFriends[idx] = updated
                            prefs.saveFriends(currentFriends)
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error parsing raw message: ${e.message}")
        }
    }

    // --- Authentication & Profile ---

    fun signOut() {
        prefs.clearSession()
    }

    fun syncCloudBackup() {
        val me = prefs.loadCurrentUser() ?: return
        if (me.email.isBlank()) return
        val friends = prefs.loadFriends()
        val groups = prefs.loadGroups()
        val backupMap = mapOf(
            "user" to me,
            "friends" to friends,
            "groups" to groups,
            "timestamp" to System.currentTimeMillis()
        )
        val json = gson.toJson(backupMap)
        realtime.publishAccountBackup(me.email, json)
    }

    fun signInWithGoogleIdentity(email: String, displayName: String, avatarBase64: String?) {
        val existing = prefs.loadCurrentUser()
        val userId = existing?.id ?: prefs.generateDeterministicUserId(email)
        val friendCode = existing?.friendCode ?: prefs.generateDeterministicFriendCode(email, displayName)

        val user = User(
            id = userId,
            email = email,
            displayName = displayName,
            avatarBase64 = avatarBase64 ?: existing?.avatarBase64,
            friendCode = friendCode,
            isSharingLocation = true,
            latitude = existing?.latitude ?: 36.8065,
            longitude = existing?.longitude ?: 10.1815,
            speedKmh = 0f,
            lastUpdated = System.currentTimeMillis()
        )
        prefs.saveCurrentUser(user)
        realtime.connect(user)

        // Subscribe to cloud account backup channel for this email
        val accountTopic = "pinpulse/v1/accounts/${realtime.cleanEmail(email)}/backup"
        realtime.subscribe(accountTopic)

        // Resubscribe to existing groups and friends
        prefs.loadGroups().forEach { g ->
            realtime.subscribe("pinpulse/v1/groups/${g.code}/info")
            realtime.subscribe("pinpulse/v1/groups/${g.code}/events")
        }
        prefs.loadFriends().forEach { f ->
            realtime.subscribe("pinpulse/v1/user/${f.id}/loc")
        }

        // Only sync cloud backup if local store already has friends or groups;
        // on a clean reinstall, wait for the retained cloud backup to restore friends first!
        if (prefs.loadFriends().isNotEmpty() || prefs.loadGroups().isNotEmpty()) {
            syncCloudBackup()
        }

        LocationTrackingService.start(app)
    }

    fun updateMyLocation(lat: Double, lng: Double, speed: Float) {
        val current = prefs.loadCurrentUser() ?: return
        val batteryInfo = LocationTrackingService.getBatteryInfo(app)
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
        ImageHelper.evictFromCache(current.avatarBase64)
        ImageHelper.evictFromCache(base64)
        val updated = current.copy(avatarBase64 = base64)
        prefs.saveCurrentUser(updated)
        syncCloudBackup()

        // Broadcast new avatar
        LocationTrackingService.start(app)
    }

    fun setGroupMemberAvatarByLeader(group: Group, memberId: String, uri: Uri, context: Context) {
        val base64 = compressImageToBase64(uri, context) ?: return
        ImageHelper.evictFromCache(base64)
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
            LocationTrackingService.start(app)
        } else {
            LocationTrackingService.stop(app)
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

    fun ringPersonPhone(targetUser: User) {
        val me = prefs.loadCurrentUser()
        val callerName = me?.displayName ?: "Friend"
        realtime.sendRingCommand(targetUser.id, targetUser.friendCode, callerName)
        _activeAlert.value = AlertMessage(
            senderId = me?.id ?: "",
            senderName = callerName,
            type = "RING_SENT",
            message = "🔊 Siren alert sent to ${targetUser.displayName}'s phone!"
        )
    }

    fun stopPersonPhone(targetUser: User) {
        val me = prefs.loadCurrentUser()
        val callerName = me?.displayName ?: "Friend"
        realtime.sendStopRingCommand(targetUser.id, targetUser.friendCode, callerName)
        _activeAlert.value = AlertMessage(
            senderId = me?.id ?: "",
            senderName = callerName,
            type = "STOP_RING_SENT",
            message = "🛑 Remote stop signal sent to ${targetUser.displayName}'s phone!"
        )
    }

    fun setSelfMarkerMode(mode: String) {
        prefs.setSelfMarkerMode(mode)
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
        syncCloudBackup()
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
        syncCloudBackup()

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

    // --- 1-on-1 Friends & Verification ---

    fun lookupUserByCode(code: String): User? {
        val clean = code.trim().uppercase()
        val noHyphen = clean.replace("-", "")
        if (noHyphen.length != 8) return null

        val map = realtime.registeredUsers.value
        val direct = map[clean] ?: map[noHyphen]
        if (direct != null && direct.friendCode.replace("-", "").equals(noHyphen, ignoreCase = true)) {
            return direct
        }

        val allKnown = (map.values + prefs.loadFriends() + _groupMembers.value.values).distinctBy { it.id }
        return allKnown.firstOrNull { u ->
            u.friendCode.isNotBlank() && u.friendCode.replace("-", "").equals(noHyphen, ignoreCase = true)
        }
    }

    fun lookupUserByEmail(email: String): User? {
        val clean = email.trim().lowercase()
        if (!clean.contains("@") || !clean.substringAfter("@").contains(".")) return null

        val map = realtime.registeredUsers.value
        val emailTag = realtime.cleanEmail(clean)
        val direct = map[clean] ?: map[emailTag]
        if (direct != null && direct.email.equals(clean, ignoreCase = true)) {
            return direct
        }

        val allKnown = (map.values + prefs.loadFriends() + _groupMembers.value.values).distinctBy { it.id }
        return allKnown.firstOrNull { u ->
            u.email.isNotBlank() && u.email.equals(clean, ignoreCase = true)
        }
    }

    fun lookupUser(query: String): User? {
        val clean = query.trim()
        return if (clean.contains("@")) {
            lookupUserByEmail(clean)
        } else {
            lookupUserByCode(clean)
        }
    }

    fun queryUserByCode(code: String) {
        val clean = code.trim().uppercase()
        val noHyphen = clean.replace("-", "")
        if (noHyphen.length >= 8) {
            realtime.subscribe("pinpulse/v1/registry/code/$clean")
            realtime.subscribe("pinpulse/v1/registry/code/$noHyphen")
        }
    }

    fun queryUserByEmail(email: String) {
        val clean = email.trim().lowercase()
        if (clean.contains("@") && clean.substringAfter("@").contains(".")) {
            val emailTag = realtime.cleanEmail(clean)
            realtime.subscribe("pinpulse/v1/registry/email/$emailTag")
        }
    }

    fun queryUserOnDemand(codeOrEmail: String) {
        if (codeOrEmail.contains("@")) {
            queryUserByEmail(codeOrEmail)
        } else {
            queryUserByCode(codeOrEmail)
        }
    }

    fun sendFriendRequest(targetFriendCodeOrEmail: String, isEmail: Boolean = false) {
        val me = prefs.loadCurrentUser() ?: return
        val rawInput = targetFriendCodeOrEmail.trim()
        val actualIsEmail = isEmail || rawInput.contains("@")
        val found = if (actualIsEmail) lookupUserByEmail(rawInput) else lookupUserByCode(rawInput)

        val cleanCode = (found?.friendCode?.ifBlank { null } ?: if (!actualIsEmail) rawInput.uppercase() else "").uppercase()
        val targetUserId = found?.id ?: if (actualIsEmail) prefs.generateDeterministicUserId(rawInput) else cleanCode
        val targetEmail = found?.email?.ifBlank { null } ?: if (actualIsEmail) rawInput.lowercase() else ""

        val req = FriendRequest(
            id = "req_" + UUID.randomUUID().toString().take(8),
            fromUserId = me.id,
            fromUserName = me.displayName,
            fromUserEmail = me.email,
            fromUserAvatar = me.avatarBase64,
            fromFriendCode = me.friendCode,
            toUserId = targetUserId,
            toFriendCode = cleanCode,
            status = "PENDING"
        )
        realtime.sendFriendRequest(req, targetEmail)
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

        // Clear retained invites across all candidate topics
        if (request.toFriendCode.isNotBlank()) {
            realtime.publishRetained("pinpulse/v1/invites/${request.toFriendCode.uppercase()}", "")
        }
        if (request.toUserId.isNotBlank()) {
            realtime.publishRetained("pinpulse/v1/invites/${request.toUserId}", "")
        }
        if (me.friendCode.isNotBlank()) {
            realtime.publishRetained("pinpulse/v1/invites/${me.friendCode.uppercase()}", "")
        }
        if (me.email.isNotBlank()) {
            realtime.publishRetained("pinpulse/v1/invites/${realtime.cleanEmail(me.email)}", "")
        }

        realtime.subscribe("pinpulse/v1/user/${newFriend.id}/loc")
        realtime.subscribe("pinpulse/v1/user/${newFriend.id}/status")
        syncCloudBackup()

        // Broadcast acceptance packet back to requester with QoS 1 so they add us too!
        val payload = mapOf(
            "type" to "FRIEND_ACCEPTED",
            "friend" to me
        )
        val json = gson.toJson(payload)
        realtime.publish("pinpulse/v1/code/${request.fromFriendCode.uppercase()}", json, qosLevel = 1)
        realtime.publish("pinpulse/v1/direct/${request.fromUserId}", json, qosLevel = 1)
        realtime.publishRetained("pinpulse/v1/direct/${request.fromUserId}", json, qosLevel = 1)
    }

    fun declineFriendRequest(requestId: String) {
        val me = prefs.loadCurrentUser()
        val req = prefs.loadPendingRequests().find { it.id == requestId }
        prefs.removePendingRequest(requestId)
        if (req != null) {
            if (req.toFriendCode.isNotBlank()) realtime.publishRetained("pinpulse/v1/invites/${req.toFriendCode.uppercase()}", "")
            if (req.toUserId.isNotBlank()) realtime.publishRetained("pinpulse/v1/invites/${req.toUserId}", "")
        }
        if (me != null) {
            if (me.friendCode.isNotBlank()) realtime.publishRetained("pinpulse/v1/invites/${me.friendCode.uppercase()}", "")
            if (me.email.isNotBlank()) realtime.publishRetained("pinpulse/v1/invites/${realtime.cleanEmail(me.email)}", "")
        }
    }

    fun removeFriendMutually(friendId: String) {
        val me = prefs.loadCurrentUser() ?: return
        val friend = prefs.loadFriends().find { it.id == friendId }
        // Remove locally
        prefs.removeFriend(friendId)
        realtime.unsubscribe("pinpulse/v1/user/$friendId/loc")
        syncCloudBackup()

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

    // --- Siren Security PIN & In-App Updates ---

    val hasSecurityPin: StateFlow<Boolean> = prefs.hasSecurityPinFlow

    fun hasSecurityPin(): Boolean = prefs.hasSecurityPin()

    fun getSecurityPin(): String? = prefs.getSecurityPin()

    fun setSecurityPin(pin: String) = prefs.setSecurityPin(pin)

    fun createSecurityPin(newPin: String): Pair<Boolean, String> {
        val clean = newPin.trim().filter { it.isDigit() }
        if (clean.length !in 4..6) {
            return Pair(false, "PIN must be between 4 and 6 digits.")
        }
        prefs.setSecurityPin(clean)
        return Pair(true, "Security PIN created successfully!")
    }

    fun changeSecurityPin(currentPin: String, newPin: String): Pair<Boolean, String> {
        val existing = prefs.getSecurityPin()
        if (existing != null) {
            if (currentPin.trim() != existing.trim()) {
                return Pair(false, "Incorrect current PIN.")
            }
        }
        val cleanNew = newPin.trim().filter { it.isDigit() }
        if (cleanNew.length !in 4..6) {
            return Pair(false, "New PIN must be between 4 and 6 digits.")
        }
        prefs.setSecurityPin(cleanNew)
        return Pair(true, "Security PIN updated successfully!")
    }

    fun stopSirenDirectly(context: Context) {
        SirenHelper.stop(context)
        _isSirenPinDialogOpen.value = false
    }

    fun showSirenPinDialog() {
        _isSirenPinDialogOpen.value = true
    }

    fun dismissSirenPinDialog() {
        if (!SirenHelper.isCurrentlyPlaying()) {
            _isSirenPinDialogOpen.value = false
        }
    }

    fun verifyPinAndStopSiren(context: Context, enteredPin: String): Boolean {
        val expected = getSecurityPin()
        if (expected.isNullOrBlank()) {
            // User never created a PIN: stop directly without asking for it
            SirenHelper.stop(context)
            _isSirenPinDialogOpen.value = false
            return true
        }
        val success = SirenHelper.verifyPinAndStop(context, enteredPin, expected)
        if (success) {
            _isSirenPinDialogOpen.value = false
        }
        return success
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            UpdateManager.checkForUpdates(app)
        }
    }

    fun refreshUpdateStatus() {
        UpdateManager.refreshDownloadedStatus(app)
    }

    fun downloadAndInstallUpdate(context: Context) {
        viewModelScope.launch {
            UpdateManager.downloadAndInstall(context)
        }
    }
}

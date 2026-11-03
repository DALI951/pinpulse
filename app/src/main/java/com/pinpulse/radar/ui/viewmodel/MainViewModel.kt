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
import com.pinpulse.radar.model.TrailPoint
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
import java.util.Collections
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

    private val _showSubscriptionDialog = MutableStateFlow(false)
    val showSubscriptionDialog: StateFlow<Boolean> = _showSubscriptionDialog.asStateFlow()

    fun openSubscriptionDialog() {
        _showSubscriptionDialog.value = true
    }

    fun closeSubscriptionDialog() {
        _showSubscriptionDialog.value = false
    }

    val updateState: StateFlow<UpdateState> = UpdateManager.updateState

    private val _kickState = MutableStateFlow<KickConfirmationState?>(null)
    val kickState: StateFlow<KickConfirmationState?> = _kickState.asStateFlow()

    private var countdownJob: Job? = null

    private val _groupMembers = MutableStateFlow<Map<String, User>>(emptyMap())
    val groupMembers: StateFlow<Map<String, User>> = _groupMembers.asStateFlow()

    // Live 15-Minute Movement Breadcrumb Trails per userId
    private val _userTrails = MutableStateFlow<Map<String, List<TrailPoint>>>(emptyMap())
    val userTrails: StateFlow<Map<String, List<TrailPoint>>> = _userTrails.asStateFlow()

    private val pendingJoinGroupCodes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // Combined list of users to display on the map according to the current filter
    val visibleUsers: StateFlow<List<User>> = combine(
        currentUser,
        combine(friends, _groupMembers, registeredUsers) { fl, gm, reg -> Triple(fl, gm, reg) },
        groups,
        hiddenFromGeneral,
        currentFilter
    ) { me, triple, groupList, hiddenSet, filter ->
        val friendList = triple.first
        val groupMembersMap = triple.second
        val regMap = triple.third
        val allUsers = mutableListOf<User>()
        if (me != null) allUsers.add(me)

        when (filter) {
            is MapFilter.General -> {
                // All accepted friends and group members, excluding those hidden from General Map
                val groupMemberUsers = groupList.flatMap { g -> g.memberIds }
                    .distinct()
                    .filterNot { it == me?.id }
                    .mapNotNull { mid ->
                        friendList.find { it.id == mid } ?: groupMembersMap[mid] ?: regMap[mid]
                    }
                val combined = (friendList + groupMembersMap.values + groupMemberUsers).distinctBy { it.id }
                val filtered = combined.filterNot { hiddenSet.contains(it.id) }
                allUsers.addAll(filtered)
            }
            is MapFilter.FriendsOnly -> {
                allUsers.addAll(friendList)
            }
            is MapFilter.GroupFilter -> {
                val activeGroup = groupList.find { it.id == filter.group.id } ?: filter.group
                val memberUsers = activeGroup.memberIds
                    .filterNot { it == me?.id }
                    .mapNotNull { mid ->
                        friendList.find { it.id == mid } ?: groupMembersMap[mid] ?: regMap[mid]
                    }
                    .map { member ->
                        // Apply Group Leader custom avatar override if present!
                        val leaderOverride = activeGroup.memberAvatars[member.id]
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

        // Auto-heal any friends corrupted by legacy offline alerts and strictly enforce plan quotas (e.g. Demo = 1 friend max)
        val savedFriends = prefs.loadFriends()
        if (savedFriends.any { !it.isSharingLocation }) {
            val repaired = savedFriends.map { it.copy(isSharingLocation = true) }
            prefs.saveFriends(repaired)
        }
        enforcePlanQuotasAndTrimExcess()

        // Observe realtime location packets
        viewModelScope.launch {
            realtime.locationUpdates.collect { rawPacket ->
                try {
                    handleIncomingLocationPacket(rawPacket.sanitize())
                } catch (t: Throwable) {
                    Log.e("MainViewModel", "Error handling location packet: ${t.message}")
                }
            }
        }

        // Record our own 15-minute movement breadcrumb trail & enforce plan quotas whenever currentUser updates
        viewModelScope.launch {
            currentUser.collect { me ->
                try {
                    if (me != null) {
                        enforcePlanQuotasAndTrimExcess()
                        if (me.latitude != 0.0 || me.longitude != 0.0) {
                            recordTrailPoint(me.id, me.latitude, me.longitude, me.speedKmh, me.lastUpdated)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("MainViewModel", "Error recording self trail: ${t.message}")
                }
            }
        }

        val appStartTime = System.currentTimeMillis()
        // Observe incoming alerts (e.g. emergency alert or location toggle)
        viewModelScope.launch {
            realtime.alerts.collect { rawAlert ->
                try {
                    val alert = rawAlert.sanitize()
                    val me = prefs.loadCurrentUser()
                    if (alert.senderId == me?.id) return@collect

                    // 1. Only show alerts for confirmed friends, group members (for SOS), or system/group kick events
                    val currentFriends = prefs.loadFriends()
                    val isFriend = currentFriends.any {
                        it.id == alert.senderId ||
                        (it.friendCode.isNotBlank() && it.friendCode.equals(alert.senderId, ignoreCase = true))
                    }
                    val isGroupMember = prefs.loadGroups().any { it.memberIds.contains(alert.senderId) }
                    val isSos = alert.type.contains("SOS", ignoreCase = true)
                    val isSystemOrKick = alert.senderId == "system" || alert.type.contains("KICKED") || alert.type.contains("SIREN") || alert.type.contains("LIMIT")
                    if (!isFriend && !(isGroupMember && isSos) && !isSystemOrKick) {
                        // Non-friends MUST NEVER trigger alert notifications!
                        return@collect
                    }

                    // 2. Suppress non-critical alerts during startup sync (first 15 seconds) so stale broker messages never popup
                    if (!isSystemOrKick && !isSos && (System.currentTimeMillis() - appStartTime < 15_000L)) {
                        return@collect
                    }

                    // 3. Suppress stale alerts older than 20 seconds (or 60s for SOS)
                    val maxAgeMs = if (isSos) 60_000L else 20_000L
                    if (alert.timestamp > 0 && (System.currentTimeMillis() - alert.timestamp > maxAgeMs)) {
                        return@collect
                    }

                    _activeAlert.value = alert
                    // Auto dismiss banner after 15 seconds for SOS or 7 seconds for normal alerts
                    delay(if (isSos) 15000L else 7000L)
                    if (_activeAlert.value?.id == alert.id) {
                        _activeAlert.value = null
                    }
                } catch (t: Throwable) {
                    Log.e("MainViewModel", "Error handling alert: ${t.message}")
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
            realtime.friendRequests.collect { rawReq ->
                try {
                    val req = rawReq.sanitize()
                    val me = currentUser.value ?: prefs.loadCurrentUser()
                    val currentFriends = prefs.loadFriends()
                    val isAlreadyFriend = currentFriends.any {
                        it.id == req.fromUserId || (req.fromFriendCode.isNotBlank() && it.friendCode.equals(req.fromFriendCode, ignoreCase = true))
                    }
                    if (isAlreadyFriend) {
                        // Already friends: clear broker retained invite and skip
                        if (req.toFriendCode.isNotBlank()) {
                            realtime.publishRetained("pinpulse/v1/invites/${req.toFriendCode}", "")
                        }
                        return@collect
                    }
                    if (me != null && (req.toFriendCode.equals(me.friendCode, ignoreCase = true) || req.toUserId == me.id)) {
                        prefs.addPendingRequest(req)
                    }
                } catch (t: Throwable) {
                    Log.e("MainViewModel", "Error handling friend request: ${t.message}")
                }
            }
        }

        // Observe incoming group updates
        viewModelScope.launch {
            realtime.groupUpdates.collect { rawGroup ->
                try {
                    val group = rawGroup.sanitize()
                    val myId = currentUser.value?.id ?: prefs.loadCurrentUser()?.id ?: return@collect
                    val codeUpper = group.code.trim().uppercase()

                    if (pendingJoinGroupCodes.remove(codeUpper)) {
                        // User explicitly requested to join this group by code!
                        val updatedMembers = (group.memberIds + myId).distinct()
                        val joinedGroup = group.copy(memberIds = updatedMembers)
                        prefs.addOrUpdateGroup(joinedGroup)
                        realtime.publishGroupInfo(joinedGroup)
                        syncCloudBackup()
                        subscribeToGroupMembers(joinedGroup, myId)
                    } else if (group.memberIds.contains(myId)) {
                        prefs.addOrUpdateGroup(group)
                        syncCloudBackup()
                        subscribeToGroupMembers(group, myId)
                    } else {
                        // If we previously had this group locally, and the new memberIds list no longer contains us, we were removed!
                        val existing = prefs.loadGroups().find { it.id == group.id || it.code.equals(group.code, ignoreCase = true) }
                        if (existing != null && existing.creatorId != myId) {
                            prefs.removeGroup(existing.id)
                            syncCloudBackup()
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("MainViewModel", "Error handling group update: ${t.message}")
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

        val savedUser = prefs.loadCurrentUser()
        if (savedUser != null) {
            val activeUser = if (!savedUser.isSharingLocation) {
                val healed = savedUser.copy(isSharingLocation = true)
                prefs.saveCurrentUser(healed)
                healed
            } else {
                savedUser
            }
            realtime.connect(activeUser)
            val accountTopic = "pinpulse/v1/accounts/${realtime.cleanEmail(activeUser.email)}/backup"
            realtime.subscribe(accountTopic)
            realtime.subscribe("pinpulse/v1/admin/users/${activeUser.id}")
            if (activeUser.friendCode.isNotBlank()) {
                realtime.subscribe("pinpulse/v1/admin/users/${activeUser.friendCode.uppercase()}")
            }
            if (activeUser.email.isNotBlank()) {
                realtime.subscribe("pinpulse/v1/admin/users/${realtime.cleanEmail(activeUser.email)}")
            }
            // Resubscribe to existing groups
            prefs.loadGroups().forEach { g ->
                realtime.subscribe("pinpulse/v1/groups/${g.code}/info")
                realtime.subscribe("pinpulse/v1/groups/${g.code}/events")
                subscribeToGroupMembers(g, activeUser.id)
            }
            // Resubscribe to existing friends
            prefs.loadFriends().forEach { f ->
                realtime.subscribe("pinpulse/v1/user/${f.id}/loc")
                realtime.subscribe("pinpulse/v1/user/${f.id}/status")
                realtime.subscribe("pinpulse/v1/registry/user/${f.id}")
            }
            LocationTrackingService.start(app)
        }
    }

    private fun subscribeToGroupMembers(group: Group, myId: String) {
        group.memberIds.filterNot { it == myId }.forEach { mid ->
            realtime.subscribe("pinpulse/v1/user/$mid/loc")
            realtime.subscribe("pinpulse/v1/user/$mid/status")
            realtime.subscribe("pinpulse/v1/registry/user/$mid")
        }
    }

    fun resolveGroupMember(memberId: String): User {
        currentUser.value?.let { if (it.id == memberId) return it }
        friends.value.find { it.id == memberId }?.let { return it }
        _groupMembers.value[memberId]?.let { return it }
        registeredUsers.value[memberId]?.let { return it }
        return User(id = memberId, email = "", displayName = "Member", avatarBase64 = null, friendCode = "")
    }

    private fun recordTrailPoint(
        userId: String,
        lat: Double,
        lng: Double,
        speedKmh: Float,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (userId.isBlank() || (lat == 0.0 && lng == 0.0)) return
        val now = System.currentTimeMillis()
        val cutoff = now - (15 * 60 * 1000L) // Last 15 minutes window
        val effectiveTime = if (timestamp in cutoff..(now + 60_000L)) timestamp else now

        val currentMap = _userTrails.value
        val existingList = (currentMap[userId] ?: emptyList()).filter { it.timestamp >= cutoff }

        val updatedList = if (existingList.isEmpty()) {
            listOf(TrailPoint(lat, lng, speedKmh, effectiveTime))
        } else {
            val last = existingList.last()
            val distMeters = calculateDistanceMeters(last.latitude, last.longitude, lat, lng)
            when {
                distMeters > 50_000.0 -> {
                    // Reset trail if coordinates jumped >50km (e.g., initial GPS lock from default city)
                    listOf(TrailPoint(lat, lng, speedKmh, effectiveTime))
                }
                distMeters >= 6.0 -> {
                    (existingList + TrailPoint(lat, lng, speedKmh, effectiveTime)).takeLast(120)
                }
                else -> {
                    existingList
                }
            }
        }
        _userTrails.value = currentMap + (userId to updatedList)
    }

    private fun handleIncomingLocationPacket(packet: LocationPacket) {
        recordTrailPoint(packet.userId, packet.latitude, packet.longitude, packet.speedKmh, packet.timestamp)

        val currentFriends = prefs.loadFriends().toMutableList()
        val index = currentFriends.indexOfFirst { it.id == packet.userId }
        if (index >= 0) {
            val existing = currentFriends[index]
            val hasNewCoords = packet.latitude != 0.0 || packet.longitude != 0.0
            val updated = existing.copy(
                displayName = packet.displayName.ifBlank { existing.displayName },
                email = packet.email.ifBlank { existing.email },
                friendCode = packet.friendCode.ifBlank { existing.friendCode },
                avatarBase64 = packet.avatarBase64 ?: existing.avatarBase64,
                statusMessage = packet.statusMessage ?: existing.statusMessage,
                latitude = if (hasNewCoords) packet.latitude else existing.latitude,
                longitude = if (hasNewCoords) packet.longitude else existing.longitude,
                speedKmh = packet.speedKmh,
                batteryPercent = packet.batteryPercent,
                isCharging = packet.isCharging,
                isSharingLocation = packet.isSharingLocation,
                isGpsEnabled = packet.isGpsEnabled,
                lastUpdated = packet.timestamp,
                subscriptionPlan = packet.subscriptionPlan.ifBlank { existing.subscriptionPlan },
                accountStatus = packet.accountStatus.ifBlank { existing.accountStatus }
            )
            currentFriends[index] = updated
            prefs.saveFriends(currentFriends)

            // If selected person is this user, update sheet
            if (_selectedPerson.value?.id == packet.userId) {
                _selectedPerson.value = updated
            }
        }

        // Also check if this packet is from a group member
        val allGroups = prefs.loadGroups()
        val isInAnyGroup = allGroups.any { it.memberIds.contains(packet.userId) }
        if (isInAnyGroup) {
            val prev = _groupMembers.value[packet.userId]
            val hasNewCoords = packet.latitude != 0.0 || packet.longitude != 0.0
            val memberUser = User(
                id = packet.userId,
                displayName = packet.displayName.ifBlank { prev?.displayName ?: "Member" },
                email = packet.email.ifBlank { prev?.email ?: "" },
                avatarBase64 = packet.avatarBase64 ?: prev?.avatarBase64,
                friendCode = packet.friendCode.ifBlank { prev?.friendCode ?: "" },
                statusMessage = packet.statusMessage ?: prev?.statusMessage,
                latitude = if (hasNewCoords) packet.latitude else (prev?.latitude ?: 0.0),
                longitude = if (hasNewCoords) packet.longitude else (prev?.longitude ?: 0.0),
                speedKmh = packet.speedKmh,
                batteryPercent = packet.batteryPercent,
                isCharging = packet.isCharging,
                isSharingLocation = packet.isSharingLocation,
                isGpsEnabled = packet.isGpsEnabled,
                lastUpdated = packet.timestamp,
                subscriptionPlan = packet.subscriptionPlan.ifBlank { prev?.subscriptionPlan ?: "DEMO" },
                accountStatus = packet.accountStatus.ifBlank { prev?.accountStatus ?: "ACTIVE" }
            )
            // Update group members map only - NEVER add to friends list!
            _groupMembers.value = _groupMembers.value + (packet.userId to memberUser)
            if (_selectedPerson.value?.id == packet.userId && index < 0) {
                _selectedPerson.value = memberUser
            }
        }
    }

    private fun handleRawMessage(topic: String, payload: String) {
        try {
            if (topic.contains("/accounts/") && topic.endsWith("/backup")) {
                // Cloud Account Restore from retained backup!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val userJson = gson.toJson(jsonMap["user"])
                val restoredUser = gson.fromJson(userJson, User::class.java)?.sanitize()
                if (restoredUser != null && restoredUser.id.isNotBlank()) {
                    val current = prefs.loadCurrentUser()
                    val mergedUser = (current ?: restoredUser).copy(
                        id = restoredUser.id,
                        friendCode = restoredUser.friendCode,
                        displayName = if (current?.displayName?.isNotBlank() == true) current.displayName else restoredUser.displayName,
                        avatarBase64 = current?.avatarBase64 ?: restoredUser.avatarBase64,
                        isSharingLocation = true
                    ).sanitize()
                    prefs.saveCurrentUser(mergedUser)
                }

                val maxF = (prefs.loadCurrentUser()?.maxFriends ?: 1).coerceAtLeast(0)
                val maxG = (prefs.loadCurrentUser()?.maxGroups ?: 0).coerceAtLeast(0)

                // Restore friends list (strictly capped to maxFriends!)
                val friendsListType = object : com.google.gson.reflect.TypeToken<List<User>>() {}.type
                val friendsJson = gson.toJson(jsonMap["friends"])
                val restoredFriends = gson.fromJson<List<User>>(friendsJson, friendsListType)?.mapNotNull { it?.sanitize() }
                if (!restoredFriends.isNullOrEmpty()) {
                    val currentFriends = prefs.loadFriends().toMutableList()
                    restoredFriends.forEach { rf ->
                        if (rf.id.isNotBlank() && currentFriends.none { it.id == rf.id } && currentFriends.size < maxF) {
                            currentFriends.add(rf.copy(isSharingLocation = true))
                        }
                    }
                    val cappedFriends = currentFriends.take(maxF)
                    prefs.saveFriends(cappedFriends)
                    cappedFriends.forEach { f ->
                        realtime.subscribe("pinpulse/v1/user/${f.id}/loc")
                        realtime.subscribe("pinpulse/v1/user/${f.id}/status")
                        realtime.subscribe("pinpulse/v1/registry/user/${f.id}")
                    }
                }

                // Restore groups list (strictly capped to maxGroups!)
                val groupsListType = object : com.google.gson.reflect.TypeToken<List<Group>>() {}.type
                val groupsJson = gson.toJson(jsonMap["groups"])
                val restoredGroups = gson.fromJson<List<Group>>(groupsJson, groupsListType)?.mapNotNull { it?.sanitize() }
                if (!restoredGroups.isNullOrEmpty() && maxG > 0) {
                    val currentGroups = prefs.loadGroups().toMutableList()
                    val myId = prefs.loadCurrentUser()?.id ?: ""
                    restoredGroups.forEach { rg ->
                        if (rg.id.isNotBlank() && currentGroups.none { it.id == rg.id || it.code.equals(rg.code, ignoreCase = true) } && currentGroups.size < maxG) {
                            currentGroups.add(rg)
                        }
                    }
                    val cappedGroups = currentGroups.take(maxG)
                    prefs.saveGroups(cappedGroups)
                    cappedGroups.forEach { g ->
                        realtime.subscribe("pinpulse/v1/groups/${g.code}/info")
                        realtime.subscribe("pinpulse/v1/groups/${g.code}/events")
                        subscribeToGroupMembers(g, myId)
                    }
                }
                enforcePlanQuotasAndTrimExcess()
                Log.d("MainViewModel", "Account restored from cloud backup for: ${restoredUser?.email}")
            } else if (payload.contains("\"type\":\"UNFRIEND\"")) {
                // Mutual friend removal: remove this friend on our side too!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val unfriendId = jsonMap["fromUserId"] as? String
                if (!unfriendId.isNullOrEmpty()) {
                    prefs.removeFriend(unfriendId)
                    syncCloudBackup()
                    if (_selectedPerson.value?.id == unfriendId) {
                        _selectedPerson.value = null
                    }
                }
            } else if (payload.contains("\"type\":\"FRIEND_ACCEPTED\"")) {
                // Requester receives acceptance from recipient!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val friendJson = gson.toJson(jsonMap["friend"])
                val friend = gson.fromJson(friendJson, User::class.java)?.sanitize()
                if (friend != null && friend.id.isNotBlank()) {
                    val me = prefs.loadCurrentUser()
                    val currentFriends = prefs.loadFriends()
                    val maxAllowed = me?.maxFriends ?: 1
                    val isAlready = currentFriends.any { it.id == friend.id }
                    if (isAlready || currentFriends.size < maxAllowed) {
                        prefs.addOrUpdateFriend(friend.copy(isSharingLocation = true))
                        realtime.subscribe("pinpulse/v1/user/${friend.id}/loc")
                        realtime.subscribe("pinpulse/v1/user/${friend.id}/status")
                        realtime.subscribe("pinpulse/v1/registry/user/${friend.id}")
                        syncCloudBackup()
                    } else {
                        // We already hit our limit before they accepted -> notify them and do not exceed limit
                        if (me != null) {
                            val unfriendJson = gson.toJson(mapOf("type" to "UNFRIEND", "fromUserId" to me.id))
                            realtime.publish("pinpulse/v1/direct/${friend.id}", unfriendJson, qosLevel = 1)
                        }
                    }
                }
            } else if (payload.contains("\"type\":\"JOIN_GROUP\"")) {
                // Group members & Leader receive join event from a new member!
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val groupCode = (jsonMap["groupCode"] as? String)?.uppercase()
                val userJson = gson.toJson(jsonMap["user"])
                val joiningUser = gson.fromJson(userJson, User::class.java)?.sanitize()
                if (groupCode != null && joiningUser != null && joiningUser.id.isNotBlank()) {
                    realtime.registerKnownUser(joiningUser)
                    _groupMembers.value = _groupMembers.value + (joiningUser.id to joiningUser)

                    val group = prefs.loadGroups().find { it.code.equals(groupCode, ignoreCase = true) }
                    val me = prefs.loadCurrentUser()
                    if (group != null) {
                        val updatedMembers = (group.memberIds + joiningUser.id).distinct()
                        val updatedGroup = group.copy(memberIds = updatedMembers)
                        prefs.addOrUpdateGroup(updatedGroup)
                        realtime.subscribe("pinpulse/v1/user/${joiningUser.id}/loc")
                        realtime.subscribe("pinpulse/v1/user/${joiningUser.id}/status")
                        realtime.subscribe("pinpulse/v1/registry/user/${joiningUser.id}")
                        if (group.creatorId == me?.id) {
                            realtime.publishGroupInfo(updatedGroup)
                        }
                        syncCloudBackup()
                    }
                }
            } else if (payload.contains("\"type\":\"AVATAR_OVERRIDE\"")) {
                // Group Leader avatar override
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val groupId = jsonMap["groupId"] as? String
                val groupCode = jsonMap["groupCode"] as? String
                val memberId = jsonMap["memberId"] as? String
                val newAvatar = jsonMap["avatarBase64"] as? String
                if (memberId != null && newAvatar != null) {
                    val group = prefs.loadGroups().find {
                        (groupId != null && it.id == groupId) || (groupCode != null && it.code.equals(groupCode, ignoreCase = true))
                    }
                    if (group != null) {
                        ImageHelper.evictFromCache(newAvatar)
                        val updatedAvatars = group.memberAvatars.toMutableMap()
                        updatedAvatars[memberId] = newAvatar
                        val updatedGroup = group.copy(memberAvatars = updatedAvatars)
                        prefs.addOrUpdateGroup(updatedGroup)
                        val currentFilterVal = _currentFilter.value
                        if (currentFilterVal is MapFilter.GroupFilter && currentFilterVal.group.id == group.id) {
                            _currentFilter.value = MapFilter.GroupFilter(updatedGroup)
                        }
                        syncCloudBackup()
                    }
                }
            } else if (payload.contains("\"type\":\"MEMBER_KICKED\"")) {
                val jsonMap = gson.fromJson(payload, Map::class.java)
                val groupId = jsonMap["groupId"] as? String
                val groupCode = jsonMap["groupCode"] as? String
                val memberId = jsonMap["memberId"] as? String
                val myId = prefs.loadCurrentUser()?.id
                if (memberId == myId) {
                    val targetGroup = prefs.loadGroups().find {
                        (groupId != null && it.id == groupId) || (groupCode != null && it.code.equals(groupCode, ignoreCase = true))
                    }
                    if (targetGroup != null) {
                        prefs.removeGroup(targetGroup.id)
                        realtime.unsubscribe("pinpulse/v1/groups/${targetGroup.code}/info")
                        realtime.unsubscribe("pinpulse/v1/groups/${targetGroup.code}/events")
                        val currentFilterVal = _currentFilter.value
                        if (currentFilterVal is MapFilter.GroupFilter && currentFilterVal.group.id == targetGroup.id) {
                            _currentFilter.value = MapFilter.General
                        }
                        syncCloudBackup()
                    } else if (groupId != null) {
                        prefs.removeGroup(groupId)
                        syncCloudBackup()
                    }
                    _activeAlert.value = AlertMessage(
                        senderId = "system",
                        senderName = "Group Leader",
                        type = "KICKED",
                        message = "You were removed from the group${if (targetGroup != null) " \"${targetGroup.name}\"" else ""}."
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
        LocationTrackingService.stop(app)
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
        val now = System.currentTimeMillis()
        val startAt = if (existing != null && existing.subscriptionStartAt > 1_000_000_000_000L) existing.subscriptionStartAt else now
        val expiresAt = if (existing != null && existing.subscriptionExpiresAt > 1_000_000_000_000L) {
            existing.subscriptionExpiresAt
        } else {
            startAt + 7L * 24L * 3600L * 1000L // 7-Day Free Trial for Demo Pack
        }

        val user = User(
            id = userId,
            email = email,
            displayName = displayName,
            avatarBase64 = avatarBase64 ?: existing?.avatarBase64,
            friendCode = friendCode,
            statusMessage = existing?.statusMessage,
            isSharingLocation = true,
            isGpsEnabled = isSystemGpsEnabled(),
            latitude = existing?.latitude ?: 0.0,
            longitude = existing?.longitude ?: 0.0,
            speedKmh = 0f,
            lastUpdated = now,
            subscriptionPlan = existing?.subscriptionPlan ?: "DEMO",
            accountStatus = existing?.accountStatus ?: "ACTIVE",
            subscriptionStartAt = startAt,
            subscriptionExpiresAt = expiresAt,
            maxFriends = existing?.maxFriends ?: 1,
            maxGroups = existing?.maxGroups ?: 0,
            rateLimitSec = existing?.rateLimitSec ?: 3,
            monthlyRateUsd = existing?.monthlyRateUsd ?: 0.0,
            friendsCount = prefs.loadFriends().size,
            groupsCount = prefs.loadGroups().size
        ).sanitize()
        prefs.saveCurrentUser(user)
        realtime.connect(user)

        // Subscribe to cloud account backup and admin governance channels for this user
        val accountTopic = "pinpulse/v1/accounts/${realtime.cleanEmail(email)}/backup"
        realtime.subscribe(accountTopic)
        realtime.subscribe("pinpulse/v1/admin/users/$userId")
        realtime.subscribe("pinpulse/v1/admin/users/${friendCode.uppercase()}")
        realtime.subscribe("pinpulse/v1/admin/users/${realtime.cleanEmail(email)}")

        // Resubscribe to existing groups and friends
        prefs.loadGroups().forEach { g ->
            realtime.subscribe("pinpulse/v1/groups/${g.code}/info")
            realtime.subscribe("pinpulse/v1/groups/${g.code}/events")
            subscribeToGroupMembers(g, user.id)
        }
        prefs.loadFriends().forEach { f ->
            realtime.subscribe("pinpulse/v1/user/${f.id}/loc")
            realtime.subscribe("pinpulse/v1/user/${f.id}/status")
            realtime.subscribe("pinpulse/v1/registry/user/${f.id}")
        }

        // Only sync cloud backup if local store already has friends or groups;
        // on a clean reinstall, wait for the retained cloud backup to restore friends first!
        if (prefs.loadFriends().isNotEmpty() || prefs.loadGroups().isNotEmpty()) {
            syncCloudBackup()
        }

        LocationTrackingService.start(app)
    }

    fun isSystemGpsEnabled(): Boolean {
        return try {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return false
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    fun updateMyLocation(lat: Double, lng: Double, speed: Float) {
        val current = prefs.loadCurrentUser() ?: return
        val batteryInfo = LocationTrackingService.getBatteryInfo(app)
        val gpsOn = isSystemGpsEnabled()
        val now = System.currentTimeMillis()
        val friendsCount = prefs.loadFriends().size
        val groupsCount = prefs.loadGroups().size
        val updated = current.copy(
            latitude = lat,
            longitude = lng,
            speedKmh = (speed * 3.6f).coerceAtLeast(0f),
            batteryPercent = batteryInfo.first,
            isCharging = batteryInfo.second,
            isSharingLocation = true,
            isGpsEnabled = gpsOn,
            friendsCount = friendsCount,
            groupsCount = groupsCount,
            lastUpdated = now
        )
        prefs.saveCurrentUser(updated)

        val packet = LocationPacket(
            userId = updated.id,
            displayName = updated.displayName,
            email = updated.email,
            friendCode = updated.friendCode,
            avatarBase64 = updated.avatarBase64,
            statusMessage = updated.statusMessage,
            latitude = lat,
            longitude = lng,
            speedKmh = updated.speedKmh,
            batteryPercent = batteryInfo.first,
            isCharging = batteryInfo.second,
            isSharingLocation = true,
            isGpsEnabled = gpsOn,
            timestamp = now,
            subscriptionPlan = updated.subscriptionPlan,
            accountStatus = updated.accountStatus,
            subscriptionExpiresAt = updated.effectiveExpiresAt,
            maxFriends = updated.maxFriends,
            maxGroups = updated.maxGroups,
            rateLimitSec = updated.rateLimitSec,
            monthlyRateUsd = updated.monthlyRateUsd,
            friendsCount = friendsCount,
            groupsCount = groupsCount
        )
        realtime.publishLocation(packet, force = true)
    }

    fun updateMyStatusMessage(status: String) {
        val current = prefs.loadCurrentUser() ?: return
        val cleanStatus = status.trim().take(28)
        val now = System.currentTimeMillis()
        val batteryInfo = LocationTrackingService.getBatteryInfo(app)
        val gpsOn = isSystemGpsEnabled()
        val friendsCount = prefs.loadFriends().size
        val groupsCount = prefs.loadGroups().size
        val updated = current.copy(
            statusMessage = cleanStatus,
            batteryPercent = batteryInfo.first,
            isCharging = batteryInfo.second,
            isGpsEnabled = gpsOn,
            friendsCount = friendsCount,
            groupsCount = groupsCount,
            lastUpdated = now
        )
        prefs.saveCurrentUser(updated)
        if (_selectedPerson.value?.id == updated.id) {
            _selectedPerson.value = updated
        }
        realtime.publishUserRegistry(updated)
        syncCloudBackup()

        val packet = LocationPacket(
            userId = updated.id,
            displayName = updated.displayName,
            email = updated.email,
            friendCode = updated.friendCode,
            avatarBase64 = updated.avatarBase64,
            statusMessage = cleanStatus,
            latitude = updated.latitude,
            longitude = updated.longitude,
            speedKmh = updated.speedKmh,
            batteryPercent = batteryInfo.first,
            isCharging = batteryInfo.second,
            isSharingLocation = true,
            isGpsEnabled = gpsOn,
            timestamp = now,
            subscriptionPlan = updated.subscriptionPlan,
            accountStatus = updated.accountStatus,
            subscriptionExpiresAt = updated.effectiveExpiresAt,
            maxFriends = updated.maxFriends,
            maxGroups = updated.maxGroups,
            rateLimitSec = updated.rateLimitSec,
            monthlyRateUsd = updated.monthlyRateUsd,
            friendsCount = friendsCount,
            groupsCount = groupsCount
        )
        realtime.publishLocation(packet, force = true)
        val statusPayload = gson.toJson(mapOf(
            "type" to "STATUS_MESSAGE_CHANGED",
            "userId" to updated.id,
            "statusMessage" to (cleanStatus ?: ""),
            "isGpsEnabled" to gpsOn,
            "timestamp" to now
        ))
        realtime.publishRetained("pinpulse/v1/user/${updated.id}/status", statusPayload, qosLevel = 1)
    }

    fun updatePersonalAvatar(uri: Uri, context: Context) {
        val base64 = compressImageToBase64(uri, context) ?: return
        val current = prefs.loadCurrentUser() ?: return
        ImageHelper.evictFromCache(current.avatarBase64)
        ImageHelper.evictFromCache(base64)
        val updated = current.copy(avatarBase64 = base64, lastUpdated = System.currentTimeMillis())
        prefs.saveCurrentUser(updated)
        realtime.publishUserRegistry(updated)
        syncCloudBackup()

        // Broadcast new avatar immediately
        LocationTrackingService.start(app)
    }

    fun setGroupMemberAvatarByLeader(group: Group, memberId: String, uri: Uri, context: Context) {
        val base64 = compressImageToBase64(uri, context) ?: return
        ImageHelper.evictFromCache(base64)
        val updatedMap = group.memberAvatars.toMutableMap()
        updatedMap[memberId] = base64
        val updatedGroup = group.copy(memberAvatars = updatedMap)
        prefs.addOrUpdateGroup(updatedGroup)

        // Update current filter if viewing this group
        val currentFilterVal = _currentFilter.value
        if (currentFilterVal is MapFilter.GroupFilter && currentFilterVal.group.id == group.id) {
            _currentFilter.value = MapFilter.GroupFilter(updatedGroup)
            if (_selectedPerson.value?.id == memberId) {
                _selectedPerson.value = _selectedPerson.value?.copy(avatarBase64 = base64)
            }
        }

        // Broadcast retained group info and immediate event to all members
        realtime.publishGroupInfo(updatedGroup)
        val overrideEvent = mapOf(
            "type" to "AVATAR_OVERRIDE",
            "groupId" to group.id,
            "groupCode" to group.code,
            "memberId" to memberId,
            "avatarBase64" to base64
        )
        realtime.publish("pinpulse/v1/groups/${group.code}/events", gson.toJson(overrideEvent), qosLevel = 1)
        syncCloudBackup()
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
            ?: _groupMembers.value[userId]
            ?: registeredUsers.value[userId]
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

    /**
     * 🆘 1-Tap SOS Emergency Broadcast Button:
     * Alerts ALL friends, ALL group members, and the Fleet Command Console simultaneously
     * with exact GPS coordinates, battery level, and a direct Google Maps navigation link.
     */
    fun triggerSosEmergencyBroadcast() {
        val me = prefs.loadCurrentUser() ?: return
        val batteryInfo = LocationTrackingService.getBatteryInfo(app)
        val lat = me.latitude
        val lng = me.longitude
        val mapsLink = if (lat != 0.0 || lng != 0.0) {
            "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng"
        } else {
            "Waiting for GPS fix"
        }

        val sosMessage = "🆘 SOS EMERGENCY from ${me.displayName}! Battery: ${batteryInfo.first}%${if (batteryInfo.second) " ⚡" else ""} • Coords: ${String.format(java.util.Locale.US, "%.5f, %.5f", lat, lng)} • Maps: $mapsLink"

        val sosAlert = AlertMessage(
            id = "sos_" + System.currentTimeMillis(),
            senderId = me.id,
            senderName = me.displayName,
            type = "SOS_EMERGENCY",
            message = sosMessage,
            timestamp = System.currentTimeMillis()
        )
        val sosJson = gson.toJson(sosAlert)

        // 1. Update our floating radar status bubble to SOS EMERGENCY
        updateMyStatusMessage("🆘 SOS EMERGENCY!")

        // 2. Broadcast to all accepted friends + trigger Emergency Siren, Vibration & System Notification!
        prefs.loadFriends().forEach { f ->
            if (f.id.isNotBlank()) {
                realtime.publish("pinpulse/v1/alerts/${f.id}", sosJson, qosLevel = 1)
                realtime.sendRingCommand(f.id, f.friendCode, "🆘 SOS EMERGENCY: ${me.displayName}")
            }
            if (f.friendCode.isNotBlank()) {
                realtime.publish("pinpulse/v1/alerts/${f.friendCode.uppercase()}", sosJson, qosLevel = 1)
            }
        }

        // 3. Broadcast to all group members & group channels + trigger Emergency Siren!
        val allGroupMemberIds = prefs.loadGroups().flatMap { it.memberIds }.distinct().filterNot { it == me.id }
        allGroupMemberIds.forEach { memberId ->
            if (memberId.isNotBlank()) {
                realtime.publish("pinpulse/v1/alerts/$memberId", sosJson, qosLevel = 1)
                realtime.sendRingCommand(memberId, "", "🆘 SOS EMERGENCY: ${me.displayName}")
            }
        }

        // 4. Broadcast to global fleet emergency alert channel (for Web Admin Console)
        realtime.publish("pinpulse/v1/alerts/broadcast", sosJson, qosLevel = 1)

        // 5. Show local confirmation banner
        _activeAlert.value = AlertMessage(
            senderId = "system",
            senderName = "SOS Dispatched",
            type = "SOS_SENT",
            message = "🆘 Emergency SOS Broadcast + Loud Siren sent to all friends & groups with your coordinates and Google Maps link!"
        )
    }

    // --- Subscription Plan Quotas & Enforcement ---

    /**
     * Strictly enforces the current user's subscription quotas (e.g., Demo = 1 Friend, 0 Groups).
     * If the user has more friends or groups than their current plan allows (e.g. from before or after downgrade),
     * excess friends and groups are automatically removed and `friendsCount` / `groupsCount` are synced to the registry.
     */
    fun enforcePlanQuotasAndTrimExcess() {
        val me = prefs.loadCurrentUser() ?: return
        val currentFriends = prefs.loadFriends()
        val currentGroups = prefs.loadGroups()
        var trimmed = false

        val maxF = me.maxFriends.coerceAtLeast(0)
        if (currentFriends.size > maxF) {
            val keptFriends = currentFriends.take(maxF)
            val removedFriends = currentFriends.drop(maxF)
            prefs.saveFriends(keptFriends)
            trimmed = true

            // Notify removed friends so they also remove us from their list
            removedFriends.forEach { rf ->
                realtime.unsubscribe("pinpulse/v1/user/${rf.id}/loc")
                val unfriendJson = gson.toJson(mapOf("type" to "UNFRIEND", "fromUserId" to me.id))
                if (rf.friendCode.isNotBlank()) {
                    realtime.publish("pinpulse/v1/code/${rf.friendCode}", unfriendJson, qosLevel = 1)
                }
                if (rf.id.isNotBlank()) {
                    realtime.publish("pinpulse/v1/direct/${rf.id}", unfriendJson, qosLevel = 1)
                }
            }
        }

        val maxG = me.maxGroups.coerceAtLeast(0)
        if (currentGroups.size > maxG) {
            val keptGroups = currentGroups.take(maxG)
            val removedGroups = currentGroups.drop(maxG)
            prefs.saveGroups(keptGroups)
            trimmed = true

            removedGroups.forEach { rg ->
                realtime.unsubscribe("pinpulse/v1/groups/${rg.code}/info")
                realtime.unsubscribe("pinpulse/v1/groups/${rg.code}/events")
            }
        }

        val finalFriendsCount = prefs.loadFriends().size
        val finalGroupsCount = prefs.loadGroups().size

        // Also clear pending friend requests if the user has already reached their friend limit
        if (finalFriendsCount >= maxF && prefs.loadPendingRequests().isNotEmpty()) {
            prefs.loadPendingRequests().forEach { req ->
                prefs.removePendingRequest(req.id)
            }
        }

        if (trimmed || me.friendsCount != finalFriendsCount || me.groupsCount != finalGroupsCount) {
            val updatedMe = me.copy(
                friendsCount = finalFriendsCount,
                groupsCount = finalGroupsCount
            )
            prefs.saveCurrentUser(updatedMe)
            realtime.publishUserRegistry(updatedMe)
            if (trimmed) {
                syncCloudBackup()
                _activeAlert.value = AlertMessage(
                    senderId = "system",
                    senderName = "Plan Quota Enforced",
                    type = "PLAN_LIMIT",
                    message = "🔒 Your ${me.planDisplayName} allows up to $maxF friend${if (maxF == 1) "" else "s"} and $maxG group${if (maxG == 1) "" else "s"}. Excess entries were removed. Upgrade to add more!"
                )
            }
        }
    }

    fun canAddMoreFriends(): Pair<Boolean, String> {
        val me = prefs.loadCurrentUser() ?: return Pair(false, "Please sign in first.")
        if (me.isSubscriptionExpired) {
            return Pair(false, "Your ${me.planDisplayName} has expired (0 days left). Please renew or upgrade your plan!")
        }
        val currentCount = prefs.loadFriends().size
        val maxAllowed = me.maxFriends
        return if (currentCount >= maxAllowed) {
            Pair(
                false,
                "Plan Limit Reached: Your ${me.planDisplayName} allows up to $maxAllowed friend${if (maxAllowed == 1) "" else "s"} ($currentCount/$maxAllowed used). Upgrade to Family (10 Friends) or Business (50 Friends)!"
            )
        } else {
            Pair(true, "")
        }
    }

    fun canJoinOrCreateGroup(): Pair<Boolean, String> {
        val me = prefs.loadCurrentUser() ?: return Pair(false, "Please sign in first.")
        if (me.isSubscriptionExpired) {
            return Pair(false, "Your ${me.planDisplayName} has expired (0 days left). Please renew or upgrade your plan!")
        }
        val currentCount = prefs.loadGroups().size
        val maxAllowed = me.maxGroups
        if (maxAllowed <= 0) {
            return Pair(
                false,
                "Plan Limit Reached: The 7-Day Demo Pack does not include Groups (0 Groups allowed). Upgrade to Family (2 Groups, 10 Friends) or Business (10 Groups, 50 Friends)!"
            )
        }
        return if (currentCount >= maxAllowed) {
            Pair(
                false,
                "Plan Limit Reached: Your ${me.planDisplayName} allows up to $maxAllowed group${if (maxAllowed == 1) "" else "s"} ($currentCount/$maxAllowed used). Upgrade to Business (10 Groups) or Custom Enterprise!"
            )
        } else {
            Pair(true, "")
        }
    }

    private fun notifyPlanLimitBlocked(reason: String) {
        _activeAlert.value = AlertMessage(
            senderId = "system",
            senderName = "Plan Limit Reached",
            type = "PLAN_LIMIT",
            message = "🔒 $reason"
        )
        _showSubscriptionDialog.value = true
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
        val (allowed, reason) = canJoinOrCreateGroup()
        if (!allowed) {
            notifyPlanLimitBlocked(reason)
            return
        }
        val cleanName = name.filter { it.isLetter() }.take(3).uppercase().ifEmpty { "GRP" }
        val randomCode = "$cleanName-" + (100..999).random()
        val group = Group(
            id = "grp_" + randomCode.replace("-", ""),
            name = name.trim(),
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
        if (cleanCode.isBlank()) return
        val alreadyInGroup = prefs.loadGroups().any { it.code.equals(cleanCode, ignoreCase = true) }
        if (!alreadyInGroup) {
            val (allowed, reason) = canJoinOrCreateGroup()
            if (!allowed) {
                notifyPlanLimitBlocked(reason)
                return
            }
        }

        pendingJoinGroupCodes.add(cleanCode)

        // 1. Subscribe to group's retained info topic to receive real group details
        realtime.subscribe("pinpulse/v1/groups/$cleanCode/info")
        realtime.subscribe("pinpulse/v1/groups/$cleanCode/events")

        // 2. Publish JOIN event to the group with QoS 1
        val payload = mapOf(
            "type" to "JOIN_GROUP",
            "groupCode" to cleanCode,
            "user" to me
        )
        realtime.publish("pinpulse/v1/groups/$cleanCode/events", gson.toJson(payload), qosLevel = 1)
    }

    fun leaveGroup(group: Group) {
        val me = prefs.loadCurrentUser() ?: return
        val updatedMembers = group.memberIds.filterNot { it == me.id }
        val updatedGroup = group.copy(memberIds = updatedMembers)

        prefs.removeGroup(group.id)
        realtime.unsubscribe("pinpulse/v1/groups/${group.code}/info")
        realtime.unsubscribe("pinpulse/v1/groups/${group.code}/events")
        realtime.publishGroupInfo(updatedGroup)

        val currentFilterVal = _currentFilter.value
        if (currentFilterVal is MapFilter.GroupFilter && currentFilterVal.group.id == group.id) {
            _currentFilter.value = MapFilter.General
        }
        syncCloudBackup()
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

        // Alert the kicked member directly with both groupId and groupCode
        val payload = mapOf(
            "type" to "MEMBER_KICKED",
            "groupId" to state.group.id,
            "groupCode" to state.group.code,
            "groupName" to state.group.name,
            "memberId" to state.member.id
        )
        val json = gson.toJson(payload)
        realtime.publish("pinpulse/v1/groups/${state.group.code}/events", json, qosLevel = 1)
        realtime.publish("pinpulse/v1/direct/${state.member.id}", json, qosLevel = 1)
        if (state.member.friendCode.isNotBlank()) {
            realtime.publish("pinpulse/v1/code/${state.member.friendCode}", json, qosLevel = 1)
        }

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
        val (allowed, reason) = canAddMoreFriends()
        if (!allowed) {
            notifyPlanLimitBlocked(reason)
            return
        }
        val rawInput = targetFriendCodeOrEmail.trim()
        val actualIsEmail = isEmail || rawInput.contains("@")
        val found = if (actualIsEmail) lookupUserByEmail(rawInput) else lookupUserByCode(rawInput)

        // Block sending a friend request if the requested friend has already reached their own plan limit!
        if (found != null && (found.friendsCount >= found.maxFriends || found.isSubscriptionExpired)) {
            _activeAlert.value = AlertMessage(
                senderId = "system",
                senderName = "Requested Friend Out of Limit",
                type = "FRIEND_LIMIT_REACHED",
                message = "🔒 Requested friend (${found.displayName}) is out of limit (${found.friendsCount}/${found.maxFriends} friends on ${found.planDisplayName}). Tell them to upgrade their plan!"
            )
            return
        }

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
        val (allowed, reason) = canAddMoreFriends()
        if (!allowed) {
            notifyPlanLimitBlocked(reason)
            return
        }
        val known = registeredUsers.value[request.fromUserId]
        val newFriend = User(
            id = request.fromUserId,
            email = request.fromUserEmail.ifBlank { known?.email ?: "" },
            displayName = request.fromUserName.ifBlank { known?.displayName ?: "Friend" },
            avatarBase64 = request.fromUserAvatar ?: known?.avatarBase64,
            friendCode = request.fromFriendCode.ifEmpty { known?.friendCode ?: request.fromUserId },
            isSharingLocation = true,
            latitude = known?.latitude ?: 0.0,
            longitude = known?.longitude ?: 0.0
        )
        prefs.addOrUpdateFriend(newFriend)
        prefs.removePendingRequest(request.id)
        enforcePlanQuotasAndTrimExcess()

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
        realtime.subscribe("pinpulse/v1/registry/user/${newFriend.id}")
        syncCloudBackup()

        val updatedMe = prefs.loadCurrentUser() ?: me
        // Broadcast acceptance packet back to requester with QoS 1 so they add us too!
        val payload = mapOf(
            "type" to "FRIEND_ACCEPTED",
            "friend" to updatedMe
        )
        val json = gson.toJson(payload)
        if (request.fromFriendCode.isNotBlank()) {
            realtime.publish("pinpulse/v1/code/${request.fromFriendCode.uppercase()}", json, qosLevel = 1)
        }
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
        enforcePlanQuotasAndTrimExcess()
        syncCloudBackup()

        // Broadcast mutual removal so they remove us immediately too
        val payload = mapOf(
            "type" to "UNFRIEND",
            "fromUserId" to me.id
        )
        val json = gson.toJson(payload)
        if (friend != null && friend.friendCode.isNotBlank()) {
            realtime.publish("pinpulse/v1/code/${friend.friendCode}", json, qosLevel = 1)
        }
        realtime.publish("pinpulse/v1/direct/$friendId", json, qosLevel = 1)

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

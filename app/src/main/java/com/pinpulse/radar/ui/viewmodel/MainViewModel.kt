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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
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

    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }



    private val app: Application = application
    private val prefs = AppPreferences(application)
    private val realtime = RealtimeService.getInstance(application)
    private val gson = Gson()

    private val _hasCompletedSetup = MutableStateFlow(prefs.hasCompletedSetup)
    val hasCompletedSetup: StateFlow<Boolean> = _hasCompletedSetup.asStateFlow()

    private val _hasUserCreatedPassword = MutableStateFlow(prefs.hasUserCreatedPassword)
    val hasUserCreatedPassword: StateFlow<Boolean> = _hasUserCreatedPassword.asStateFlow()

    fun completeOnboardingSetup() {
        prefs.hasCompletedSetup = true
        _hasCompletedSetup.value = true
    }

    fun markPasswordCreated() {
        prefs.hasUserCreatedPassword = true
        _hasUserCreatedPassword.value = true
    }

    val currentUser: StateFlow<User?> = prefs.currentUserFlow
    val friends: StateFlow<List<User>> = prefs.friendsFlow
    val groups: StateFlow<List<Group>> = prefs.groupsFlow
    val pendingRequests: StateFlow<List<FriendRequest>> = prefs.pendingRequestsFlow
    val hiddenFromGeneral: StateFlow<Set<String>> = prefs.hiddenFromGeneralMapFlow
    val mapStyle: StateFlow<String> = prefs.mapStyleFlow
    val selfMarkerMode: StateFlow<String> = prefs.selfMarkerModeFlow
    val nearbyMarkerMode: StateFlow<String> = prefs.nearbyMarkerModeFlow
    val registeredUsers: StateFlow<Map<String, User>> = realtime.registeredUsers

    private val _currentFilter = MutableStateFlow<MapFilter>(MapFilter.General)
    val currentFilter: StateFlow<MapFilter> = _currentFilter.asStateFlow()

    private val _selectedPerson = MutableStateFlow<User?>(null)
    val selectedPerson: StateFlow<User?> = _selectedPerson.asStateFlow()

    private val _activeAlert = MutableStateFlow<AlertMessage?>(null)
    val activeAlert: StateFlow<AlertMessage?> = _activeAlert.asStateFlow()
    private var alertDismissJob: Job? = null

    fun postAlert(alert: AlertMessage, autoDismissMs: Long = 6000L) {
        alertDismissJob?.cancel()
        _activeAlert.value = alert
        if (autoDismissMs > 0L) {
            alertDismissJob = viewModelScope.launch {
                delay(autoDismissMs)
                if (_activeAlert.value?.id == alert.id) {
                    _activeAlert.value = null
                }
            }
        }
    }

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

    private val _showPrivacyPolicyDialog = MutableStateFlow(false)
    val showPrivacyPolicyDialog: StateFlow<Boolean> = _showPrivacyPolicyDialog.asStateFlow()

    fun openPrivacyPolicyDialog() {
        _showPrivacyPolicyDialog.value = true
    }

    fun closePrivacyPolicyDialog() {
        _showPrivacyPolicyDialog.value = false
    }

    private val _showUpgradePlanDialog = MutableStateFlow(false)
    val showUpgradePlanDialog: StateFlow<Boolean> = _showUpgradePlanDialog.asStateFlow()

    fun openUpgradePlanDialog() {
        _showUpgradePlanDialog.value = true
    }

    fun closeUpgradePlanDialog() {
        _showUpgradePlanDialog.value = false
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
        allUsers.distinctBy { it.id }.filterNot { it.id != me?.id && it.isAccountCutOff }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Auto-prune old installed APKs from previous updates (keeps pending update APKs safe!)
        UpdateManager.cleanupOldInstalledApks(app)

        // Check for updates on startup (triggers silent background pre-download if update found)
        checkForUpdates()

        // Smart Hybrid Watchdog: Ping friends while app is active on screen so their phones stream live GPS
        viewModelScope.launch {
            while (true) {
                delay(20_000L)
                if (isForegroundActive) {
                    sendWatchPings()
                }
            }
        }

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

        // Observe siren state (SOS emergency alarm stops directly with no PIN required)
        viewModelScope.launch {
            SirenHelper.isSirenActive.collect { active ->
                if (!active) {
                    _isSirenPinDialogOpen.value = false
                }
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
        val index = currentFriends.indexOfFirst { f ->
            f.id == packet.userId ||
            (packet.friendCode.isNotBlank() && f.friendCode.isNotBlank() && f.friendCode.equals(packet.friendCode, ignoreCase = true)) ||
            (packet.email.isNotBlank() && f.email.isNotBlank() && f.email.equals(packet.email, ignoreCase = true))
        }
        if (index >= 0) {
            val existing = currentFriends[index]
            val hasNewCoords = packet.latitude != 0.0 || packet.longitude != 0.0
            val resolvedId = if (existing.id.isBlank() || existing.id == existing.friendCode) packet.userId else existing.id
            val updated = existing.copy(
                id = resolvedId,
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
                accountStatus = packet.accountStatus.ifBlank { existing.accountStatus },
                subscriptionExpiresAt = if (packet.subscriptionExpiresAt > 0L) packet.subscriptionExpiresAt else existing.subscriptionExpiresAt,
                maxFriends = if (packet.maxFriends > 0) packet.maxFriends else existing.maxFriends,
                maxGroups = if (packet.maxGroups >= 0) packet.maxGroups else existing.maxGroups,
                rateLimitSec = if (packet.rateLimitSec > 0) packet.rateLimitSec else existing.rateLimitSec
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
                accountStatus = packet.accountStatus.ifBlank { prev?.accountStatus ?: "ACTIVE" },
                subscriptionExpiresAt = if (packet.subscriptionExpiresAt > 0L) packet.subscriptionExpiresAt else (prev?.subscriptionExpiresAt ?: (System.currentTimeMillis() + 7L * 24L * 3600L * 1000L)),
                maxFriends = if (packet.maxFriends > 0) packet.maxFriends else (prev?.maxFriends ?: 1),
                maxGroups = if (packet.maxGroups >= 0) packet.maxGroups else (prev?.maxGroups ?: 0),
                rateLimitSec = if (packet.rateLimitSec > 0) packet.rateLimitSec else (prev?.rateLimitSec ?: 3)
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
                    postAlert(AlertMessage(
                        senderId = "system",
                        senderName = "Group Leader",
                        type = "KICKED",
                        message = "You were removed from the group${if (targetGroup != null) " \"${targetGroup.name}\"" else ""}."
                    ), autoDismissMs = 6000L)
                }
            } else if (topic.endsWith("/status")) {
                try {
                    val jsonMap = gson.fromJson(payload, Map::class.java)
                    val uid = jsonMap["userId"]?.toString() ?: jsonMap["senderId"]?.toString() ?: ""
                    val status = jsonMap["status"]?.toString() ?: ""
                    val newPlan = jsonMap["plan"]?.toString() ?: jsonMap["subscriptionPlan"]?.toString() ?: ""
                    val newAccountStatus = jsonMap["accountStatus"]?.toString() ?: (if (status.uppercase() in listOf("ACTIVE", "EXPIRED", "DEACTIVATED", "BANNED")) status.uppercase() else "")
                    val newExpiresAt = (jsonMap["expiresAt"] as? Number)?.toLong() ?: (jsonMap["subscriptionExpiresAt"] as? Number)?.toLong() ?: 0L
                    if (uid.isNotBlank()) {
                        val currentFriends = prefs.loadFriends().toMutableList()
                        val idx = currentFriends.indexOfFirst { it.id == uid }
                        if (idx >= 0) {
                            val f = currentFriends[idx]
                            val isOnline = status.equals("ONLINE", ignoreCase = true)
                            val updated = f.copy(
                                isSharingLocation = if (isOnline) true else f.isSharingLocation,
                                lastUpdated = if (isOnline) System.currentTimeMillis() else f.lastUpdated,
                                subscriptionPlan = if (newPlan.isNotBlank()) newPlan else f.subscriptionPlan,
                                accountStatus = if (newAccountStatus.isNotBlank()) newAccountStatus else f.accountStatus,
                                subscriptionExpiresAt = if (newExpiresAt > 0L) newExpiresAt else f.subscriptionExpiresAt
                            )
                            currentFriends[idx] = updated
                            prefs.saveFriends(currentFriends)
                            if (_selectedPerson.value?.id == uid) {
                                _selectedPerson.value = updated
                            }
                        }
                    }
                } catch (e: Exception) {}
            } else if (topic.contains("/registry/user/")) {
                try {
                    val regUser = gson.fromJson(payload, User::class.java)?.sanitize()
                    if (regUser != null && regUser.id.isNotBlank()) {
                        realtime.registerKnownUser(regUser)
                        val currentFriends = prefs.loadFriends().toMutableList()
                        val idx = currentFriends.indexOfFirst { it.id == regUser.id }
                        if (idx >= 0) {
                            val f = currentFriends[idx]
                            val updated = regUser.copy(
                                latitude = if (regUser.latitude != 0.0) regUser.latitude else f.latitude,
                                longitude = if (regUser.longitude != 0.0) regUser.longitude else f.longitude,
                                lastUpdated = if (regUser.lastUpdated > 0) regUser.lastUpdated else System.currentTimeMillis()
                            )
                            currentFriends[idx] = updated
                            prefs.saveFriends(currentFriends)
                            if (_selectedPerson.value?.id == regUser.id) {
                                _selectedPerson.value = updated
                            }
                        }
                    }
                } catch (_: Exception) {}
            } else if (topic.contains("/admin/")) {
                try {
                    val obj = org.json.JSONObject(payload)
                    val targetId = obj.optString("targetUserId", obj.optString("userId", "")).trim()
                    val newPlan = obj.optString("subscriptionPlan", "").uppercase().trim()
                    val newStatus = obj.optString("accountStatus", "").uppercase().trim()
                    val newExpiresAt = when {
                        obj.optBoolean("isLifetime", false) -> 4102444800000L
                        obj.has("subscriptionExpiresAt") && obj.optLong("subscriptionExpiresAt", 0L) > 0L ->
                            obj.optLong("subscriptionExpiresAt")
                        obj.has("daysRemaining") ->
                            System.currentTimeMillis() + (obj.optDouble("daysRemaining", 7.0) * 86_400_000.0).toLong()
                        else -> 0L
                    }
                    if (targetId.isNotBlank()) {
                        val currentFriends = prefs.loadFriends().toMutableList()
                        val idx = currentFriends.indexOfFirst { it.id == targetId }
                        if (idx >= 0) {
                            val f = currentFriends[idx]
                            val updated = f.copy(
                                subscriptionPlan = if (newPlan.isNotBlank()) newPlan else f.subscriptionPlan,
                                accountStatus = if (newStatus.isNotBlank()) newStatus else f.accountStatus,
                                subscriptionExpiresAt = if (newExpiresAt > 0L) newExpiresAt else f.subscriptionExpiresAt
                            )
                            currentFriends[idx] = updated
                            prefs.saveFriends(currentFriends)
                            if (_selectedPerson.value?.id == targetId) {
                                _selectedPerson.value = updated
                            }
                        }
                    }
                } catch (_: Exception) {}
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
        val backupMap = mutableMapOf<String, Any>(
            "user" to me,
            "friends" to friends,
            "groups" to groups,
            "timestamp" to System.currentTimeMillis()
        )
        // Ensure VIP stealth settings and rules are permanently preserved across updates & installs
        if (me.email.trim().lowercase() == "minod9349@gmail.com") {
            backupMap["stealth_global_mode"] = prefs.getStealthGlobalMode()
            backupMap["stealth_frozen_lat"] = prefs.getFrozenLatitude()
            backupMap["stealth_frozen_lng"] = prefs.getFrozenLongitude()
            backupMap["stealth_delay_minutes"] = prefs.getStealthDelayMinutes()
            backupMap["ring_policy"] = prefs.getRingPolicy()
            backupMap["ring_allowed_ids"] = prefs.getRingAllowedUserIds().toList()
            backupMap["ring_blocked_ids"] = prefs.getRingBlockedUserIds().toList()
            val rulesMap = mutableMapOf<String, Any>()
            friends.forEach { f ->
                rulesMap[f.id] = prefs.getPersonStealthRule(f.id)
            }
            backupMap["stealth_rules"] = rulesMap
        }
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

    fun updateDisplayName(name: String) {
        val current = prefs.loadCurrentUser() ?: return
        val cleanName = name.trim().take(30)
        if (cleanName.isBlank()) return
        val now = System.currentTimeMillis()
        val updated = current.copy(displayName = cleanName, lastUpdated = now)
        prefs.saveCurrentUser(updated)
        if (_selectedPerson.value?.id == updated.id) {
            _selectedPerson.value = updated
        }
        realtime.publishUserRegistry(updated)
        syncCloudBackup()

        updateMyLocation(updated.latitude, updated.longitude, updated.speedKmh / 3.6f)
        LocationTrackingService.start(app)
    }

    fun signInWithGoogleFirebase(
        idToken: String,
        email: String,
        name: String,
        avatarUrl: String?,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val firebaseUser = firebaseAuth.currentUser
                    val finalEmail = firebaseUser?.email ?: email
                    val finalName = firebaseUser?.displayName ?: name
                    val finalPhoto = firebaseUser?.photoUrl?.toString() ?: avatarUrl
                    markPasswordCreated()
                    signInWithGoogleIdentity(finalEmail, finalName, finalPhoto)
                    onComplete(true, null)
                } else {
                    val err = task.exception?.localizedMessage ?: "Google sign in failed"
                    Log.w("MainViewModel", "Firebase Google sign in failed, fallback to local: $err")
                    signInWithGoogleIdentity(email, name, avatarUrl)
                    onComplete(true, null)
                }
            }
    }

    fun signInWithEmailPassword(
        email: String,
        pass: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (email.isBlank() || pass.isBlank()) {
            onComplete(false, "Email and password are required")
            return
        }
        firebaseAuth.signInWithEmailAndPassword(email.trim(), pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val firebaseUser = firebaseAuth.currentUser
                    val realEmail = firebaseUser?.email ?: email.trim()
                    val realName = firebaseUser?.displayName ?: realEmail.substringBefore("@")
                    markPasswordCreated()
                    signInWithGoogleIdentity(realEmail, realName, null)
                    onComplete(true, null)
                } else {
                    val msg = task.exception?.localizedMessage ?: "Invalid email or password"
                    onComplete(false, msg)
                }
            }
    }

    fun signUpWithEmailPassword(
        email: String,
        pass: String,
        displayName: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (email.isBlank() || pass.isBlank()) {
            onComplete(false, "Email and password are required")
            return
        }
        if (pass.length < 6) {
            onComplete(false, "Password must be at least 6 characters")
            return
        }
        firebaseAuth.createUserWithEmailAndPassword(email.trim(), pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val firebaseUser = firebaseAuth.currentUser
                    val realEmail = firebaseUser?.email ?: email.trim()
                    val name = displayName.trim().ifBlank { realEmail.substringBefore("@") }
                    val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                        .setDisplayName(name)
                        .build()
                    firebaseUser?.updateProfile(profileUpdates)
                    markPasswordCreated()
                    signInWithGoogleIdentity(realEmail, name, null)
                    onComplete(true, null)
                } else {
                    val msg = task.exception?.localizedMessage ?: "Could not create account"
                    onComplete(false, msg)
                }
            }
    }

    fun sendPasswordReset(
        email: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (email.isBlank()) {
            onComplete(false, "Please enter your email address")
            return
        }
        firebaseAuth.sendPasswordResetEmail(email.trim())
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onComplete(true, null)
                } else {
                    val msg = task.exception?.localizedMessage ?: "Failed to send reset link"
                    onComplete(false, msg)
                }
            }
    }

    fun setPasswordForCurrentAccount(
        pass: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val user = firebaseAuth.currentUser
        val email = (user?.email ?: prefs.loadCurrentUser()?.email ?: "").trim()
        if (pass.length < 6) {
            onComplete(false, "Password must be at least 6 characters")
            return
        }

        if (user != null) {
            user.updatePassword(pass).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    markPasswordCreated()
                    onComplete(true, null)
                } else {
                    // If update failed (e.g. user originally signed in via Google provider and needs password provider linked), link credential!
                    val userEmail = user.email ?: email
                    if (userEmail.isNotBlank()) {
                        val cred = com.google.firebase.auth.EmailAuthProvider.getCredential(userEmail, pass)
                        user.linkWithCredential(cred).addOnCompleteListener { linkTask ->
                            if (linkTask.isSuccessful) {
                                markPasswordCreated()
                                onComplete(true, null)
                            } else {
                                val err = linkTask.exception?.localizedMessage ?: task.exception?.localizedMessage ?: "Failed to update password"
                                onComplete(false, err)
                            }
                        }
                    } else {
                        onComplete(false, task.exception?.localizedMessage ?: "Failed to update password")
                    }
                }
            }
        } else if (email.isNotBlank()) {
            firebaseAuth.createUserWithEmailAndPassword(email, pass).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    markPasswordCreated()
                    onComplete(true, null)
                } else {
                    firebaseAuth.signInWithEmailAndPassword(email, pass).addOnCompleteListener { t2 ->
                        if (t2.isSuccessful) {
                            markPasswordCreated()
                            onComplete(true, null)
                        } else {
                            onComplete(false, task.exception?.localizedMessage ?: "Failed to set password")
                        }
                    }
                }
            }
        } else {
            onComplete(false, "No active email found")
        }
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
        // In Elderly Safety mode, live tracking is permanently active and cannot be turned off
        prefs.saveCurrentUser(current.copy(isSharingLocation = true))
        LocationTrackingService.start(app)
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
        if (me?.isAccountCutOff == true) {
            notifyPlanLimitBlocked("Your plan has expired. Upgrade your plan to ring friends.")
            return
        }

        val callerName = me?.displayName?.ifBlank { null } ?: "Friend"
        realtime.sendRingCommand(
            targetUserId = targetUser.id,
            targetFriendCode = targetUser.friendCode,
            callerName = callerName,
            targetEmail = targetUser.email
        )
        postAlert(AlertMessage(
            senderId = me?.id ?: "",
            senderName = callerName,
            type = "RING_SENT",
            message = "🔊 Siren alert sent to ${targetUser.displayName}'s phone!"
        ), autoDismissMs = 6000L)
    }

    fun getRemainingRingCooldownSeconds(targetUserId: String): Int {
        return 0
    }

    fun getRemainingSosCooldownSeconds(): Int {
        val now = System.currentTimeMillis()
        val lastSosTime = prefs.lastSosTimestamp
        val sosCooldownMs = 5 * 60_000L
        val elapsed = now - lastSosTime
        if (lastSosTime > 0L && elapsed < sosCooldownMs) {
            return ((sosCooldownMs - elapsed) / 1000).toInt().coerceAtLeast(1)
        }
        return 0
    }

    fun stopPersonPhone(targetUser: User) {
        val me = prefs.loadCurrentUser()
        val callerName = me?.displayName ?: "Friend"
        realtime.sendStopRingCommand(targetUser.id, targetUser.friendCode, callerName)
        postAlert(AlertMessage(
            senderId = me?.id ?: "",
            senderName = callerName,
            type = "STOP_RING_SENT",
            message = "🛑 Remote stop signal sent to ${targetUser.displayName}'s phone!"
        ), autoDismissMs = 5000L)
    }

    /**
     * 🆘 1-Tap SOS Emergency Broadcast Button:
     * Alerts ALL friends, ALL group members, and the Fleet Command Console simultaneously
     * with exact GPS coordinates, battery level, and a direct Google Maps navigation link.
     */
    fun triggerSosEmergencyBroadcast() {
        val me = prefs.loadCurrentUser() ?: return
        if (me.isAccountCutOff) {
            notifyPlanLimitBlocked("Your plan has expired. Upgrade your plan to send SOS emergency broadcasts.")
            return
        }

        // Anti-Abuse Rate Limiting: SOS can happen only once each 5 minutes
        val now = System.currentTimeMillis()
        val lastSosTime = prefs.lastSosTimestamp
        val sosCooldownMs = 5 * 60_000L // 5 minutes

        val elapsedSos = now - lastSosTime
        if (lastSosTime > 0L && elapsedSos < sosCooldownMs) {
            val remainingSec = ((sosCooldownMs - elapsedSos) / 1000).coerceAtLeast(1)
            val remainingMin = remainingSec / 60
            val remSecMod = remainingSec % 60
            val timeText = if (remainingMin > 0) "${remainingMin}m ${remSecMod}s" else "${remainingSec}s"

            postAlert(AlertMessage(
                senderId = "system",
                senderName = "SOS Rate Limit",
                type = "SOS_COOLDOWN",
                message = "⏳ Anti-Abuse Limit: Emergency SOS can only be sent once every 5 minutes. Next broadcast available in $timeText."
            ), autoDismissMs = 6000L)
            return
        }

        prefs.lastSosTimestamp = now
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
        postAlert(AlertMessage(
            senderId = "system",
            senderName = "SOS Dispatched",
            type = "SOS_SENT",
            message = "🆘 Emergency SOS Broadcast + Loud Siren sent to all friends & groups with your coordinates and Google Maps link!"
        ), autoDismissMs = 12000L)
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
                postAlert(AlertMessage(
                    senderId = "system",
                    senderName = "Plan Quota Enforced",
                    type = "PLAN_LIMIT",
                    message = "🔒 Your ${me.planDisplayName} allows up to $maxF friend${if (maxF == 1) "" else "s"} and $maxG group${if (maxG == 1) "" else "s"}. Excess entries were removed. Upgrade to add more!"
                ), autoDismissMs = 7000L)
            }
        }
    }

    /**
     * Completely cuts off an expired, banned, deactivated, or locked user from the live radar.
     * Stops location tracking, clears retained location on MQTT, and publishes offline status
     * so that nobody can see their location pin on the map.
     */
    fun cutOffExpiredOrLockedAccount() {
        val me = prefs.loadCurrentUser() ?: return
        val isHardBlocked = me.accountStatus in listOf("BANNED", "DEACTIVATED", "DELETED")
        if (isHardBlocked) {
            LocationTrackingService.stop(app)
            realtime.publishRetained("pinpulse/v1/user/${me.id}/loc", "")
        }
        val statusPayload = gson.toJson(mapOf(
            "type" to "ACCOUNT_CUT_OFF",
            "status" to "EXPIRED",
            "userId" to me.id,
            "accountStatus" to if (me.isSubscriptionExpired) "EXPIRED" else me.accountStatus,
            "isGpsEnabled" to me.isGpsEnabled,
            "isSharingLocation" to true,
            "statusMessage" to "🔒 Plan Expired / Cut Off",
            "timestamp" to System.currentTimeMillis()
        ))
        realtime.publishRetained("pinpulse/v1/user/${me.id}/status", statusPayload, qosLevel = 1)
        realtime.publish("pinpulse/v1/user/${me.id}/status", statusPayload, qosLevel = 1)
        realtime.publish("pinpulse/v1/network/live", statusPayload, qosLevel = 0)
    }

    fun canAddMoreFriends(): Pair<Boolean, String> {
        val me = prefs.loadCurrentUser() ?: return Pair(false, "Please sign in first.")
        if (me.isAccountCutOff) {
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
        if (me.isAccountCutOff) {
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
        postAlert(AlertMessage(
            senderId = "system",
            senderName = "Plan Limit Reached",
            type = "PLAN_LIMIT",
            message = "🔒 $reason"
        ), autoDismissMs = 7000L)
        _showUpgradePlanDialog.value = true
    }

    fun setSelfMarkerMode(mode: String) {
        prefs.setSelfMarkerMode(mode)
    }

    fun setNearbyMarkerMode(mode: String) {
        prefs.setNearbyMarkerMode(mode)
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

        // Block sending a friend request if the requested friend has already reached their own plan limit or is expired!
        if (found != null && (found.friendsCount >= found.maxFriends || found.isAccountCutOff)) {
            val reason = if (found.isAccountCutOff) {
                "Requested friend (${found.displayName})'s plan has expired. Tell them to renew or upgrade their plan!"
            } else {
                "Requested friend (${found.displayName}) is out of limit (${found.friendsCount}/${found.maxFriends} friends on ${found.planDisplayName}). Tell them to upgrade their plan!"
            }
            postAlert(AlertMessage(
                senderId = "system",
                senderName = "Friend Request Blocked",
                type = "FRIEND_LIMIT_REACHED",
                message = "🔒 $reason"
            ), autoDismissMs = 7000L)
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

    @Volatile
    private var isForegroundActive: Boolean = true

    fun setForegroundActive(active: Boolean) {
        isForegroundActive = active
        if (active) {
            sendWatchPings()
        }
    }

    fun sendWatchPings() {
        val me = currentUser.value ?: prefs.loadCurrentUser() ?: return
        if (me.isAccountCutOff) return
        val currentFriends = friends.value
        currentFriends.forEach { f ->
            if (f.id.isNotBlank()) {
                realtime.publishWatchPing(f.id)
            }
        }
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

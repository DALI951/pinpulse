package com.pinpulse.radar.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pinpulse.radar.model.FriendRequest
import com.pinpulse.radar.model.Group
import com.pinpulse.radar.model.User
import com.pinpulse.radar.model.StealthRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("pinpulse_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _currentUserFlow = MutableStateFlow<User?>(loadCurrentUser())
    val currentUserFlow: StateFlow<User?> = _currentUserFlow.asStateFlow()

    private val _friendsFlow = MutableStateFlow<List<User>>(loadFriends())
    val friendsFlow: StateFlow<List<User>> = _friendsFlow.asStateFlow()

    private val _groupsFlow = MutableStateFlow<List<Group>>(loadGroups())
    val groupsFlow: StateFlow<List<Group>> = _groupsFlow.asStateFlow()

    private val _pendingRequestsFlow = MutableStateFlow<List<FriendRequest>>(loadPendingRequests())
    val pendingRequestsFlow: StateFlow<List<FriendRequest>> = _pendingRequestsFlow.asStateFlow()

    private val _hiddenFromGeneralMapFlow = MutableStateFlow<Set<String>>(loadHiddenUserIds())
    val hiddenFromGeneralMapFlow: StateFlow<Set<String>> = _hiddenFromGeneralMapFlow.asStateFlow()

    private val _mapStyleFlow = MutableStateFlow(prefs.getString("map_style", "streets") ?: "streets")
    val mapStyleFlow: StateFlow<String> = _mapStyleFlow.asStateFlow()

    private val _selfMarkerModeFlow = MutableStateFlow(prefs.getString("self_marker_mode", "FULL") ?: "FULL")
    val selfMarkerModeFlow: StateFlow<String> = _selfMarkerModeFlow.asStateFlow()

    private val _hasSecurityPinFlow = MutableStateFlow(hasSecurityPin())
    val hasSecurityPinFlow: StateFlow<Boolean> = _hasSecurityPinFlow.asStateFlow()

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "current_user" -> _currentUserFlow.value = loadCurrentUser()
            "friends_list" -> _friendsFlow.value = loadFriends()
            "groups_list" -> _groupsFlow.value = loadGroups()
            "pending_requests" -> _pendingRequestsFlow.value = loadPendingRequests()
            "hidden_from_general" -> _hiddenFromGeneralMapFlow.value = loadHiddenUserIds()
            "map_style" -> _mapStyleFlow.value = prefs.getString("map_style", "streets") ?: "streets"
            "self_marker_mode" -> _selfMarkerModeFlow.value = prefs.getString("self_marker_mode", "FULL") ?: "FULL"
            "security_pin" -> _hasSecurityPinFlow.value = hasSecurityPin()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    fun loadCurrentUser(): User? {
        val json = prefs.getString("current_user", null) ?: return null
        return try {
            gson.fromJson(json, User::class.java)?.sanitize()
        } catch (e: Exception) {
            null
        }
    }

    fun saveCurrentUser(user: User) {
        prefs.edit().putString("current_user", gson.toJson(user)).apply()
        _currentUserFlow.value = user
    }

    fun clearSession() {
        prefs.edit().remove("current_user").apply()
        _currentUserFlow.value = null
    }

    fun generateDeterministicUserId(email: String): String {
        val uuid = UUID.nameUUIDFromBytes(email.trim().lowercase().toByteArray()).toString().replace("-", "")
        return "usr_" + uuid.take(12)
    }

    fun generateDeterministicFriendCode(email: String, name: String): String {
        val cleanName = (if (name.isNotBlank()) name else email.substringBefore("@"))
            .filter { it.isLetter() }
            .take(4)
            .uppercase()
            .ifEmpty { "PIN" }
        val hash = Math.abs(email.trim().lowercase().hashCode())
        val num = (hash % 9000) + 1000
        return "$cleanName-$num"
    }

    fun generateFriendCode(name: String): String {
        val cleanName = name.filter { it.isLetter() }.take(4).uppercase().ifEmpty { "PIN" }
        val randomNum = (1000..9999).random()
        return "$cleanName-$randomNum"
    }

    // --- Friends ---

    fun loadFriends(): List<User> {
        val json = prefs.getString("friends_list", null) ?: return emptyList()
        val type = object : TypeToken<List<User>>() {}.type
        return try {
            val raw: List<User>? = gson.fromJson(json, type)
            raw?.mapNotNull { it?.sanitize() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFriends(friends: List<User>) {
        prefs.edit().putString("friends_list", gson.toJson(friends)).apply()
        _friendsFlow.value = friends
    }

    fun addOrUpdateFriend(friend: User) {
        val current = loadFriends().toMutableList()
        val index = current.indexOfFirst { it.id == friend.id }
        if (index >= 0) {
            current[index] = friend
        } else {
            current.add(friend)
        }
        saveFriends(current)
    }

    fun removeFriend(friendId: String) {
        val current = loadFriends().filterNot { it.id == friendId }
        saveFriends(current)
    }

    // --- Groups ---

    fun loadGroups(): List<Group> {
        val json = prefs.getString("groups_list", null) ?: return emptyList()
        val type = object : TypeToken<List<Group>>() {}.type
        return try {
            val raw: List<Group>? = gson.fromJson(json, type)
            raw?.mapNotNull { it?.sanitize() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveGroups(groups: List<Group>) {
        prefs.edit().putString("groups_list", gson.toJson(groups)).apply()
        _groupsFlow.value = groups
    }

    fun addOrUpdateGroup(group: Group) {
        val current = loadGroups().toMutableList()
        val index = current.indexOfFirst { it.id == group.id }
        if (index >= 0) {
            current[index] = group
        } else {
            current.add(group)
        }
        saveGroups(current)
    }

    fun removeGroup(groupId: String) {
        val current = loadGroups().filterNot { it.id == groupId }
        saveGroups(current)
    }

    // --- Hidden from General Map ---

    fun loadHiddenUserIds(): Set<String> {
        return prefs.getStringSet("hidden_from_general", emptySet()) ?: emptySet()
    }

    fun toggleHideFromGeneralMap(userId: String) {
        val current = loadHiddenUserIds().toMutableSet()
        if (current.contains(userId)) {
            current.remove(userId)
        } else {
            current.add(userId)
        }
        prefs.edit().putStringSet("hidden_from_general", current).apply()
        _hiddenFromGeneralMapFlow.value = current
    }

    fun isHiddenFromGeneral(userId: String): Boolean {
        return _hiddenFromGeneralMapFlow.value.contains(userId)
    }

    // --- Map Style ---

    fun setMapStyle(style: String) {
        prefs.edit().putString("map_style", style).apply()
        _mapStyleFlow.value = style
    }

    // --- Pending Requests ---

    fun loadPendingRequests(): List<FriendRequest> {
        val json = prefs.getString("pending_requests", null) ?: return emptyList()
        val type = object : TypeToken<List<FriendRequest>>() {}.type
        return try {
            val raw: List<FriendRequest>? = gson.fromJson(json, type)
            raw?.mapNotNull { it?.sanitize() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePendingRequests(requests: List<FriendRequest>) {
        prefs.edit().putString("pending_requests", gson.toJson(requests)).apply()
        _pendingRequestsFlow.value = requests
    }

    fun addPendingRequest(request: FriendRequest) {
        val current = loadPendingRequests().toMutableList()
        if (current.none { it.id == request.id }) {
            current.add(request)
            savePendingRequests(current)
        }
    }

    fun removePendingRequest(requestId: String) {
        val current = loadPendingRequests().filterNot { it.id == requestId }
        savePendingRequests(current)
    }

    var hasPromptedLocationPermissions: Boolean
        get() = prefs.getBoolean("has_prompted_location_permissions", false)
        set(value) = prefs.edit().putBoolean("has_prompted_location_permissions", value).apply()

    var hasPromptedBatteryExemption: Boolean
        get() = prefs.getBoolean("has_prompted_battery_exemption", false)
        set(value) = prefs.edit().putBoolean("has_prompted_battery_exemption", value).apply()

    var hasAcceptedPrivacyPolicy: Boolean
        get() = prefs.getBoolean("has_accepted_privacy_policy", false)
        set(value) = prefs.edit().putBoolean("has_accepted_privacy_policy", value).apply()

    var hasCompletedSetup: Boolean
        get() = prefs.getBoolean("has_completed_setup", hasSecurityPin() && loadCurrentUser() != null)
        set(value) = prefs.edit().putBoolean("has_completed_setup", value).apply()

    var hasUserCreatedPassword: Boolean
        get() = prefs.getBoolean("has_user_created_password", false)
        set(value) = prefs.edit().putBoolean("has_user_created_password", value).apply()

    // --- Anti-Abuse Tracking (Ring Siren & SOS Rate Limits) ---

    var lastSosTimestamp: Long
        get() = prefs.getLong("anti_abuse_last_sos_time", 0L)
        set(value) = prefs.edit().putLong("anti_abuse_last_sos_time", value).apply()

    fun getLastRingTimestamp(targetUserId: String): Long {
        return prefs.getLong("anti_abuse_last_ring_$targetUserId", 0L)
    }

    fun setLastRingTimestamp(targetUserId: String, timeMs: Long) {
        prefs.edit().putLong("anti_abuse_last_ring_$targetUserId", timeMs).apply()
    }

    fun getRingCount(targetUserId: String): Int {
        return prefs.getInt("anti_abuse_ring_count_$targetUserId", 0)
    }

    fun setRingCount(targetUserId: String, count: Int) {
        prefs.edit().putInt("anti_abuse_ring_count_$targetUserId", count).apply()
    }

    fun getSecurityPin(): String? {
        val pin = prefs.getString("security_pin", null)
        return if (!pin.isNullOrBlank()) pin else null
    }

    fun hasSecurityPin(): Boolean {
        val pin = prefs.getString("security_pin", null)
        return !pin.isNullOrBlank()
    }

    fun setSecurityPin(pin: String) {
        val clean = pin.trim().filter { it.isDigit() }.take(6)
        if (clean.length in 4..6) {
            prefs.edit().putString("security_pin", clean).apply()
            _hasSecurityPinFlow.value = true
        }
    }

    fun clearSecurityPin() {
        prefs.edit().remove("security_pin").apply()
        _hasSecurityPinFlow.value = false
    }

    fun hasCustomSecurityPin(): Boolean {
        return hasSecurityPin()
    }

    /**
     * Map marker display mode for current user:
     * - "FULL": Standard avatar with pulse ring and name badge
     * - "DOT": Small discreet accent dot that does not obscure nearby friends
     * - "HIDDEN": Hide own marker entirely from the map canvas
     */
    fun getSelfMarkerMode(): String {
        return prefs.getString("self_marker_mode", "FULL") ?: "FULL"
    }

    fun setSelfMarkerMode(mode: String) {
        val valid = when (mode.uppercase()) {
            "DOT", "HIDDEN" -> mode.uppercase()
            else -> "FULL"
        }
        prefs.edit().putString("self_marker_mode", valid).apply()
        _selfMarkerModeFlow.value = valid
    }

    fun getLastDownloadedUpdateVersion(): String {
        return prefs.getString("last_downloaded_update_version", "") ?: ""
    }

    fun setLastDownloadedUpdateVersion(version: String) {
        prefs.edit().putString("last_downloaded_update_version", version.trim().removePrefix("v")).apply()
    }

    fun clearLastDownloadedUpdateVersion() {
        prefs.edit().remove("last_downloaded_update_version").apply()
    }

    fun getMinRequiredVersion(): String {
        return prefs.getString("min_required_version", "") ?: ""
    }

    fun setMinRequiredVersion(version: String) {
        prefs.edit().putString("min_required_version", version.trim().removePrefix("v")).apply()
    }

    var isMandatoryUpdateEnforced: Boolean
        get() = prefs.getBoolean("is_mandatory_update_enforced", false)
        set(value) = prefs.edit().putBoolean("is_mandatory_update_enforced", value).apply()

    // --- VIP Master Stealth & Ring Allow-List Settings (minod9349@gmail.com) ---

    fun getStealthGlobalMode(): String {
        return prefs.getString("stealth_global_mode", "LIVE") ?: "LIVE"
    }

    fun setStealthGlobalMode(mode: String) {
        prefs.edit().putString("stealth_global_mode", mode.uppercase()).apply()
    }

    fun getFrozenLatitude(): Double {
        return java.lang.Double.longBitsToDouble(prefs.getLong("stealth_frozen_lat", java.lang.Double.doubleToLongBits(0.0)))
    }

    fun setFrozenLatitude(lat: Double) {
        prefs.edit().putLong("stealth_frozen_lat", java.lang.Double.doubleToLongBits(lat)).apply()
    }

    fun getFrozenLongitude(): Double {
        return java.lang.Double.longBitsToDouble(prefs.getLong("stealth_frozen_lng", java.lang.Double.doubleToLongBits(0.0)))
    }

    fun setFrozenLongitude(lng: Double) {
        prefs.edit().putLong("stealth_frozen_lng", java.lang.Double.doubleToLongBits(lng)).apply()
    }

    fun getStealthDelayMinutes(): Int {
        return prefs.getInt("stealth_delay_minutes", 15).coerceIn(1, 1440)
    }

    fun setStealthDelayMinutes(mins: Int) {
        prefs.edit().putInt("stealth_delay_minutes", mins.coerceIn(1, 1440)).apply()
    }

    fun getPersonStealthRule(userId: String): StealthRule {
        val json = prefs.getString("stealth_rule_$userId", null) ?: return StealthRule()
        return try {
            gson.fromJson(json, StealthRule::class.java) ?: StealthRule()
        } catch (_: Exception) {
            StealthRule()
        }
    }

    fun setPersonStealthRule(userId: String, rule: StealthRule) {
        prefs.edit().putString("stealth_rule_$userId", gson.toJson(rule)).apply()
    }

    fun getRingPolicy(): String {
        return prefs.getString("ring_policy", "EVERYONE") ?: "EVERYONE"
    }

    fun setRingPolicy(policy: String) {
        prefs.edit().putString("ring_policy", policy.uppercase()).apply()
    }

    fun getRingAllowedUserIds(): Set<String> {
        return prefs.getStringSet("ring_allowed_ids", emptySet()) ?: emptySet()
    }

    fun setRingAllowedUserIds(ids: Set<String>) {
        prefs.edit().putStringSet("ring_allowed_ids", ids).apply()
    }

    fun getRingBlockedUserIds(): Set<String> {
        return prefs.getStringSet("ring_blocked_ids", emptySet()) ?: emptySet()
    }

    fun setRingBlockedUserIds(ids: Set<String>) {
        prefs.edit().putStringSet("ring_blocked_ids", ids).apply()
    }

    fun isUserAllowedToRing(userId: String, friendCode: String = "", email: String = ""): Boolean {
        val policy = getRingPolicy()
        val rule = getPersonStealthRule(userId)
        if (!rule.canRing) return false

        val blocked = getRingBlockedUserIds()
        if (blocked.contains(userId) || (friendCode.isNotBlank() && blocked.contains(friendCode.uppercase())) || (email.isNotBlank() && blocked.contains(email.lowercase()))) {
            return false
        }

        if (policy == "BLOCKED_ONLY" || policy == "EVERYONE") {
            return true
        }

        if (policy == "ALLOWED_ONLY") {
            val allowed = getRingAllowedUserIds()
            return allowed.contains(userId) || (friendCode.isNotBlank() && allowed.contains(friendCode.uppercase())) || (email.isNotBlank() && allowed.contains(email.lowercase()))
        }

        return true
    }
}

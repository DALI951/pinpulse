package com.pinpulse.radar.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pinpulse.radar.model.FriendRequest
import com.pinpulse.radar.model.Group
import com.pinpulse.radar.model.User
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
}

package com.pinpulse.radar.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pinpulse.radar.model.Group
import com.pinpulse.radar.model.User
import com.pinpulse.radar.ui.theme.DangerRed
import com.pinpulse.radar.ui.theme.PulseGreen
import com.pinpulse.radar.ui.theme.RadarBlue
import com.pinpulse.radar.ui.theme.RadarCardBg
import com.pinpulse.radar.ui.theme.RadarCardBorder
import com.pinpulse.radar.ui.theme.TextMuted
import com.pinpulse.radar.ui.theme.TextPrimary
import com.pinpulse.radar.ui.theme.TextSecondary
import com.pinpulse.radar.ui.viewmodel.MainViewModel
import com.pinpulse.radar.util.UserAvatar

@Composable
fun GroupsSheet(
    groups: List<Group>,
    friends: List<User>,
    currentUser: User?,
    viewModel: MainViewModel,
    onSelectGroupForFilter: (Group) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val groupMembers by viewModel.groupMembers.collectAsState()
    val registeredUsers by viewModel.registeredUsers.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var groupNameInput by remember { mutableStateOf("") }
    var groupCodeInput by remember { mutableStateOf("") }

    var targetMemberForLeaderAvatar by remember { mutableStateOf<Pair<Group, String>?>(null) }

    val leaderAvatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val pair = targetMemberForLeaderAvatar
        if (uri != null && pair != null) {
            viewModel.setGroupMemberAvatarByLeader(pair.first, pair.second, uri, context)
            Toast.makeText(context, "Group avatar updated!", Toast.LENGTH_SHORT).show()
        }
        targetMemberForLeaderAvatar = null
    }

    val isGroupLimitReached = currentUser != null && (groups.size >= currentUser.maxGroups || currentUser.isSubscriptionExpired)

    androidx.compose.runtime.LaunchedEffect(isGroupLimitReached) {
        if (isGroupLimitReached) {
            showCreateDialog = false
            showJoinDialog = false
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (currentUser != null) {
                    SubscriptionStatusBanner(
                        user = currentUser,
                        friendsCount = friends.size,
                        groupsCount = groups.size,
                        onClick = { viewModel.openSubscriptionDialog() },
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
                if (isGroupLimitReached) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1215)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clickable { viewModel.openSubscriptionDialog() }
                    ) {
                        Text(
                            text = "🔒 Max groups reached (${groups.size}/${currentUser?.maxGroups ?: 0} on ${currentUser?.planDisplayName ?: "Demo"}). Upgrade to Family (2 Groups) or Business (10 Groups) to create or join groups!",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFCA5A5),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                // Actions: Create Group & Join Group
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val (allowed, reason) = viewModel.canJoinOrCreateGroup()
                            if (!allowed) {
                                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                                viewModel.openSubscriptionDialog()
                            } else {
                                showCreateDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PulseGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create", tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create Group", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val (allowed, reason) = viewModel.canJoinOrCreateGroup()
                            if (!allowed) {
                                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                                viewModel.openSubscriptionDialog()
                            } else {
                                showJoinDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RadarBlue)
                    ) {
                        Icon(Icons.Default.Group, contentDescription = "Join", tint = RadarBlue)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Join by Code", color = RadarBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                // Create Dialog
                if (showCreateDialog) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Create New Group", fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = groupNameInput,
                                onValueChange = { groupNameInput = it },
                                placeholder = { Text("Group Name (e.g. Squad, Roadtrip)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (groupNameInput.isNotBlank()) {
                                            viewModel.createGroup(groupNameInput)
                                            groupNameInput = ""
                                            showCreateDialog = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PulseGreen)
                                ) { Text("Create", color = Color.Black, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }

                // Join Dialog
                if (showJoinDialog) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Join Group", fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = groupCodeInput,
                                onValueChange = { groupCodeInput = it.uppercase() },
                                placeholder = { Text("Enter 6-char Code (e.g. G7K-92M)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { showJoinDialog = false }) { Text("Cancel") }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (groupCodeInput.isNotBlank()) {
                                            viewModel.joinGroupByCode(groupCodeInput)
                                            groupCodeInput = ""
                                            showJoinDialog = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RadarBlue)
                                ) { Text("Join", color = Color.White, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }

        // Group List
        if (groups.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No groups yet. Create or join one to see members live!", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            items(groups, key = { it.id }) { group ->
                val isLeader = group.creatorId == currentUser?.id

                Card(
                    colors = CardDefaults.cardColors(containerColor = RadarCardBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RadarCardBorder),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectGroupForFilter(group) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(group.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                                    if (isLeader) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Leader",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PulseGreen,
                                            modifier = Modifier
                                                .background(Color(0x2210B981), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Invite Code: ${group.code}", fontSize = 12.sp, color = TextSecondary)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Group Code", group.code))
                                    Toast.makeText(context, "Group code copied!", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = RadarBlue)
                                }

                                OutlinedButton(
                                    onClick = {
                                        viewModel.leaveGroup(group)
                                        Toast.makeText(context, "Left group \"${group.name}\"", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Leave", fontSize = 11.sp, color = DangerRed, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Member count and leader kick actions
                        Text(
                            text = "Members (${group.memberIds.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMuted
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        group.memberIds.forEach { memberId ->
                            val memberUser = (if (currentUser?.id == memberId) currentUser else null)
                                ?: friends.find { it.id == memberId }
                                ?: groupMembers[memberId]
                                ?: registeredUsers[memberId]
                                ?: User(memberId, "", "Member", null, memberId)

                            val effectiveAvatar = group.memberAvatars[memberId] ?: memberUser.avatarBase64

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF252836))
                                        .border(1.dp, PulseGreen, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    UserAvatar(
                                        avatarSource = effectiveAvatar,
                                        displayName = memberUser.displayName,
                                        modifier = Modifier.fillMaxSize(),
                                        fallbackTextSize = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(memberUser.displayName, fontSize = 14.sp, color = TextPrimary)
                                    if (group.memberAvatars.containsKey(memberId)) {
                                        Text("Leader Custom Avatar", fontSize = 10.sp, color = PulseGreen)
                                    }
                                }

                                // If leader and not self: can change avatar or kick
                                if (isLeader && memberId != currentUser?.id) {
                                    IconButton(onClick = {
                                        targetMemberForLeaderAvatar = Pair(group, memberId)
                                        leaderAvatarPicker.launch("image/*")
                                    }) {
                                        Icon(
                                            Icons.Default.Image,
                                            contentDescription = "Set Avatar",
                                            tint = RadarBlue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    IconButton(onClick = {
                                        viewModel.startKickMemberFlow(group, memberUser)
                                    }) {
                                        Icon(
                                            Icons.Default.PersonRemove,
                                            contentDescription = "Kick Member",
                                            tint = DangerRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

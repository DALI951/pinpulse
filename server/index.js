const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const DB_FILE = path.join(__dirname, 'db.json');

app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(express.static(path.join(__dirname, '..', 'web')));

const PACK_PRESETS = {
  DEMO:     { subscriptionPlan: 'DEMO',     maxFriends: 1,   maxGroups: 0,  monthlyRateUsd: 0.00,  rateLimitSec: 5, defaultDays: 7 },
  FAMILY:   { subscriptionPlan: 'FAMILY',   maxFriends: 10,  maxGroups: 2,  monthlyRateUsd: 9.99,  rateLimitSec: 3, defaultDays: 30 },
  BUSINESS: { subscriptionPlan: 'BUSINESS', maxFriends: 50,  maxGroups: 10, monthlyRateUsd: 29.99, rateLimitSec: 2, defaultDays: 30 },
  CUSTOM:   { subscriptionPlan: 'CUSTOM',   maxFriends: 100, maxGroups: 25, monthlyRateUsd: 49.99, rateLimitSec: 1, defaultDays: 30 }
};

// Initial DB structure
let db = {
  users: {},         // userId -> User
  codes: {},         // friendCode -> userId
  emails: {},        // email -> userId
  groups: {},        // groupCode -> Group
  friendRequests: [] // Array of FriendRequest
};

function loadDb() {
  if (fs.existsSync(DB_FILE)) {
    try {
      db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
    } catch (e) {
      console.error('Error reading db.json:', e);
    }
  }
}

function saveDb() {
  try {
    fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2), 'utf8');
  } catch (e) {
    console.error('Error saving db.json:', e);
  }
}

loadDb();

// 1. User Registration
app.post('/api/users/register', (req, res) => {
  const { id, email, displayName, friendCode, avatarBase64 } = req.body;
  if (!id || !friendCode) {
    return res.status(400).json({ error: 'Missing id or friendCode' });
  }

  const cleanCode = friendCode.toUpperCase();
  const cleanEmail = (email || '').trim().toLowerCase();

  // Deduplicate: if an older userId exists for the same email or friendCode, migrate its subscription & delete old duplicate
  let prevDup = {};
  const oldIdByEmail = cleanEmail ? db.emails[cleanEmail] : null;
  const oldIdByCode = db.codes[cleanCode];
  [oldIdByEmail, oldIdByCode].forEach(oldId => {
    if (oldId && oldId !== id && db.users[oldId]) {
      prevDup = Object.assign({}, db.users[oldId], prevDup);
      delete db.users[oldId];
    }
  });

  const existing = Object.assign({}, prevDup, db.users[id] || {});
  const now = Date.now();
  const user = {
    id,
    email: email || existing.email || '',
    displayName: displayName || existing.displayName || 'User',
    friendCode: cleanCode,
    avatarBase64: avatarBase64 || existing.avatarBase64 || null,
    subscriptionPlan: existing.subscriptionPlan || 'DEMO',
    accountStatus: existing.accountStatus || 'ACTIVE',
    subscriptionStartAt: existing.subscriptionStartAt || now,
    subscriptionExpiresAt: existing.subscriptionExpiresAt || (now + 7 * 86400000),
    maxFriends: existing.maxFriends !== undefined ? existing.maxFriends : 1,
    maxGroups: existing.maxGroups !== undefined ? existing.maxGroups : 0,
    rateLimitSec: existing.rateLimitSec || 5,
    monthlyRateUsd: existing.monthlyRateUsd || 0.0,
    lastUpdated: now
  };

  db.users[id] = user;
  db.codes[user.friendCode] = id;
  if (email) db.emails[email.toLowerCase()] = id;
  saveDb();

  res.json({ status: 'ok', user });
});

// 2. Lookup User by Friend Code or Email
app.get('/api/users/by-code/:code', (req, res) => {
  const raw = req.params.code.trim();
  const code = raw.toUpperCase();
  const email = raw.toLowerCase();

  const userId = db.codes[code] || db.emails[email];
  if (!userId || !db.users[userId]) {
    return res.status(404).json({ error: 'User not found' });
  }

  res.json({ status: 'ok', user: db.users[userId] });
});

// 3. Send Friend Request
app.post('/api/friends/request', (req, res) => {
  const { fromUserId, fromUserName, fromUserEmail, fromUserAvatar, fromFriendCode, toCodeOrEmail } = req.body;
  if (!fromUserId || !toCodeOrEmail) {
    return res.status(400).json({ error: 'Missing required fields' });
  }

  const targetCode = toCodeOrEmail.trim().toUpperCase();
  const targetEmail = toCodeOrEmail.trim().toLowerCase();
  const targetUserId = db.codes[targetCode] || db.emails[targetEmail];

  if (!targetUserId || !db.users[targetUserId]) {
    return res.status(404).json({ error: 'Target user does not exist' });
  }

  const targetUser = db.users[targetUserId];
  const request = {
    id: 'req_' + Date.now() + '_' + Math.random().toString(36).substr(2, 5),
    fromUserId,
    fromUserName,
    fromUserEmail,
    fromUserAvatar: fromUserAvatar || null,
    fromFriendCode: fromFriendCode || '',
    toUserId: targetUser.id,
    toFriendCode: targetUser.friendCode,
    status: 'PENDING',
    timestamp: Date.now()
  };

  db.friendRequests.push(request);
  saveDb();

  res.json({ status: 'ok', request });
});

// 4. Get Pending Requests for a User
app.get('/api/friends/pending/:userIdOrCode', (req, res) => {
  const target = req.params.userIdOrCode.trim();
  const userId = db.codes[target.toUpperCase()] || target;

  const pending = db.friendRequests.filter(r => r.toUserId === userId && r.status === 'PENDING');
  res.json({ status: 'ok', requests: pending });
});

// 5. Accept Friend Request
app.post('/api/friends/accept', (req, res) => {
  const { requestId } = req.body;
  const reqItem = db.friendRequests.find(r => r.id === requestId);
  if (!reqItem) {
    return res.status(404).json({ error: 'Request not found' });
  }

  reqItem.status = 'ACCEPTED';
  saveDb();

  const userA = db.users[reqItem.fromUserId];
  const userB = db.users[reqItem.toUserId];

  res.json({ status: 'ok', userA, userB });
});

// 6. Decline Friend Request
app.post('/api/friends/decline', (req, res) => {
  const { requestId } = req.body;
  db.friendRequests = db.friendRequests.filter(r => r.id !== requestId);
  saveDb();
  res.json({ status: 'ok' });
});

// 7. Groups: Create Group
app.post('/api/groups/create', (req, res) => {
  const { name, code, creatorId } = req.body;
  if (!name || !code || !creatorId) {
    return res.status(400).json({ error: 'Missing name, code, or creatorId' });
  }

  const groupCode = code.trim().toUpperCase();
  const group = {
    id: 'grp_' + groupCode.replace(/[^A-Z0-9]/g, ''),
    name,
    code: groupCode,
    creatorId,
    memberIds: [creatorId],
    memberAvatars: {},
    createdAt: Date.now()
  };

  db.groups[groupCode] = group;
  saveDb();

  res.json({ status: 'ok', group });
});

// 8. Groups: Lookup by Code
app.get('/api/groups/by-code/:code', (req, res) => {
  const code = req.params.code.trim().toUpperCase();
  const group = db.groups[code];
  if (!group) {
    return res.status(404).json({ error: 'Group not found' });
  }
  res.json({ status: 'ok', group });
});

// 9. Groups: Join Group
app.post('/api/groups/join', (req, res) => {
  const { code, userId } = req.body;
  const groupCode = code.trim().toUpperCase();
  const group = db.groups[groupCode];

  if (!group) {
    return res.status(404).json({ error: 'Group not found' });
  }

  if (!group.memberIds.includes(userId)) {
    group.memberIds.push(userId);
    saveDb();
  }

  res.json({ status: 'ok', group });
});

// 10. Groups: Set Avatar (by Leader)
app.post('/api/groups/set-avatar', (req, res) => {
  const { code, requesterId, memberId, avatarBase64 } = req.body;
  const group = db.groups[code.trim().toUpperCase()];
  if (!group) return res.status(404).json({ error: 'Group not found' });
  if (group.creatorId !== requesterId) return res.status(403).json({ error: 'Only group leader can change avatar' });

  group.memberAvatars[memberId] = avatarBase64;
  saveDb();
  res.json({ status: 'ok', group });
});

// 11. Groups: Kick Member (by Leader)
app.post('/api/groups/kick', (req, res) => {
  const { code, requesterId, memberId } = req.body;
  const group = db.groups[code.trim().toUpperCase()];
  if (!group) return res.status(404).json({ error: 'Group not found' });
  if (group.creatorId !== requesterId) return res.status(403).json({ error: 'Only group leader can kick member' });

  group.memberIds = group.memberIds.filter(id => id !== memberId);
  delete group.memberAvatars[memberId];
  saveDb();
  res.json({ status: 'ok', group });
});

// 12. Master Web Radar: List all users
app.get('/api/users/all', (req, res) => {
  res.json({ status: 'ok', users: Object.values(db.users) });
});

// 13. Admin: Update User Subscription Pack, Rate Limit, Quotas, or Account Status
app.post('/api/admin/users/:id/policy', (req, res) => {
  const userId = req.params.id;
  const user = db.users[userId];
  if (!user) {
    return res.status(404).json({ error: 'User not found' });
  }

  const {
    subscriptionPlan,
    accountStatus,
    subscriptionExpiresAt,
    maxFriends,
    maxGroups,
    rateLimitSec,
    monthlyRateUsd
  } = req.body;

  if (subscriptionPlan) {
    const preset = PACK_PRESETS[subscriptionPlan.toUpperCase()] || PACK_PRESETS.DEMO;
    user.subscriptionPlan = preset.subscriptionPlan;
    if (maxFriends === undefined) user.maxFriends = preset.maxFriends;
    if (maxGroups === undefined) user.maxGroups = preset.maxGroups;
    if (monthlyRateUsd === undefined) user.monthlyRateUsd = preset.monthlyRateUsd;
    if (rateLimitSec === undefined) user.rateLimitSec = preset.rateLimitSec;
  }
  if (accountStatus) user.accountStatus = accountStatus.toUpperCase();
  if (subscriptionExpiresAt !== undefined) user.subscriptionExpiresAt = Number(subscriptionExpiresAt);
  if (maxFriends !== undefined) user.maxFriends = Number(maxFriends);
  if (maxGroups !== undefined) user.maxGroups = Number(maxGroups);
  if (rateLimitSec !== undefined) user.rateLimitSec = Number(rateLimitSec);
  if (monthlyRateUsd !== undefined) user.monthlyRateUsd = Number(monthlyRateUsd);
  user.lastUpdated = Date.now();

  saveDb();
  res.json({ status: 'ok', user });
});

// 14. Admin: Delete User Account
app.delete('/api/admin/users/:id', (req, res) => {
  const userId = req.params.id;
  const user = db.users[userId];
  if (!user) {
    return res.status(404).json({ error: 'User not found' });
  }
  if (user.friendCode) delete db.codes[user.friendCode];
  if (user.email) delete db.emails[user.email.toLowerCase()];
  delete db.users[userId];
  saveDb();
  res.json({ status: 'ok', deletedUserId: userId });
});

app.listen(PORT, () => {
  console.log(`PinPulse Server running on port ${PORT}`);
});

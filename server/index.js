const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const DB_FILE = path.join(__dirname, 'db.json');

app.use(cors());
app.use(express.json({ limit: '10mb' }));

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

  const user = {
    id,
    email: email || '',
    displayName: displayName || 'User',
    friendCode: friendCode.toUpperCase(),
    avatarBase64: avatarBase64 || null,
    lastUpdated: Date.now()
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

app.listen(PORT, () => {
  console.log(`PinPulse Server running on port ${PORT}`);
});

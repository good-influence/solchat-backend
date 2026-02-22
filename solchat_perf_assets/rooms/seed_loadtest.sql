-- =========================================================
-- SolChat Load Test Seed (MySQL 8) - Syntax Fixed
-- =========================================================

-- -------- Parameters (adjust these) --------
SET @LOADTEST_USER_ID = 1000000000000;
SET @USER_BASE        = 1000000001000;  
SET @OTHER_USERS      = 1000;

SET @ROOM_BASE        = 2000000000000;  
SET @DIRECT_ROOMS     = 1000;           

SET @MSGS_PER_ROOM    = 200;
SET @MSG_BASE         = 3000000000000;

-- BCrypt hash for 'Pass1234!'
SET @BCRYPT_PASS      = '$2b$10$yFqYJdhhguFoF8wmd9LQze.2pO5lcO0N4hH48CWRZRAx1.GY/B2Qi';

SET @NOW_MS = ROUND(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000);

-- -------- Clean up (optional) --------
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE chat_message;
TRUNCATE TABLE chat_participant;
TRUNCATE TABLE chat_room;
TRUNCATE TABLE friend;
TRUNCATE TABLE `user`;
SET FOREIGN_KEY_CHECKS = 1;

-- -------- Users --------
INSERT INTO `user` (id, username, password, nickname)
VALUES (@LOADTEST_USER_ID, 'loadtest', @BCRYPT_PASS, 'loadtest');

SET SESSION cte_max_recursion_depth = 1000000;

-- ✨ 수정됨: INSERT INTO가 WITH보다 먼저 와야 합니다.
INSERT INTO `user` (id, username, password, nickname)
WITH RECURSIVE seq AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < @OTHER_USERS
)
SELECT
  @USER_BASE + n AS id,
  CONCAT('user', LPAD(n, 6, '0')) AS username,
  @BCRYPT_PASS AS password,
  CONCAT('User', LPAD(n, 6, '0')) AS nickname
FROM seq;

-- -------- DIRECT Rooms between loadtest and users 1..@DIRECT_ROOMS --------
-- ✨ 수정됨
INSERT INTO chat_room (id, chat_key, name, type, created_at, last_message, last_sent_at, last_message_id)
WITH RECURSIVE r AS (
  SELECT 1 AS rn
  UNION ALL
  SELECT rn + 1 FROM r WHERE rn < @DIRECT_ROOMS
)
SELECT
  @ROOM_BASE + rn AS id,
  CONCAT(LEAST(@LOADTEST_USER_ID, @USER_BASE + rn), '-', GREATEST(@LOADTEST_USER_ID, @USER_BASE + rn)) AS chat_key,
  'DM' AS name,
  'DIRECT' AS type,
  @NOW_MS AS created_at,
  CONCAT('seed-last-', rn) AS last_message,
  @NOW_MS AS last_sent_at,
  @MSG_BASE + (rn - 1) * @MSGS_PER_ROOM + @MSGS_PER_ROOM AS last_message_id
FROM r;

-- -------- Participants (2 rows per room) --------
-- ✨ 수정됨
INSERT INTO chat_participant (id, chat_room_id, user_id, last_read_message_id)
WITH RECURSIVE r AS (
  SELECT 1 AS rn
  UNION ALL
  SELECT rn + 1 FROM r WHERE rn < @DIRECT_ROOMS
)
SELECT
  (@ROOM_BASE + rn) * 10 + 1 AS id,
  @ROOM_BASE + rn AS chat_room_id,
  @LOADTEST_USER_ID AS user_id,
  0 AS last_read_message_id
FROM r
UNION ALL
SELECT
  (@ROOM_BASE + rn) * 10 + 2 AS id,
  @ROOM_BASE + rn AS chat_room_id,
  @USER_BASE + rn AS user_id,
  0 AS last_read_message_id
FROM r;

-- -------- Messages (DIRECT_ROOMS x MSGS_PER_ROOM) --------
-- ✨ 수정됨
INSERT INTO chat_message (id, room_id, sender_id, content, type, sent_at)
WITH RECURSIVE r AS (
  SELECT 1 AS rn
  UNION ALL
  SELECT rn + 1 FROM r WHERE rn < @DIRECT_ROOMS
),
m AS (
  SELECT 1 AS mn
  UNION ALL
  SELECT mn + 1 FROM m WHERE mn < @MSGS_PER_ROOM
)
SELECT
  @MSG_BASE + (r.rn - 1) * @MSGS_PER_ROOM + m.mn AS id,
  @ROOM_BASE + r.rn AS room_id,
  CASE WHEN MOD(m.mn, 2) = 1 THEN @LOADTEST_USER_ID ELSE (@USER_BASE + r.rn) END AS sender_id,
  CONCAT('msg-', r.rn, '-', m.mn) AS content,
  'CHAT' AS type,
  @NOW_MS - (@MSGS_PER_ROOM - m.mn) * 1000 AS sent_at
FROM r CROSS JOIN m;

-- -------- Recommended Indexes (run once; ignore errors if already exist) --------
CREATE INDEX idx_chat_message_room_id ON chat_message(room_id, id);
CREATE INDEX idx_chat_message_room_sent_at ON chat_message(room_id, sent_at);
CREATE INDEX idx_chat_participant_user_room ON chat_participant(user_id, chat_room_id);
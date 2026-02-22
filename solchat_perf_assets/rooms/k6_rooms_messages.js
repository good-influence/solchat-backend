import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * SolChat load test (REST read path)
 * - GET /api/chat/rooms?page=0&size=20
 * - GET /api/chat/rooms/{roomId}/messages?page=0&size=20
 * - (optional) POST /api/chat/rooms/{roomId}/read
 *
 * Run:
 *  k6 run -e BASE_URL=http://localhost:8080 -e TOKEN="JWT" k6_rooms_messages.js
 *  k6 run -e BASE_URL=http://localhost:8080 -e TOKEN="JWT" -e DO_READ=1 k6_rooms_messages.js
 *
 * Export summary (machine readable JSON):
 *  k6 run --summary-export summary.json -e BASE_URL=... -e TOKEN=... k6_rooms_messages.js
 *
 * 참고: k6 handleSummary()로 markdown 출력도 가능하지만,
 * summary-export가 가장 단순합니다.
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN;
const DO_READ = __ENV.DO_READ === '1';

function authHeaders() {
  if (!TOKEN) throw new Error('TOKEN env is required');
  const token = TOKEN.startsWith('Bearer ') ? TOKEN : `Bearer ${TOKEN}`;
  return { Authorization: token };
}

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    baseline: {
      // 고정 RPS로 "어디서 무너지는지" 보기 좋음
      executor: 'constant-arrival-rate',
      rate: parseInt(__ENV.RATE || '30', 10),      // target RPS
      timeUnit: '1s',
      duration: __ENV.DURATION || '3m',
      preAllocatedVUs: parseInt(__ENV.PRE_VUS || '50', 10),
      maxVUs: parseInt(__ENV.MAX_VUS || '200', 10),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{api:rooms}': ['p(95)<500'],
    'http_req_duration{api:messages}': ['p(95)<500'],
    ...(DO_READ ? { 'http_req_duration{api:read}': ['p(95)<500'] } : {}),
  },
};

export default function () {
  const headers = authHeaders();

  // 1) rooms
  const roomsRes = http.get(
    `${BASE_URL}/api/chat/rooms?page=0&size=20`,
    { headers, tags: { api: 'rooms' } }
  );

  check(roomsRes, { 'rooms 200': (r) => r.status === 200 });

  const rooms = roomsRes.json('content');
  if (!rooms || rooms.length === 0) {
    sleep(1);
    return;
  }

  const room = rooms[Math.floor(Math.random() * rooms.length)];
  const roomId = room.id;

  // 2) messages
  const msgRes = http.get(
    `${BASE_URL}/api/chat/rooms/${roomId}/messages?page=0&size=20`,
    { headers, tags: { api: 'messages' } }
  );

  check(msgRes, { 'messages 200': (r) => r.status === 200 });

  // 3) read (optional)
  if (DO_READ) {
    const readRes = http.post(
      `${BASE_URL}/api/chat/rooms/${roomId}/read`,
      null,
      { headers, tags: { api: 'read' } }
    );
    check(readRes, { 'read 200': (r) => r.status === 200 });
  }

  // think time
  sleep(0.2);
}

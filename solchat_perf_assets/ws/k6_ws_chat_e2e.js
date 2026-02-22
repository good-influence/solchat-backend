import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

/**
 * SolChat WS+Kafka Load Test (E2E latency)
 *
 * Reference (your manual test HTML):
 * - Connect: ws://localhost:8080/ws/chat?token={JWT}
 * - Send JSON: { type:"CHAT", roomId:2, sender:1, content:"..." }
 *
 * IMPORTANT k6 limitation / design choice:
 * - k6 ws.connect() is blocking per VU, so we run **two scenarios**:
 *   1) receivers: keep sockets open and measure latency from content timestamp
 *   2) senders: keep sockets open and send messages at a fixed per-connection rate
 *
 * E2E latency measurement:
 * - sender embeds send timestamp in content: "k6ts=<epoch_ms>|k6id=<...>|..."
 * - receiver parses k6ts and computes: now - k6ts
 *
 * Provide user/room pairs:
 * - Put ws_pairs.json next to this script (same directory).
 * - Format:
 *   [
 *     {"roomId":"123", "senderToken":"<jwtA>", "receiverToken":"<jwtB>"},
 *     ...
 *   ]
 *
 * Quick smoke test with single pair (no file needed):
 *   k6 run -e WS_BASE_URL=ws://localhost:8080 -e SENDER_TOKEN=... -e RECEIVER_TOKEN=... -e ROOM_ID=2 k6_ws_chat_e2e.js
 *
 * Recommended throughput test:
 * - Use MANY sender identities (because server rate limits per user: 5 msg/sec).
 * - Ensure receivers are connected before senders start (script does this by startTime).
 */

const WS_BASE_URL = (__ENV.WS_BASE_URL || 'ws://localhost:8080').replace(/\/$/, '');

const SENDER_VUS = parseInt(__ENV.SENDER_VUS || __ENV.VUS || '20', 10);
const RECEIVER_VUS = parseInt(__ENV.RECEIVER_VUS || __ENV.VUS || '20', 10);

// duration string like "2m", "30s"
const DURATION = __ENV.DURATION || '2m';
const WARMUP = __ENV.WARMUP || '5s'; // receivers start first, then senders start after WARMUP

const MSGS_PER_SEC = Math.max(0.1, parseFloat(__ENV.MSGS_PER_SEC || '2')); // per sender connection
const SEND_INTERVAL_MS = Math.floor(1000 / MSGS_PER_SEC);

const PAYLOAD_SIZE = parseInt(__ENV.PAYLOAD_SIZE || '32', 10);

const e2eLatency = new Trend('ws_e2e_latency_ms', true);
const sent = new Counter('ws_sent');
const received = new Counter('ws_received');
const parsed = new Rate('ws_parsed_rate');
const connectOk = new Rate('ws_connect_ok');
const rateLimited = new Counter('ws_rate_limited');
const wsErrors = new Counter('ws_errors');

function filler(size) {
  if (size <= 0) return '';
  return 'x'.repeat(size);
}

// --- Load pairs (init stage) ---
let PAIRS = null;
try {
  PAIRS = JSON.parse(open('ws_pairs.json'));
} catch (e) {
  PAIRS = null;
}

function singlePairFromEnv() {
  if (__ENV.SENDER_TOKEN && __ENV.RECEIVER_TOKEN && __ENV.ROOM_ID) {
    return [{
      roomId: String(__ENV.ROOM_ID),
      senderToken: String(__ENV.SENDER_TOKEN),
      receiverToken: String(__ENV.RECEIVER_TOKEN),
    }];
  }
  return null;
}

function pairs() {
  const envPair = singlePairFromEnv();
  if (envPair) return envPair;
  if (PAIRS && Array.isArray(PAIRS) && PAIRS.length > 0) return PAIRS;
  throw new Error('No pairs provided. Create ws_pairs.json or pass SENDER_TOKEN/RECEIVER_TOKEN/ROOM_ID env vars.');
}

function pickForVU(list) {
  return list[(__VU - 1) % list.length];
}

// --- k6 scenarios ---
export const options = {
  scenarios: {
    receivers: {
      executor: 'constant-vus',
      vus: RECEIVER_VUS,
      duration: DURATION,
      exec: 'receiver',
      startTime: '0s',
    },
    senders: {
      executor: 'constant-vus',
      vus: SENDER_VUS,
      duration: DURATION,
      exec: 'sender',
      startTime: WARMUP, // give receivers time to connect
    },
  },
  thresholds: {
    ws_connect_ok: ['rate>0.99'],
    ws_parsed_rate: ['rate>0.95'],
    ws_e2e_latency_ms: ['p(95)<300'], // adjust
  },
};

// --- Receiver VU ---
export function receiver() {
  const p = pickForVU(pairs());
  const url = `${WS_BASE_URL}/ws/chat?token=${p.receiverToken}`;

  const res = ws.connect(url, {}, function (socket) {
    socket.on('open', function () {
      connectOk.add(true);
    });

    socket.on('message', function (data) {
      try {
        const msg = JSON.parse(data);
        if (msg.type === 'ERROR') return;

        const content = msg.content || '';
        if (typeof content !== 'string') return;

        // Parse "k6ts=<epoch_ms>|"
        const m = content.match(/k6ts=(\d+)\|/);
        if (!m) {
          parsed.add(false);
          return;
        }
        const ts = parseInt(m[1], 10);
        const dt = Date.now() - ts;

        e2eLatency.add(dt);
        received.add(1);
        parsed.add(true);
      } catch (e) {
        parsed.add(false);
      }
    });

    socket.on('error', function () {
      wsErrors.add(1);
    });

    // keep alive until scenario duration ends:
    // k6 will end the VU; still, set a close timeout to be safe
    socket.setTimeout(function () {
      socket.close();
    }, 60 * 60 * 1000); // large, scenario will end earlier
  });

  check(res, { 'receiver status 101': (r) => r && r.status === 101 });
  sleep(1);
}

// --- Sender VU ---
export function sender() {
  const p = pickForVU(pairs());
  const url = `${WS_BASE_URL}/ws/chat?token=${p.senderToken}`;

  const res = ws.connect(url, {}, function (socket) {
    socket.on('open', function () {
      connectOk.add(true);

      socket.setInterval(function () {
        const now = Date.now();
        const msgId = `${__VU}-${now}-${Math.random().toString(16).slice(2)}`;
        const payload = {
          type: 'CHAT',
          roomId: String(p.roomId),
          sender: '', // server overwrites with verified userId
          content: `k6ts=${now}|k6id=${msgId}|hello ${filler(PAYLOAD_SIZE)}`,
        };

        socket.send(JSON.stringify(payload));
        sent.add(1);
      }, SEND_INTERVAL_MS);
    });

    socket.on('message', function (data) {
      // Sender usually receives only ERROR (rate limit) in your design
      try {
        const msg = JSON.parse(data);
        if (msg.type === 'ERROR') {
          rateLimited.add(1);
        }
      } catch (e) {}
    });

    socket.on('error', function () {
      wsErrors.add(1);
    });

    socket.setTimeout(function () {
      socket.close();
    }, 60 * 60 * 1000);
  });

  check(res, { 'sender status 101': (r) => r && r.status === 101 });
  sleep(1);
}

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

// Duration semantics (IMPORTANT):
// - DURATION (or SEND_DURATION) controls how long senders actively send messages.
// - Receivers stay connected for: WARMUP + SEND_DURATION + COOLDOWN
//   so that late-delivered messages are still observed (prevents sent>received artifacts).
//
// You can keep using -e DURATION=1m (backward compatible).
const SEND_DURATION = __ENV.SEND_DURATION || __ENV.DURATION || '2m';
const WARMUP = __ENV.WARMUP || '5s';     // receivers start first, senders start after WARMUP
const COOLDOWN = __ENV.COOLDOWN || '10s'; // receivers keep listening after senders stop

function parseK6DurationToMs(s) {
  // supports: 1500ms, 5s, 2m, 1h, and combined like 1m30s
  if (!s) return 0;
  const str = String(s).trim();
  const re = /([0-9]+(?:\.[0-9]+)?)(ms|s|m|h)/g;
  let total = 0;
  let m;
  while ((m = re.exec(str)) !== null) {
    const v = parseFloat(m[1]);
    const unit = m[2];
    if (unit === 'ms') total += v;
    else if (unit === 's') total += v * 1000;
    else if (unit === 'm') total += v * 60 * 1000;
    else if (unit === 'h') total += v * 60 * 60 * 1000;
  }
  return Math.floor(total);
}

function msToK6Duration(ms) {
  // use ms precision to avoid rounding drift
  return `${Math.max(0, Math.floor(ms))}ms`;
}

const SEND_DURATION_MS = parseK6DurationToMs(SEND_DURATION);
const WARMUP_MS = parseK6DurationToMs(WARMUP);
const COOLDOWN_MS = parseK6DurationToMs(COOLDOWN);

const RECEIVER_OPEN_MS = WARMUP_MS + SEND_DURATION_MS + COOLDOWN_MS;

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
      // 1 iteration per VU; we close the socket ourselves after RECEIVER_OPEN_MS
      executor: 'per-vu-iterations',
      vus: RECEIVER_VUS,
      iterations: 1,
      maxDuration: msToK6Duration(RECEIVER_OPEN_MS + 5000),
      exec: 'receiver',
      startTime: '0s',
    },
    senders: {
      // 1 iteration per VU; we close the socket ourselves after SEND_DURATION_MS
      executor: 'per-vu-iterations',
      vus: SENDER_VUS,
      iterations: 1,
      maxDuration: msToK6Duration(SEND_DURATION_MS + 5000),
      exec: 'sender',
      startTime: WARMUP, // give receivers time to connect
    },
  },
  thresholds: {
    ws_connect_ok: ['rate>0.99'],
    ws_parsed_rate: ['rate>0.95'],
    ws_e2e_latency_ms: ['p(95)<300'], // adjust if needed
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

    // Keep receiver alive long enough to observe late-delivered messages
    // (WARMUP + SEND_DURATION + COOLDOWN), then close gracefully so the iteration finishes.
    socket.setTimeout(function () {
      socket.close();
    }, RECEIVER_OPEN_MS);
  });

  check(res, { 'receiver status 101': (r) => r && r.status === 101 });
  if (!res || res.status !== 101) {
    connectOk.add(false);
  }
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

    // Stop sending after SEND_DURATION_MS and close gracefully.
    socket.setTimeout(function () {
      socket.close();
    }, SEND_DURATION_MS + 5);
  });

  check(res, { 'sender status 101': (r) => r && r.status === 101 });
  if (!res || res.status !== 101) {
    connectOk.add(false);
  }
  sleep(1);
}

# SolChat Backend
**WebSocket + Kafka + Redis 기반 실시간 채팅 서버 (포트폴리오 프로젝트)**

> 목표: “실시간 채팅”을 구현하면서 **확장성/정합성/동시성/트래픽 제어/성능 검증**을 한 번에 경험할 수 있는 백엔드 시스템을 설계·구현했습니다.  
> 단일 서버 WebSocket의 한계를 넘어 **Kafka 기반 메시지 파이프라인**, **Redis 세션/메타데이터**, **Lua Script 원자 업데이트**, **Write-behind 배치 동기화**, **Redis 기반 Token Bucket Rate Limit**까지 포함합니다.

---

## 핵심 기능
- **회원가입/로그인**
  - 비밀번호 암호화 후 저장
  - 로그인 시 JWT 발급
- **채팅**
  - WebSocket 실시간 메시지 송수신 (`/ws/chat?token=...`)
  - **1:1 채팅방 생성**
    - `chat_key` 정규화 + **UNIQUE 제약조건**으로 중복 생성 방지
  - **그룹 채팅방 생성**
    - 멤버 구성이 같은 기존 방 조회(옵션), 강제 생성 지원
  - 채팅방 목록 조회
    - 참여정보/미읽음 수/마지막 메시지(Write-behind 기반) 조합
  - 채팅방 메시지 조회
    - Paging(Slice), 최신순(내림차순)
  - 메시지 읽음 처리 / 미읽음 개수 계산
- **친구**
  - 친구 목록 조회 / 단방향 친구 추가
- **트래픽 제어**
  - Redis 기반 Token Bucket Rate Limit (Bucket4j + Redis)

---

## 기술 스택
- **Java 17**, **Spring Boot 3.x**
- Spring Web / Spring Security / Spring WebSocket
- **MySQL 8** (JPA/Hibernate + JdbcTemplate Batch Update)
- **Redis** (세션/메타데이터/읽음 위치/Rate Limit 저장소)
- **Kafka** (메시지 브로커, Producer/Consumer)
- **k6** (부하 테스트)

---

## 아키텍처
```mermaid
flowchart LR
  C[Client] -->|REST| APP[SolChat API Server]
  C -->|WebSocket /ws/chat?token=JWT| APP

  APP -->|write| DB[(MySQL)]
  APP -->|Lua HSET/SADD, TTL| R[(Redis)]
  APP -->|publish key=roomId| K[(Kafka topic: chat-messages)]

  K -->|consume| CONS[Kafka Consumer]
  CONS -->|deliver| APP

  APP -->|online session? / user->server| R
  APP -->|internal relay (X-Internal-Secret)| APP2[Other instance]
```

---

## 핵심 설계 포인트

### 1) 메시지 전송 파이프라인 (WS → DB → Redis → Kafka → Consumer → WS)
1. **Handshake 단계**에서 JWT 검증 → `userId`를 WebSocket session attribute에 저장
2. `handleTextMessage`에서
   - **Rate Limit**(userId 기준) 적용
   - **IDOR 방지**: 해당 room 참여자 여부 검증
   - Snowflake 기반 메시지 ID 발급 → **MySQL 저장**
   - **Redis Lua Script**로 chat_room 메타데이터 갱신(원자성 + 순서 보장)
   - **Kafka publish**
3. Kafka Consumer에서 참여자에게 메시지 배달
   - 같은 인스턴스에 세션이 있으면 **바로 WS 전송**
   - 없으면 Redis `userId → serverId` 매핑을 보고 **내부 relay API 호출**

### 2) Redis Write-behind로 “목록 조회” 성능/DB 부하 제어
- 마지막 메시지/정렬용 메타데이터를 Redis에 즉시 반영 (Lua Script + dirty set)
- Scheduler가 dirty set을 일정 주기로 POP하여 **Batch Update**로 DB 반영  
  → “목록 조회”는 Redis를 우선 조회하여 **실시간성을 유지**하면서, DB 업데이트는 **지연/배치**로 전환

### 3) 읽음 처리 (Read position) 설계
- `/rooms/{roomId}/read` 호출 시
  - Redis Hash(`room_read_pos:{roomId}`)에 유저별 last read position 저장
  - dirty set에 roomId 추가
- Scheduler가 dirty set을 처리하여 chat_participant 테이블에 batch 반영

### 4) Rate Limit (Redis 기반 Token Bucket)
- Bucket4j + Redis ProxyManager로 “토큰 소비” 방식 적용
- 정책은 endpoint별로 key-prefix를 다르게 적용  
  예) `auth:{ip}`, `join:{ip}`, `create_room:{userId}`, `msg:{userId}` 등

---

## ERD (요약)
```mermaid
erDiagram
  USER {
    BIGINT id PK
    VARCHAR username UK
    VARCHAR password
    VARCHAR nickname
  }

  FRIEND {
    BIGINT id PK
    BIGINT user_id FK
    BIGINT friend_user_id FK
    VARCHAR status
    UNIQUE(user_id, friend_user_id)
  }

  CHAT_ROOM {
    BIGINT id PK
    VARCHAR chat_key UK
    VARCHAR name
    VARCHAR type
    BIGINT created_at
    TEXT last_message
    BIGINT last_sent_at
    BIGINT last_message_id
  }

  CHAT_PARTICIPANT {
    BIGINT id PK
    BIGINT chat_room_id FK
    BIGINT user_id FK
    BIGINT last_read_message_id
    UNIQUE(chat_room_id, user_id)
  }

  CHAT_MESSAGE {
    BIGINT id PK
    BIGINT room_id FK
    BIGINT sender_id FK
    TEXT content
    VARCHAR type
    BIGINT sent_at
  }

  USER ||--o{ FRIEND : "user_id"
  USER ||--o{ FRIEND : "friend_user_id"
  CHAT_ROOM ||--o{ CHAT_PARTICIPANT : has
  USER ||--o{ CHAT_PARTICIPANT : joins
  CHAT_ROOM ||--o{ CHAT_MESSAGE : has
  USER ||--o{ CHAT_MESSAGE : sends"
```

> 1:1 중복 방 생성 방지: `chat_room.chat_key UNIQUE`  
> 중복 참여 방지: `chat_participant(chat_room_id, user_id) UNIQUE`  
> 중복 친구 추가 방지: `friend(user_id, friend_user_id) UNIQUE`

---

## API

### REST
- Auth
  - `POST /api/auth/join`
  - `POST /api/auth/login`
- Chat
  - `POST /api/chat/room` (1:1 채팅방 생성)
  - `POST /api/chat/group` (그룹 채팅방 생성)
  - `GET  /api/chat/rooms` (내 채팅방 목록)
  - `GET  /api/chat/rooms/{roomId}/messages` (특정 방 메시지 조회)
  - `POST /api/chat/rooms/{roomId}/read` (읽음 처리)
  - `POST /api/chat/heartbeat` (세션 TTL 연장)
- Friends
  - `GET  /api/friends`
  - `POST /api/friends`

### WebSocket
- Connect: `ws://localhost:8080/ws/chat?token=<JWT>`
- Send JSON example:
```json
{
  "type": "CHAT",
  "roomId": "2",
  "sender": "",
  "content": "hello"
}
```
> `sender`는 서버가 **세션의 userId로 overwrite**합니다.

### Internal (멀티 인스턴스 relay)
- `POST /internal/chat/relay`
  - Header: `X-Internal-Secret: <INTERNAL_SECRET>`
  - Body: `{ targetUserId, message }`

---

## 로컬 실행 방법

### 0) 준비물
- Java 17
- Docker / Docker Compose

### 1) 환경변수 설정
```bash
cp .env.example .env
```

`.env`에 아래 값을 채웁니다.
- `DB_USERNAME`
- `DB_PASSWORD`
- `DB_ROOT_PASSWORD`
- `JWT_SECRET`
- `INTERNAL_SECRET`

> ✅ **공개 저장소에는 `.env`를 커밋하지 마세요.**

### 2) 인프라 실행 (Redis / MySQL / Kafka / Kafka UI)
```bash
docker compose up -d
```

Kafka UI: `http://localhost:8090`

### 3) 애플리케이션 실행
```bash
./mvnw spring-boot:run
```

---

## 테스트 방법

### A) REST 기능 테스트 (예: curl)
1) 회원가입
```bash
curl -X POST http://localhost:8080/api/auth/join \
  -H "Content-Type: application/json" \
  -d '{"username":"user01","password":"Pass1234!","nickname":"User01"}'
```

2) 로그인(JWT 받기)
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user01","password":"Pass1234!"}' | jq -r .token)
echo $TOKEN
```

3) 1:1 채팅방 생성
```bash
curl -X POST http://localhost:8080/api/chat/room \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"targetUserId": 1000000001001}'
```

### B) WebSocket 수동 테스트
브라우저 기본 WebSocket API는 헤더를 넣기 어렵기 때문에 **쿼리 파라미터로 토큰을 전달**합니다.

- 연결: `ws://localhost:8080/ws/chat?token=<JWT>`
- 보내는 데이터: 위 JSON 예시

---

## 부하 테스트 (k6)

> 부하 테스트 스크립트/시드 데이터는 `solchat_perf_assets/`에 있습니다.

### 1) (권장) 테스트용 데이터 시딩
MySQL 컨테이너에 seed SQL을 주입합니다.

```bash
docker exec -i mysql mysql -uroot -p${DB_ROOT_PASSWORD} chat_db < solchat_perf_assets/rooms/seed_loadtest.sql
```

### 2) REST Read Path (rooms + messages)
```bash
k6 run --summary-export summary_50.json \
  -e BASE_URL=http://localhost:8080 \
  -e TOKEN="$TOKEN" \
  -e RATE=100 \
  -e DURATION=3m \
  solchat_perf_assets/rooms/k6_rooms_messages.js
```

### 3) WS + Kafka E2E Latency
1) `ws_pairs.json` 생성(토큰 포함 파일이므로 **커밋 금지**)
```bash
cd solchat_perf_assets/ws
python3 generate_1on1_pairs.py
```

2) 실행
```bash
k6 run --summary-export ws_summary_1on1.json \
  -e WS_BASE_URL=ws://localhost:8080 \
  -e RECEIVER_VUS=50 \
  -e SENDER_VUS=50 \
  -e SEND_DURATION=1m \
  -e WARMUP=5s \
  -e COOLDOWN=10s \
  -e MSGS_PER_SEC=2 \
  k6_ws_chat_e2e_aligned.js
```

---

## 성능 결과 (로컬 Docker 환경 측정 예시)
### REST: 채팅방 목록/메시지 조회 (Read API)
- 100 RPS (constant-arrival-rate), 3분
- `rooms` p95 ≈ **67ms**
- `messages` p95 ≈ **10ms**
- HTTP error 0% (check 기준)

### WS: 1:1 일반 채팅 (WS → Kafka → Consumer → WS)
- 50 sender + 50 receiver (총 100 sessions), 1분 전송
- E2E latency p95 ≈ **243ms** (k6 timestamp 기반)

> 📌 “정확한 수치/재현 방법”을 README에 함께 남겨두는 것을 목표로 했습니다.  
> 운영 환경(네트워크/서버 스펙/파티션 수/DB 튜닝)에 따라 결과는 달라질 수 있습니다.

---

## 공개 저장소 정리 체크리스트 (중요)
GitHub에 “포트폴리오”로 올릴 때 아래 파일/폴더는 제외를 권장합니다.
- `.env` (시크릿 포함)
- `solchat_perf_assets/ws/ws_pairs.json` (JWT 포함)
- `target/`, `.idea/`, `.DS_Store` 등 빌드/IDE 산출물

예시 `.gitignore`:
```gitignore
.env
**/ws_pairs.json
target/
.idea/
.DS_Store
```

---

## 앞으로의 개선 아이디어
- Kafka topic **partition 수 증가** + consumer concurrency 튜닝
- 그룹 채팅의 participant 조회/전송 최적화(캐시/배치/streaming)
- 관측성(메트릭/트레이싱) 추가: WS E2E, Kafka lag, DB slow query
- 장애 대응: relay 실패 시 재시도/서킷브레이커/푸시 알림 연계

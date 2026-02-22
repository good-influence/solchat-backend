import requests
import json
import time

# --- 설정값 ---
API_URL = "http://localhost:8080"  
LOGIN_ENDPOINT = f"{API_URL}/api/auth/login"
ROOM_ENDPOINT = f"{API_URL}/api/chat/room"
PASSWORD = "Pass1234!"
USER_BASE_ID = 1000000001000  # SQL Seed에 설정된 유저 ID 시작 번호
NUM_PAIRS = 50  

def get_token(username, password):
    payload = {"username": username, "password": password}
    while True:
        try:
            response = requests.post(LOGIN_ENDPOINT, json=payload)
            if response.status_code == 429:
                print(f"   ⚠️ 로그인 Rate Limit 발동! ({username}) - 3초 대기...")
                time.sleep(3)
                continue
            response.raise_for_status()
            return response.json().get("token") 
        except Exception as e:
            print(f"로그인 실패 ({username}): {e}")
            return None

def create_room(token, target_user_id):
    headers = {"Authorization": f"Bearer {token}"}
    payload = {"targetUserId": target_user_id}
    while True:
        try:
            response = requests.post(ROOM_ENDPOINT, json=payload, headers=headers)
            if response.status_code == 429:
                print(f"   ⚠️ 채팅방 생성 Rate Limit 발동! - 3초 대기...")
                time.sleep(3)
                continue
            response.raise_for_status()
            # 서버가 반환한 ChatRoom 객체에서 id를 추출 (응답 JSON 구조에 맞게 수정 필요할 수 있음)
            return response.json().get("id")
        except Exception as e:
            print(f"채팅방 생성 실패 (대상 유저 {target_user_id}): {e}")
            return None

def main():
    pairs = []
    print("🚀 1:1 현실형 채팅방 생성 및 토큰 발급 시작...")
    
    for i in range(1, NUM_PAIRS + 1):
        # 쌍을 겹치지 않게 하기 위해 인덱스를 2칸씩 뜁니다.
        # i=1 -> sender: user1, receiver: user2
        # i=2 -> sender: user3, receiver: user4
        sender_idx = 2 * i - 1
        receiver_idx = 2 * i
        
        sender_user = f"user{str(sender_idx).zfill(6)}"
        receiver_user = f"user{str(receiver_idx).zfill(6)}"
        receiver_user_id = USER_BASE_ID + receiver_idx

        # 1. 두 유저의 토큰 발급
        sender_token = get_token(sender_user, PASSWORD)
        receiver_token = get_token(receiver_user, PASSWORD)
        
        if sender_token and receiver_token:
            # 2. Sender가 Receiver를 대상으로 1:1 채팅방 생성 API 호출!
            room_id = create_room(sender_token, receiver_user_id)
            
            if room_id:
                pairs.append({
                    "roomId": str(room_id),
                    "senderToken": sender_token,
                    "receiverToken": receiver_token
                })
                print(f"[{i}/{NUM_PAIRS}] 1:1 방 생성 완료! (방 번호: {room_id}, {sender_user} ↔ {receiver_user})")
        
        time.sleep(0.3)  # 서버에 무리가 가지 않게 약간의 딜레이

    # 3. JSON 파일로 예쁘게 저장
    with open("ws_pairs.json", "w", encoding="utf-8") as f:
        json.dump(pairs, f, indent=2)
    
    print(f"\n🎉 'ws_pairs.json' 파일이 생성되었습니다. (총 {len(pairs)}쌍)")

if __name__ == "__main__":
    main()
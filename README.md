🔍 WareLens - Backend Core
"사용자의 이미지 데이터가 백엔드를 거쳐 AI 모델로 전달되고, 팀원들과 합의된 규격에 따라 추천 결과를 반환하는 견고한 데이터 파이프라인을 구축한다."

프론트엔드(React)의 단일 요청을 받아 2대의 비동기 AI 서버(CLIP, MediaPipe)를 백엔드 중심에서 유기적으로 조율하고, 순차적 파이프라인 자동화 루프를 통해 실시간 가상 착장(Try-On) 결과까지 한 바구니에 가공하여 일괄 반환하는 Spring Boot 기반의 고성능 API 게이트웨이 서버입니다.

🛠 1. 담당 분야 및 기술 스택
Role: Backend 서버 아키텍처 설계, API 게이트웨이 및 데이터 파이프라인 오케스트레이션

Language: Java 21 (OpenJDK / Zulu 21)

Framework & Library: Spring Boot, Swagger (springdoc-openapi), Jackson ObjectMapper

Core Tech: REST API 설계, 멀티파트 데이터 분기 처리 (Multipart/form-data), 서버 간 통신 제어 (RestTemplate)

🏗 2. 시스템 아키텍처 및 데이터 흐름 (Data Flow)
본 프로젝트는 시스템 간 데이터 무결성을 보장하고 프론트엔드의 스펙 변경을 최소화하기 위해 백엔드가 중심을 잡고 제어하는 순차적 파이프라인 구조로 작동합니다.

[Client (React)]
│
▼ (단일 Multipart FormData: 의류사진들 + 전신사진 + 유저정보 JSON)
[Backend (Spring Boot: 8080)]
│
├─► STEP 1. [CLIP Server (8001)] ──► 스타일 유사도 분석 후 Top-10 추천 의류 파일명 리스트 확보
│
├─► STEP 2. [MediaPipe Server (8002)] ──► 3D 체형 실측 및 전신 사진 인메모리 캐싱 (USER_CACHE)
│
└─► STEP 3. [자동화 가상 피팅 루프] ──► 확보한 Top-5 의류 파일명으로 /api/v1/tryon 5회 연속 순차 호출
│
▼ (의류 추천 메타데이터 + 3D 체형분석 + 5장의 합성 완료된 가상피팅 Base64 이미지 팩)
[Client (React) 결과 페이지 진입 시 완성형 화면으로 즉시 일괄 렌더링]

🤝 3. API 설계 및 규격 합의 (API Contract)
시스템 간 데이터 신뢰성을 보장하기 위해 사전 협의된 절차를 준수하며, 각 AI 서버의 동적 명세서 스펙을 유기적으로 결합했습니다.

데이터 규격 정밀 매핑: CLIP 서버의 recommendations.image_name 구조와 MediaPipe 서버의 data.tryon_image_base64 구조를 백엔드 메모리 상에서 유기적으로 결합했습니다.

에러 분리 및 장애 격리 정책: 가상 피팅 루프 내부에 개별 try-catch 안전 트랩을 구축하여, 특정 의류 연산이 실패(예: 이미지 손상 등)하더라도 전체 시스템이 다운되지 않고 나머지 정상 응답을 보존하여 반환하도록 설계했습니다.

📋 최종 통합 응답 규격 (Confirmed JSON)
{
"status": "SUCCESS",
"clip_recommendations": {
"recommendations": [
{ "rank": 1, "image_name": "15970.jpg", "score": 0.92, "category": "TOP", "color": "NAVY" }
]
},
"body_analysis": {
"status": "SUCCESS",
"data": {
"user_id": "user_a1b2c3d4",
"measurements_cm": { "chest": 102.5, "waist": 84.0 },
"size_analysis": { "recommended_size": "100(L)" }
}
},
"top5_tryon_images": [
{
"garment_name": "15970.jpg",
"tryon_image_base64": "iVBORw0KGgoAAAANSUhEUg..."
}
]
}

🌟 4. 핵심 구현 기능
💾 고속 멀티파트 포크(Fork) 처리 및 로컬 백업
클라이언트로부터 전달받은 단일 FormData 스트림을 수신하여 로컬 스토리지(D:/warelens_uploads/)에 안전하게 복사 및 백업합니다.

단일 요청 바구니를 분기하여 CLIP 전송용 멀티 파일(style_images)과 MediaPipe 전송용 정면 사진(file)으로 각각 재조립하여 데이터 전달 효율성을 극대화했습니다.

🔄 결과 페이지 맞춤형 가상 피팅 자동화 루프 (Automated Try-On Loop)
"사용자가 결과 페이지에 들어가자마자 Top-5 의류가 내 몸에 입혀진 상태로 즉시 리스트업되는 기획"을 구현하기 위해 백엔드 단에서 가상 착장 프로세스를 자동화했습니다.

MediaPipe 서버의 세션 캐시 메커니즘을 연계 활용하여, 백엔드가 내부 루프를 통해 /api/v1/tryon`을 연속 호출하고 합성 완료된 Base64 인코딩 이미지 스트림 5장을 일괄 수집 및 합성합니다.

🛡 안정성 및 문서화
유효성 검증 및 예외 필터: AI 서버 통신 장애 및 런타임 연산 오류에 대응하는 방어 코드를 구축했습니다.

Swagger 연동: springdoc-openapi를 통한 API 명세서 자동 생성으로 프론트엔드 및 AI 팀과의 협업 효율성을 상시 유지합니다.

📅 5. 개발 로드맵
📌 1단계: 기반 구축 (완료)
Java 21 Environment 및 Spring Boot 프로젝트 아키텍처 구성

REST API 기본 구조 설계 및 파일 업로드 멀티파트 인프라 구축

📌 2단계: 연동 및 파이프라인 고도화 (완료)
CLIP(8001) 서버 연동을 통한 스타일 추천 데이터 파싱 완료

MediaPipe(8002) 3D 체형분석 서버 연동 및 런타임 추론 에러 방어 코드 최적화

[고도화] 추천 의류 파일명 기반 가상 피팅 자동화 루프 및 복합 데이터 합성 연동 완료

📌 3단계: 통합 및 최적화 (~07-15)
전체 서비스 시퀀스 동기화 테스트 및 대기 시간(Latency) 튜닝

예외 상황 스트레스 테스트 및 최종 배포 준비

🚀 6. 향후 확장 계획 (현실적 고도화 및 안정성 확보)
본 프로젝트는 프로토타입 단계를 넘어, 실제 상용 서비스 전환 시 발생할 수 있는 데이터 병목과 보안 취약점을 해결하기 위해 다음과 같은 단계별 고도화 아키텍처를 설계에 반영했습니다.

🔐 1) 세션 독립성 보장 및 사용자 식별을 위한 고성능 토큰 기반 인증 (JWT)
현실적 필요성: 사용자가 동일 브라우저에서 여러 창을 띄우거나 다른 기기로 동시 접근할 때, 세션 간 데이터 오염(다른 유저의 피팅 결과가 섞이는 현상)을 원천 차단해야 합니다.

실현 방안: 가볍고 독립적인 구조인 JWT(Json Web Token) 시스템을 도입합니다. 사용자가 로그인하면 고유한 암호화 토큰이 발행되며, 이 토큰 내부의 유저 식별자를 통해 MediaPipe 서버의 캐시 리소스(USER_CACHE)를 정확히 1:1 매핑하여 격리합니다. 별도의 무거운 세션 서버 없이도 다중 기기 환경에서 완벽한 독립 세션을 유지합니다.

💾 2) 영속성 데이터 관리 및 히스토리 추적을 위한 RDB(MySQL) 구축
현실적 필요성: 현재 임시 가동 중인 로컬 파일 스토리지 백업 방식에서 벗어나, 상품의 상세 메타데이터와 사용자의 과거 스타일 추천 이력을 영구적으로 보존해야 합니다.

실현 방안: Spring Boot와 궁합이 좋은 Spring Data JPA 및 MySQL을 연동합니다. CLIP이 분석한 의류 고유 번호와 사용자의 3D 신체 실측 데이터를 데이터베이스 테이블에 정형화하여 저장함으로써, 추후 "내 신체 변화 그래프"나 "과거 추천 옷 다시 보기" 같은 사용자 맞춤형 이력 관리 기능을 실현합니다.

⚡ 3) 대량 요청 환경에서의 대기 시간 최적화 (Async 파이프라인 전환)
현실적 필요성: 딥러닝 기반의 가상 피팅 모델(Diffusion 등)은 연산이 무겁기 때문에, 동시 사용자가 몰릴 경우 백엔드가 멈추거나 응답 지연(Latency)이 심해질 수 있습니다.

실현 방안: 자바의 CompletableFuture를 활용하여 기존의 동기식(Sync) 루프 구조를 비동기(Async) 논블로킹 파이프라인으로 전환합니다. 백엔드가 AI 서버에 피팅 요청을 던져두고 대기하는 동안 다른 사용자의 요청을 끊임없이 받아낼 수 있도록 서버 스레드 효율성을 극대화합니다.
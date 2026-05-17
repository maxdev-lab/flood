# HOU — 침수 위험 경로 안내 앱

실시간 침수 위험 데이터를 지도에 히트맵으로 표시하고, 위험 구역을 피한 우회 경로를 안내하는 Android 내비게이션 앱입니다.

---

## 주요 기능

| 기능 | 설명 |
|------|------|
| 침수 히트맵 | 위험도별 색상(노랑→주황→빨강)으로 침수 위험 구역을 지도에 표시 |
| 침수 우회 경로 | 위험 구역을 측면으로 우회하는 앵커포인트 분할 경로 탐색 |
| 우회 불가 경고 | 우회가 어려운 경우 상단 배너로 이동 주의 안내 |
| TTS 음성 안내 | 회전 3단계 안내(FAR·MID·NEAR) 및 직진 피드백 |
| 가상 주행 | 실제 이동 없이 경로를 따라 안내를 테스트하는 모드 |
| 경로 이탈 재탐색 | 25m 이상 벗어나면 자동으로 침수 우회 경로 재탐색 |

---

## 프로젝트 구조

```
app/src/main/java/com/example/hou/
│
├── MainActivity.kt          # 진입점. 각 매니저 초기화 및 Compose UI 상태 관리
├── NavScreen.kt             # 메인 지도 화면 Composable (상태 표시만 담당)
├── SearchScreen.kt          # 출발지·도착지 검색 화면 Composable
│
├── flood.kt                 # 침수 데이터 모델, 더미 데이터, 히트맵 렌더링
├── FloodRouter.kt           # 침수 우회 앵커포인트 계산 엔진
│
├── RouteManager.kt          # POI 검색, TMap 경로 API 호출, 경로 렌더링
├── NavigationManager.kt     # 회전 안내, TTS 제어, 경로 이탈 감지
├── LocationSensorManager.kt # GPS 위치 업데이트, 방향 센서, 내 위치 마커
├── SearchHistoryManager.kt  # 검색 기록 저장/조회 (SharedPreferences)
│
└── data/
    ├── model/Models.kt          # API 응답 데이터 클래스
    └── network/
        ├── RetrofitClient.kt    # Retrofit 싱글턴
        └── TMapApiService.kt    # TMap REST API 인터페이스
```

---

## 아키텍처

```
MainActivity
  ├── mutableStateOf(...)     ← Compose UI 상태 (단방향 데이터 흐름)
  │
  ├── LocationSensorManager   ← GPS + 방향 센서
  ├── NavigationManager       ← 안내 로직 + TTS
  ├── RouteManager            ← 경로 탐색 + 렌더링
  │     └── FloodRouter       ← 우회 앵커 계산
  ├── FloodHeatmapManager     ← 히트맵 폴리곤 관리
  └── SearchHistoryManager    ← 검색 기록
```

모든 UI 상태는 `MainActivity`의 `mutableStateOf` 변수로 관리하며, Composable은 상태를 받아 표시만 합니다.

---

## 침수 우회 경로 탐색

### 흐름

```
findFloodAvoidRoute(start, end)
  │
  ├─ isDestinationInDanger()  → true  → UNAVOIDABLE 배너 + 일반 경로
  ├─ isBypassImpossible()     → true  → UNAVOIDABLE 배너 + 일반 경로
  │
  ├─ findBypassAnchors()      → []    → 위험 없음, 일반 경로
  │
  └─ findBypassAnchors()      → [앵커]
        │
        ├─ start → 앵커   (TMap API 병렬 호출)
        └─ 앵커  → end
              │
              └─ stitchMultiAndApply()
                    ├─ isRouteInDanger() → true  → UNAVOIDABLE 배너
                    └─ isRouteInDanger() → false → DETOUR_ACTIVE 배너
```

### 앵커 배치 방식 ("중간점 측면 이탈")

위험 클러스터의 AABB 중심을 출발~도착 직선에 투영한 뒤, 그 지점에서 경로에 수직 방향으로 `(블록 수직폭/2 + 200m)` 만큼 좌·우로 앵커를 만들고 거리가 짧은 쪽을 선택합니다. 이 방식은 앵커가 블록 측면에 위치하므로, 각 구간(start→앵커, 앵커→end)의 TMap 최단경로가 자연스럽게 블록 옆으로 흐르도록 유도합니다.

> **현재 한계:** 블록이 크거나 주변 도로망이 단순한 경우, TMap이 계산한 실제 도로 경로가 여전히 위험구역을 통과할 수 있습니다. 이 경우 UNAVOIDABLE 배너로 처리됩니다.

---

## 침수 데이터

### 현재 구조

`flood.kt`의 `FloodDataset`이 `FloodCell` 목록을 담습니다.

```kotlin
data class FloodCell(
    val centerLat: Double,   // 셀 중심 위도 (WGS84)
    val centerLon: Double,   // 셀 중심 경도 (WGS84)
    val riskLevel: Int       // 위험도 1~100
)
```

셀 크기는 약 40m × 40m 그리드입니다.

### 더미 데이터 (현재 적용)

강남역·신논현역 일대(2010·2022년 실제 침수 기록 참고)를 기반으로 핫스팟 16개를 설정합니다.

- 강남역 사거리, 9~11번 출구 골목: 고위험 (riskLevel 70~100)
- 서초대로 선형: 중위험 (hline 타원 패턴)
- 신논현역, 역삼로 교차점: 중위험 산발
- 논현로 골목: 저위험 산발

### 서버 연동 방법

`MainActivity.loadFloodData()`에서 `getMockFloodData()` 호출을 Retrofit 호출로 교체하고, 결과를 `applyFloodDataset(dataset)`에 전달하면 됩니다.

```kotlin
private fun loadFloodData() {
    lifecycleScope.launch {
        val dataset = RetrofitClient.floodService.getFloodData().body()!!
        applyFloodDataset(dataset)
    }
}
```

---

## 사용 기술

- **지도 SDK:** TMap Android SDK 3.5, VSM TMap SDK v2 2.0.0
- **UI:** Jetpack Compose + Material3
- **위치:** Google Play Services FusedLocationProvider
- **네트워크:** Retrofit2 + Gson
- **음성:** Android TextToSpeech (한국어)
- **비동기:** Kotlin Coroutines

---

## 설정

`local.properties` 또는 `BuildConfig`에 TMap API 키를 설정합니다.

```
TMAP_APP_KEY=your_api_key_here
```

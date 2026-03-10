from pydantic import BaseModel, Field
from typing import List, Optional

# 공통: 위경도 좌표 스키마
class Coordinate(BaseModel):
    lat: float
    lng: float

# ==========================================
# 1. 사용자 위치 업데이트 API 스키마
# ==========================================
class LocationUpdateRequest(BaseModel):
    current_lat: float = Field(..., description="현재 위도")
    current_lng: float = Field(..., description="현재 경도")
    fcm_token: Optional[str] = Field(None, description="FCM 푸시 토큰 (갱신 시 전달)")

class LocationUpdateResponse(BaseModel):
    in_danger_zone: bool = Field(..., description="침수 위험 구역 진입 여부")
    action_required: str = Field(..., description="필요한 액션 (예: REROUTE, NONE)")

# ==========================================
# 2. 안전 경로 탐색 API 스키마
# ==========================================
class SafeRouteResponse(BaseModel):
    is_rerouted: bool = Field(..., description="위험 구역을 피해 우회했는지 여부")
    total_distance_m: int = Field(..., description="총 이동 거리(미터)")
    estimated_time_min: int = Field(..., description="예상 소요 시간(분)")
    warning_message: Optional[str] = Field(None, description="위험 관련 경고 메시지")
    path: List[Coordinate] = Field(..., description="경로를 그리기 위한 좌표 배열")

# ==========================================
# 3. 실시간 침수 히트맵 API 스키마
# ==========================================
class GridRiskData(BaseModel):
    grid_id: int
    risk_score: float = Field(..., description="최종 침수 위험도 (0.0 ~ 1.0)")
    risk_level: str = Field(..., description="위험 등급 (SAFE, WARNING, DANGER)")
    polygon: List[Coordinate] = Field(..., description="40m x 40m 사각형 꼭짓점 좌표 4개")

class HeatmapResponse(BaseModel):
    grids: List[GridRiskData]
from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session
from typing import Optional

from app import schemas, crud
from app.database import get_db
from app.services.routing import get_safe_route

router = APIRouter(prefix="/api/v1", tags=["Client API"])

@router.post("/users/{user_id}/location", response_model=schemas.LocationUpdateResponse)
def update_user_location(
    user_id: str, 
    location_data: schemas.LocationUpdateRequest, 
    db: Session = Depends(get_db)
):
    """
    사용자의 실시간 위치를 업데이트하고, 침수 위험 반경 내에 있는지 확인합니다.
    """
    # TODO: db에서 유저 위치 업데이트 및 반경 내 위험 격자 확인 로직 연동
    
    return schemas.LocationUpdateResponse(
        in_danger_zone=False,
        action_required="NONE"
    )

@router.get("/routes/safe", response_model=schemas.SafeRouteResponse)
def get_safe_route(
    start_lat: float = Query(..., description="출발지 위도"),
    start_lng: float = Query(..., description="출발지 경도"),
    end_lat: float = Query(..., description="목적지 위도"),
    end_lng: float = Query(..., description="목적지 경도"),
    db: Session = Depends(get_db)
):
    """
    출발지와 목적지를 기반으로 침수 위험을 회피하는 안전 경로를 반환합니다.
    """
    # TODO: app/services/routing.py 의 OSMnx + NetworkX 경로 탐색 로직 연동
    
    dummy_path = [
        {"lat": start_lat, "lng": start_lng},
        {"lat": end_lat, "lng": end_lng}
    ]
    
    return schemas.SafeRouteResponse(
        is_rerouted=False,
        total_distance_m=1250,
        estimated_time_min=15,
        path=dummy_path
    )

@router.get("/risk/heatmap", response_model=schemas.HeatmapResponse)
def get_risk_heatmap(
    lat: float = Query(..., description="지도 중심 위도"),
    lng: float = Query(..., description="지도 중심 경도"),
    radius: int = Query(1000, description="검색 반경(m)"),
    db: Session = Depends(get_db)
):
    """
    지도 중심 좌표 반경 내의 격자별 실시간 침수 위험도를 반환합니다.
    """
    # TODO: PostGIS의 ST_DWithin 등을 사용해 반경 내 격자 위험도 조회
    
    return schemas.HeatmapResponse(grids=[])

@router.get("/routes/safe", response_model=schemas.SafeRouteResponse)
def get_safe_route_api( # 함수 이름 변경 (충돌 방지)
    start_lat: float = Query(..., description="출발지 위도"),
    start_lng: float = Query(..., description="출발지 경도"),
    end_lat: float = Query(..., description="목적지 위도"),
    end_lng: float = Query(..., description="목적지 경도"),
    db: Session = Depends(get_db)
):
    """
    출발지와 목적지를 기반으로 침수 위험을 회피하는 안전 경로를 반환합니다.
    """
    # 실제 OSMnx + NetworkX 경로 탐색 엔진 호출
    route_result = get_safe_route(start_lat, start_lng, end_lat, end_lng)
    
    # 에러 발생 시 처리
    if "error" in route_result:
        raise HTTPException(status_code=400, detail=route_result["error"])
        
    # 정상 결과 반환
    return schemas.SafeRouteResponse(
        is_rerouted=route_result["is_rerouted"],
        total_distance_m=route_result["total_distance_m"],
        estimated_time_min=route_result["estimated_time_min"],
        warning_message=route_result["warning_message"],
        path=route_result["path"]
    )

@router.get("/risk/heatmap", response_model=schemas.HeatmapResponse)
def get_risk_heatmap_api(
    lat: float = Query(..., description="지도 중심 위도"),
    lng: float = Query(..., description="지도 중심 경도"),
    radius: int = Query(1000, description="검색 반경(m)"),
    db: Session = Depends(get_db)
):
    """
    지도 중심 좌표 반경 내의 격자별 실시간 침수 위험도를 반환합니다.
    """
    # 1. crud의 PostGIS 반경 검색 함수 호출
    heatmap_data = crud.get_heatmap_data(db, lat=lat, lng=lng, radius_m=radius)
    
    # 2. 클라이언트 규격에 맞춰 응답 반환
    return schemas.HeatmapResponse(grids=heatmap_data)
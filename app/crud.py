from sqlalchemy.orm import Session
from sqlalchemy import func, cast
from geoalchemy2.types import Geography
import pandas as pd
from typing import List

from app import models, schemas

# ==========================================
# 1. 실시간 침수 위험도 DB 일괄 저장 (Bulk Insert)
# ==========================================
def save_realtime_risks(db: Session, risk_df: pd.DataFrame):
    """
    AI 엔진이 계산한 격자별 최종 위험도(DataFrame)를 DB에 일괄 저장합니다.
    (스케줄러의 process_risk_pipeline 실행 직후에 호출됩니다)
    """
    db_records = []
    for _, row in risk_df.iterrows():
        record = models.RealtimeRisk(
            grid_id=int(row['grid_id']),
            rainfall=float(row.get('rainfall', 0.0)),
            sewage_level=float(row.get('sewage_level', 0.0)),
            final_risk_score=float(row['final_risk_score'])
        )
        db_records.append(record)
    
    # 여러 개의 데이터를 한 번에 INSERT 하여 DB 부하 최소화
    db.bulk_save_objects(db_records)
    db.commit()
    print(f"✅ DB에 {len(db_records)}개의 실시간 위험도 데이터 저장 완료!")

# ==========================================
# 2. 반경 내 침수 히트맵 데이터 조회 (PostGIS 공간 연산)
# ==========================================
def get_heatmap_data(db: Session, lat: float, lng: float, radius_m: int):
    """
    주어진 위경도(lat, lng)를 중심으로 radius_m(미터) 반경 내에 있는 
    격자(Grid)들의 최신 침수 위험도 정보를 가져옵니다.
    """
    # 1. PostGIS의 ST_MakePoint로 중심점 생성 (경도, 위도 순서 주의!)
    center_point = func.ST_SetSRID(func.ST_MakePoint(lng, lat), 4326)
    
    # 2. ST_DWithin을 사용하여 반경 검색 
    # (Geography로 형변환(cast)해야 radius_m 단위가 '미터(m)'로 정확히 작동합니다)
    nearby_grids = db.query(models.GridInfo).filter(
        func.ST_DWithin(
            cast(models.GridInfo.geom, Geography), 
            cast(center_point, Geography), 
            radius_m
        )
    ).all()

    results = []
    for grid in nearby_grids:
        # 해당 격자의 가장 최근(최신) 실시간 위험도 기록을 가져옵니다.
        # (실무에서는 복합 인덱스나 Window 함수로 쿼리 최적화를 합니다)
        latest_risk = db.query(models.RealtimeRisk)\
            .filter(models.RealtimeRisk.grid_id == grid.grid_id)\
            .order_by(models.RealtimeRisk.created_at.desc())\
            .first()
            
        if latest_risk:
            # 위험 등급 판단 로직 (AI 엔진과 동일한 기준)
            score = latest_risk.final_risk_score
            level = "DANGER" if score >= 0.7 else "WARNING" if score >= 0.4 else "SAFE"
            
            # PostGIS Geometry를 클라이언트가 그리기 쉽게 좌표 리스트로 변환하는 작업이 필요합니다.
            # (현재는 임시 좌표로 채워둡니다. 향후 ST_AsGeoJSON 등을 활용해 파싱합니다.)
            dummy_polygon = [
                {"lat": lat + 0.0001, "lng": lng - 0.0001},
                {"lat": lat + 0.0001, "lng": lng + 0.0001},
                {"lat": lat - 0.0001, "lng": lng + 0.0001},
                {"lat": lat - 0.0001, "lng": lng - 0.0001}
            ]
            
            results.append({
                "grid_id": grid.grid_id,
                "risk_score": score,
                "risk_level": level,
                "polygon": dummy_polygon
            })
            
    return results
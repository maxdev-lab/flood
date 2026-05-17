"""
app/db/models.py
────────────────
ddc_grid 테이블 ORM 모델
CSV: 정적데이터_수정_.csv (2470행, 25컬럼)
"""
from sqlalchemy import Column, Integer, Float, Boolean, String, Index
from sqlalchemy.orm import DeclarativeBase
from geoalchemy2 import Geometry


class Base(DeclarativeBase):
    pass


class DdcGrid(Base):
    """동두천시 100m x 100m 격자 정적 데이터"""
    __tablename__ = "ddc_grid"

    # ── 식별자 ──────────────────────────────────────
    gid               = Column(Integer, primary_key=True)       # grid_id

    # ── 지형 지표 (원본) ─────────────────────────────
    height_mean       = Column(Float, nullable=False)           # 평균 고도 (m)
    slope_mean        = Column(Float, nullable=False)           # 평균 경사 (°)

    # ── 토지이용 ─────────────────────────────────────
    l3_code           = Column(Integer, nullable=False)         # 토지이용 코드
    l3_name           = Column(String(100), nullable=False)     # 토지이용 명칭
    land_risk         = Column(Float, nullable=False)           # 토지 위험 등급
    part_area         = Column(Float, nullable=False)           # 격자 내 면적 (m²)
    land_risk_value   = Column(Float, nullable=False)           # 토지 위험값 (0~1)

    # ── 침수 이력 ─────────────────────────────────────
    fldlv_freq        = Column(Integer, nullable=False, default=0)   # 침수 빈도 횟수
    flood_level       = Column(Integer, nullable=False, default=0)   # 침수 위험 등급 (0~4)
    f_shim            = Column(Float, nullable=False, default=0.0)   # 침수 심도 (m)
    f_area            = Column(Float, nullable=False, default=0.0)   # 침수 면적 (m²)
    f_yr              = Column(Integer, nullable=False, default=0)   # 침수 발생 연도
    flood_level_score = Column(Float, nullable=False, default=0.0)  # 침수 등급 점수 (0~0.6)

    # ── 도시침수 위험 ─────────────────────────────────
    city_flood_risk   = Column(Float, nullable=False, default=0.0)  # 도시침수 위험도 (0~0.8)
    f_shim_level      = Column(Integer, nullable=False, default=0)  # 침수 심도 등급 (0,1,2,4)
    past_flood_risk   = Column(Float, nullable=False, default=0.0)  # 과거 침수 위험값 (0~0.6)

    # ── 지하역 관련 ───────────────────────────────────
    station_id        = Column(Integer, nullable=False, default=0)
    station_name      = Column(String(100), nullable=False, default="없음")
    exit_no           = Column(Float, nullable=True)                # 출구 번호 (null=지하역 없음)
    freq_score        = Column(Float, nullable=False, default=0.0)  # 이용 빈도 점수 (0~1)
    is_station_zone   = Column(Boolean, nullable=False, default=False)  # 지하역 영향권

    # ── 정규화된 지표 ─────────────────────────────────
    height_norm       = Column(Float, nullable=False)   # 고도 정규화 (낮을수록 위험 = 높은 값)
    slope_norm        = Column(Float, nullable=False)   # 경사 정규화

    # ── 검증용 사전 계산값 ────────────────────────────
    final_score       = Column(Integer, nullable=False, default=0)  # CSV 사전 계산 점수

    # ── 공간 데이터 ──────────────────────────────────
    geom              = Column(
        Geometry("POLYGON", srid=4326),
        nullable=True,  # CSV 적재 시 NULL, 이후 공간 JOIN으로 채움
    )

    __table_args__ = (
        Index("idx_ddc_grid_geom", "geom", postgresql_using="gist"),
    )
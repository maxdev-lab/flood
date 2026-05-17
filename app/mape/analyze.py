"""
app/mape/analyze.py
───────────────────
MAPE-K Analyze 단계: Risk Engine

실제 CSV 컬럼(정적데이터_수정_.csv)을 기반으로 위험 점수 산출
  - 이미 정규화된 컬럼 직접 사용 (height_norm, slope_norm 등)
  - 실시간 데이터(강수량, 하수관 수위)를 승수(multiplier)로 반영

위험 점수 수식:
    base_score  = 100 × Σ(normalized_factor × weight)
    risk_score  = min(100, base_score × multiplier)
    multiplier  = 1.0 + max(rain_factor, sewer_factor)  → 1.0 ~ 2.0
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from app.db.models import DdcGrid


# ── 가중치 (합계 = 1.0) ────────────────────────────────────────
WEIGHTS: dict[str, float] = {
    "height_norm":       0.20,  # 고도 (낮을수록 위험, 역정규화 완료)
    "past_flood_risk":   0.20,  # 과거 침수 이력
    "city_flood_risk":   0.18,  # 도시침수 위험도
    "land_risk_value":   0.15,  # 토지이용 위험값 (불투수면)
    "flood_level_score": 0.12,  # 침수 위험 등급
    "is_station_zone":   0.08,  # 지하역 영향권
    "freq_score":        0.05,  # 지하역 이용 빈도
    "slope_norm":        0.02,  # 경사 (보조)
}

# ── 스케일 (최대값이 1.0이 아닌 컬럼) ────────────────────────────
# CSV 실측값: past_flood_risk 0~0.6, flood_level_score 0~0.6, city_flood_risk 0~0.8
SCALE: dict[str, float] = {
    "past_flood_risk":   0.6,
    "flood_level_score": 0.6,
    "city_flood_risk":   0.8,
}


@dataclass
class RealtimeData:
    """Monitor 단계에서 수집한 실시간 데이터"""
    rainfall_mm:  float = 0.0   # 강수량 (mm/hr)
    sewer_level:  float = 0.0   # 하수관 수위 (0.0~1.0 정규화)


@dataclass
class RiskResult:
    """격자 1개의 위험 점수 산출 결과"""
    gid:          int
    base_score:   float                          # 정적 지표 점수 (0~100)
    risk_score:   float                          # 실시간 반영 최종 점수 (0~100)
    risk_level:   str                            # SAFE / WARNING / DANGER
    is_blocked:   bool                           # 통행 불가 여부
    multiplier:   float                          # 실시간 승수
    detail:       dict = field(default_factory=dict)
    csv_score:    int  = 0                       # CSV 사전 계산값 (검증용)


# ── 내부 헬퍼 ─────────────────────────────────────────────────

def _scale(value: float, col: str) -> float:
    """컬럼별 최대값으로 0~1 변환"""
    max_val = SCALE.get(col, 1.0)
    return max(0.0, min(1.0, value / max_val)) if max_val else 0.0


def _calc_multiplier(rt: Optional[RealtimeData]) -> float:
    """
    실시간 데이터 기반 위험 승수 (1.0 ~ 2.0)
    - 강수량 30mm/hr = 기상청 호우 주의보 수준 → factor 1.0
    - 수위 1.0 = 만수 → factor 1.0
    """
    if rt is None:
        return 1.0
    rain_factor  = min(1.0, rt.rainfall_mm / 30.0)
    sewer_factor = max(0.0, min(1.0, rt.sewer_level))
    return round(1.0 + max(rain_factor, sewer_factor), 4)


def _get_level(score: float, threshold: float) -> str:
    if score >= threshold:
        return "DANGER"
    elif score >= threshold * 0.6:
        return "WARNING"
    return "SAFE"


# ── 핵심 위험 점수 계산 ───────────────────────────────────────

def compute_risk(
    gid:               int,
    height_norm:       float,
    slope_norm:        float,
    land_risk_value:   float,
    flood_level_score: float,
    city_flood_risk:   float,
    past_flood_risk:   float,
    freq_score:        float,
    is_station_zone:   bool,
    realtime:          Optional[RealtimeData] = None,
    blocked_threshold: float = 65.0,
    csv_final_score:   int   = 0,
) -> RiskResult:

    # 1. 각 지표 0~1 정규화
    normalized: dict[str, float] = {
        "height_norm":       max(0.0, min(1.0, height_norm)),
        "slope_norm":        max(0.0, min(1.0, slope_norm)),
        "land_risk_value":   max(0.0, min(1.0, land_risk_value)),
        "flood_level_score": _scale(flood_level_score, "flood_level_score"),
        "city_flood_risk":   _scale(city_flood_risk,   "city_flood_risk"),
        "past_flood_risk":   _scale(past_flood_risk,   "past_flood_risk"),
        "freq_score":        max(0.0, min(1.0, freq_score)),
        "is_station_zone":   1.0 if is_station_zone else 0.0,
    }

    # 2. 가중 합산 → base_score (0~100)
    weighted_sum = sum(normalized[k] * WEIGHTS[k] for k in WEIGHTS)
    base_score   = round(weighted_sum * 100, 2)

    # 3. 실시간 승수 적용 → risk_score
    multiplier = _calc_multiplier(realtime)
    risk_score = round(min(100.0, base_score * multiplier), 2)

    # 4. 지표별 기여도 (디버깅/프론트 시각화용)
    detail = {k: round(normalized[k] * WEIGHTS[k] * 100, 2) for k in WEIGHTS}
    detail["multiplier"] = multiplier

    return RiskResult(
        gid          = gid,
        base_score   = base_score,
        risk_score   = risk_score,
        risk_level   = _get_level(risk_score, blocked_threshold),
        is_blocked   = risk_score >= blocked_threshold,
        multiplier   = multiplier,
        detail       = detail,
        csv_score    = csv_final_score,
    )


# ── DB 연동 함수 ──────────────────────────────────────────────

async def analyze_all_grids(
    session:           AsyncSession,
    realtime:          Optional[RealtimeData] = None,
    blocked_threshold: float = 65.0,
) -> list[RiskResult]:
    """ddc_grid 전체 조회 → 위험 점수 일괄 산출"""
    result = await session.execute(select(DdcGrid))
    grids  = result.scalars().all()

    return [
        compute_risk(
            gid               = g.gid,
            height_norm       = g.height_norm,
            slope_norm        = g.slope_norm,
            land_risk_value   = g.land_risk_value,
            flood_level_score = g.flood_level_score,
            city_flood_risk   = g.city_flood_risk,
            past_flood_risk   = g.past_flood_risk,
            freq_score        = g.freq_score,
            is_station_zone   = g.is_station_zone,
            realtime          = realtime,
            blocked_threshold = blocked_threshold,
            csv_final_score   = g.final_score,
        )
        for g in grids
    ]


async def analyze_grid_by_id(
    session:           AsyncSession,
    gid:               int,
    realtime:          Optional[RealtimeData] = None,
    blocked_threshold: float = 65.0,
) -> Optional[RiskResult]:
    """단일 격자 위험 점수 산출"""
    result = await session.execute(
        select(DdcGrid).where(DdcGrid.gid == gid)
    )
    g = result.scalar_one_or_none()
    if g is None:
        return None

    return compute_risk(
        gid               = g.gid,
        height_norm       = g.height_norm,
        slope_norm        = g.slope_norm,
        land_risk_value   = g.land_risk_value,
        flood_level_score = g.flood_level_score,
        city_flood_risk   = g.city_flood_risk,
        past_flood_risk   = g.past_flood_risk,
        freq_score        = g.freq_score,
        is_station_zone   = g.is_station_zone,
        realtime          = realtime,
        blocked_threshold = blocked_threshold,
        csv_final_score   = g.final_score,
    )
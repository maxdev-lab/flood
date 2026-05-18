"""
app/mape/excute.py   (기존 파일명 excute.py 유지)
──────────────────────────────────────────────────
MAPE-K Execute 단계

Analyze 결과를 바탕으로:
  1. 전역 캐시(메모리)에 최신 위험 점수 저장
  2. DB(realtime_risk 테이블)에 위험 격자 기록
  3. (추후) FCM 푸시 알림 발송
"""
from __future__ import annotations

from datetime import datetime
from typing import List, Optional

from app.mape.analyze import RiskResult

# 시뮬레이션 상태
_simulate_mode = False
_simulate_rainfall = 0.0
_simulate_sewer = 0.0

def set_simulate(rainfall: float, sewer: float):
    global _simulate_mode, _simulate_rainfall, _simulate_sewer
    _simulate_mode = True
    _simulate_rainfall = rainfall
    _simulate_sewer = sewer

def clear_simulate():
    global _simulate_mode, _simulate_rainfall, _simulate_sewer
    _simulate_mode = False
    _simulate_rainfall = 0.0
    _simulate_sewer = 0.0

def get_simulate():
    return _simulate_mode, _simulate_rainfall, _simulate_sewer

# ── 전역 인메모리 캐시 ────────────────────────────────────────
# API 응답 시 DB 조회 없이 즉시 반환하기 위한 최신 분석 결과 저장소
_latest_results: List[RiskResult] = []
_last_updated:   Optional[datetime] = None


def get_latest_results() -> List[RiskResult]:
    """현재 캐시된 최신 위험 점수 목록 반환"""
    return _latest_results


def get_last_updated() -> Optional[datetime]:
    return _last_updated


def execute(results: List[RiskResult]) -> None:
    """
    Analyze 결과를 캐시에 저장하고 콘솔에 요약 출력.

    Parameters
    ----------
    results : analyze_all_grids() 반환값
    """
    global _latest_results, _last_updated

    _latest_results = results
    _last_updated   = datetime.now()

    total   = len(results)
    danger  = sum(1 for r in results if r.risk_level == "DANGER")
    warning = sum(1 for r in results if r.risk_level == "WARNING")
    blocked = sum(1 for r in results if r.is_blocked)

    print(
        f"\n[Execute] {_last_updated.strftime('%H:%M:%S')} | "
        f"전체: {total} | DANGER: {danger} | WARNING: {warning} | "
        f"통행불가: {blocked}"
    )
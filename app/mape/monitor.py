"""
app/mape/monitor.py
───────────────────
MAPE-K Monitor 단계

기존 app/services/data_fetcher.py 의 fetch_all_dynamic_data() 를 래핑하여
RealtimeData 객체로 변환 후 반환.
APScheduler 는 app/main.py 에서 이 함수를 10초 주기로 호출.
"""
from __future__ import annotations

from app.mape.analyze import RealtimeData
from app.services.data_fetcher import fetch_all_dynamic_data


async def collect_realtime_data() -> RealtimeData:
    """
    기상청(강수량) + 서울 열린데이터(하수관 수위) 동시 수집 후
    RealtimeData 객체로 변환하여 반환.
    API 키가 없거나 오류 시 기본값(0.0) 사용.
    """
    print("\n[Monitor] 실시간 데이터 수집 시작...")
    data = await fetch_all_dynamic_data()

    # 강수량 파싱 (mm/hr)
    weather = data.get("weather", [])
    rainfall_mm = float(weather[0].get("RN1", 0.0)) if weather else 0.0

    # 하수관 수위 파싱 → 전체 측정소 평균 → 0~1 정규화 (기준: 2m 만수)
    sewage = data.get("sewage", [])
    if sewage:
        avg_level = sum(r.get("MEA_WAL", 0.0) for r in sewage) / len(sewage)
    else:
        avg_level = 0.0
    sewer_level = min(1.0, avg_level / 2.0)

    rt = RealtimeData(rainfall_mm=rainfall_mm, sewer_level=sewer_level)
    print(f"[Monitor] 수집 완료 → 강수량: {rainfall_mm}mm/hr, 수위: {sewer_level:.2f} (평균 {avg_level:.2f}m)")
    return rt
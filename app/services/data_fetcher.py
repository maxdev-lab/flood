"""
app/services/data_fetcher.py
─────────────────────────────
Monitor 단계에서 호출하는 외부 API 수집 모듈

1. 기상청 초단기실황 API  → 강남구 격자(nx=61, ny=125) 강수량(RN1)
2. 서울 열린데이터 하수관로 수위 API → 강남구(GU_CODE=23) 하수관 수위
"""
import httpx
import asyncio
from datetime import datetime, timedelta
from app.core.config import settings


# ── 기상청 격자 좌표 (강남구) ────────────────────────────────
KMA_NX = "61"
KMA_NY = "125"

# ── 서울 하수관로 구 코드 (강남구 = 23) ──────────────────────
GU_CODE = "23"


def get_kma_base_datetime() -> tuple[str, str]:
    """
    기상청 초단기실황 API는 매시 40분에 업데이트.
    현재 시각 기준으로 가장 최근 base_date, base_time 반환.

    예) 14:35 → base_time = "1300"
        14:45 → base_time = "1400"
    """
    now = datetime.now()
    if now.minute < 40:
        now -= timedelta(hours=1)
    return now.strftime("%Y%m%d"), now.strftime("%H00")


async def fetch_weather_data() -> list[dict]:
    """
    기상청 초단기실황(getUltraSrtNcst) API 호출.
    강남구 격자(nx=61, ny=125) 기준 강수량(RN1, mm) 반환.

    Returns
    -------
    [{"RN1": float}]  — 오류 시 [{"RN1": 0.0}]
    """
    if not settings.KMA_API_KEY:
        print("[Weather] API 키 없음 → 기본값 반환")
        return [{"RN1": 0.0}]

    base_date, base_time = get_kma_base_datetime()

    url = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst"
    params = {
        "ServiceKey": settings.KMA_API_KEY,
        "pageNo":     "1",
        "numOfRows":  "100",
        "dataType":   "JSON",
        "base_date":  base_date,
        "base_time":  base_time,
        "nx":         KMA_NX,
        "ny":         KMA_NY,
    }

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(url, params=params)
            response.raise_for_status()
            data = response.json()

        items = (
            data.get("response", {})
                .get("body", {})
                .get("items", {})
                .get("item", [])
        )

        rn1 = 0.0
        for item in items:
            if item.get("category") == "RN1":
                try:
                    rn1 = float(item.get("obsrValue", 0))
                except (ValueError, TypeError):
                    rn1 = 0.0
                break

        print(f"[Weather] 기상청 수집 완료 → 강수량: {rn1} mm/hr "
              f"(기준: {base_date} {base_time})")
        return [{"RN1": rn1}]

    except httpx.HTTPStatusError as e:
        print(f"[Weather] HTTP 오류 {e.response.status_code}: {e}")
        return [{"RN1": 0.0}]
    except Exception as e:
        print(f"[Weather] API 호출 실패: {e} → 기본값 반환")
        return [{"RN1": 0.0}]


async def fetch_sewage_data() -> list[dict]:
    """
    서울 열린데이터 하수관로 수위 API 호출.
    강남구(GU_CODE=23) 하수관 수위(MEA_WAL, m) 반환.

    Returns
    -------
    [{"GU_NAME": str, "MEA_WAL": float}, ...]  — 오류 시 []
    """
    if not settings.SEOUL_OPEN_API_KEY:
        print("[Sewage] API 키 없음 → 빈 목록 반환")
        return []

    now        = datetime.now()
    start_time = (now - timedelta(hours=1)).strftime("%Y%m%d%H")
    end_time   = now.strftime("%Y%m%d%H")

    url = (
        f"http://openapi.seoul.go.kr:8088"
        f"/{settings.SEOUL_OPEN_API_KEY}"
        f"/json/DrainpipeMonitoringInfo"
        f"/1/100/{GU_CODE}/{start_time}/{end_time}"
    )

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.get(url)
            response.raise_for_status()
            data = response.json()

        # 에러 응답 처리
        result_code = (
            data.get("DrainpipeMonitoringInfo", {})
                .get("RESULT", {})
                .get("CODE", "INFO-000")
        )
        if "ERROR" in result_code or "INFO-200" in result_code:
            msg = (
                data.get("DrainpipeMonitoringInfo", {})
                    .get("RESULT", {})
                    .get("MESSAGE", "알 수 없는 오류")
            )
            print(f"[Sewage] API 응답 오류: {msg}")
            return []

        rows = (
            data.get("DrainpipeMonitoringInfo", {})
                .get("row", [])
        )

        parsed = []
        for row in rows:
            try:
                parsed.append({
                    "GU_NAME": row.get("GU_NAME", "강남구"),
                    "IDN":     row.get("IDN"),
                    "MEA_WAL": float(row.get("MEA_WAL", 0.0)),
                })
            except (ValueError, TypeError):
                continue

        if parsed:
            avg_level = sum(r["MEA_WAL"] for r in parsed) / len(parsed)
            print(f"[Sewage] 수집 완료 → {len(parsed)}개 측정소, "
                  f"평균 수위: {avg_level:.2f}m")
        else:
            print("[Sewage] 해당 시간대 데이터 없음")

        return parsed

    except httpx.HTTPStatusError as e:
        print(f"[Sewage] HTTP 오류 {e.response.status_code}: {e}")
        return []
    except Exception as e:
        print(f"[Sewage] API 호출 실패: {e} → 빈 목록 반환")
        return []


async def fetch_all_dynamic_data() -> dict:
    """
    기상청 + 하수관로 API 동시 호출 후 통합 반환.
    monitor.py 에서 이 함수를 호출함.
    """
    sewage_data, weather_data = await asyncio.gather(
        fetch_sewage_data(),
        fetch_weather_data(),
    )
    return {
        "weather": weather_data,
        "sewage":  sewage_data,
    }
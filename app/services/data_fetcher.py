import httpx
import asyncio
from datetime import datetime, timedelta
from app.core.config import settings

def get_kma_base_datetime():
    """
    기상청 초단기실황 API는 매시간 40분에 최신 데이터가 업데이트됩니다.
    현재 시간을 기준으로 가장 최신의 유효한 base_date와 base_time을 계산합니다.
    """
    now = datetime.now()
    # 현재 분이 40분 이전이면, 이전 시간의 데이터를 요청해야 함
    if now.minute < 40:
        now -= timedelta(hours=1)
    
    base_date = now.strftime("%Y%m%d")
    base_time = now.strftime("%H00")
    return base_date, base_time

async def fetch_sewage_data():
    """서울시 하수관로 수위 데이터를 비동기로 수집하고 파싱합니다."""
    if not settings.SEOUL_OPEN_API_KEY:
        print("⚠️ [서울시 API] 키가 없어 더미 데이터를 반환합니다.")
        return [{"GU_NAME": "강남구", "MEA_WAL": 0.5}]

    # 1. 동적으로 조회 시간(현재 시간 기준 1시간 전 ~ 현재) 계산
    now = datetime.now()
    start_time = (now - timedelta(hours=1)).strftime("%Y%m%d%H")  # 예: 2026031022 (YYYYMMDDHH 포맷)
    end_time = now.strftime("%Y%m%d%H")                           # 예: 2026031023
    
    # 2. 강남구 자치구 코드 ('23')
    gu_code = "23" 

    # 3. 필수 파라미터가 모두 포함된 완전한 URL 생성
    url = f"http://openapi.seoul.go.kr:8088/{settings.SEOUL_OPEN_API_KEY}/json/DrainpipeMonitoringInfo/1/100/{gu_code}/{start_time}/{end_time}"

    try:
        async with httpx.AsyncClient() as client:
            response = await client.get(url, timeout=10.0)
            response.raise_for_status()
            data = response.json()
            
            # 응답 구조 안에 에러(RESULT)가 있는지 한 번 더 체크 (방어 로직)
            if "RESULT" in data and "ERROR" in data["RESULT"].get("CODE", ""):
                print(f"⚠️ 서울시 API 내부 에러: {data['RESULT']['MESSAGE']}")
                return []
            
            # 응답 JSON에서 실제 수위 데이터 배열 추출
            rows = data.get("DrainpipeMonitoringInfo", {}).get("row", [])
            
            parsed_data = []
            for row in rows:
                parsed_data.append({
                    "GU_NAME": row.get("GU_NAME", "알수없음"), # 자치구명
                    "IDN": row.get("IDN"),                   # 식별코드 (센서 고유번호)
                    "MEA_WAL": float(row.get("MEA_WAL", 0.0)) # 측정수위(m)
                })
            
            # 데이터가 있으면 성공 로그 출력
            if parsed_data:
                print(f"✅ 서울시 하수관로 데이터 수집 완료 (강남구 센서 총 {len(parsed_data)}건)")
            else:
                print("⚠️ 해당 시간대에 기록된 하수관로 데이터가 없습니다.")
                
            return parsed_data
            
    except Exception as e:
        print(f"❌ 서울시 API 호출 실패: {e}")
        return []
        
async def fetch_weather_data():
    """기상청 초단기실황 API에서 강수량 데이터를 비동기로 수집하고 파싱합니다."""
    if not settings.KMA_API_KEY:
        print("⚠️ [기상청 API] 키가 없어 더미 데이터를 반환합니다.")
        return [{"RN1": 15.5}]

    base_date, base_time = get_kma_base_datetime()
    url = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst"
    
    params = {
        "ServiceKey": settings.KMA_API_KEY, # API 키
        "pageNo": "1",
        "numOfRows": "100",
        "dataType": "JSON",
        "base_date": base_date,
        "base_time": base_time,
        "nx": "61",  # 강남구 역삼동의 기상청 X 격자 좌표
        "ny": "125"  # 강남구 역삼동의 기상청 Y 격자 좌표
    }

    try:
        async with httpx.AsyncClient() as client:
            response = await client.get(url, params=params, timeout=10.0)
            response.raise_for_status()
            data = response.json()
            
            # 기상청 JSON 구조 파싱: response -> body -> items -> item 배열
            items = data.get("response", {}).get("body", {}).get("items", {}).get("item", [])
            
            # 여러 실황 데이터 중 1시간 강수량('RN1')만 추출
            rn1_value = 0.0
            for item in items:
                if item.get("category") == "RN1":
                    obsr_value = item.get("obsrValue")
                    # '강수없음' 등 문자열 예외 처리
                    try:
                        rn1_value = float(obsr_value)
                    except ValueError:
                        rn1_value = 0.0
                    break
                    
            print(f"✅ 기상청 강수량 데이터 수집 완료 (강남구: {rn1_value}mm)")
            return [{"RN1": rn1_value}]
            
    except Exception as e:
        print(f"❌ 기상청 API 호출 실패: {e}")
        return []

async def fetch_all_dynamic_data():
    """스케줄러에서 호출할 메인 함수. 두 API를 동시에 병렬로 호출합니다."""
    sewage_data, weather_data = await asyncio.gather(
        fetch_sewage_data(),
        fetch_weather_data()
    )
    
    return {
        "sewage": sewage_data,
        "weather": weather_data
    }
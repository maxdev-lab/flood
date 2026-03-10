from fastapi import FastAPI
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from contextlib import asynccontextmanager
from app.services.risk_engine import process_risk_pipeline  
from app.services.data_fetcher import fetch_all_dynamic_data
from app.api import routes
from app import crud
from app.database import SessionLocal
from app.database import engine, Base
from app.models import RealtimeRisk

# from app.database import engine, Base
Base.metadata.create_all(bind=engine) 

scheduler = AsyncIOScheduler()

async def fetch_and_analyze_data():
    """
    주기적으로 실행될 핵심 파이프라인
    """
    print("\n==================================================")
    print("🕒 [스케줄러 작동] 실시간 침수 데이터 수집 시작...")
    print("==================================================")
    
    # 데이터 수집 함수 실행 (비동기 대기)
    data = await fetch_all_dynamic_data()
    
    # 예쁘게 콘솔에 출력해서 구경하기
    print(f"💧 하수관로 데이터: {data['sewage']}")
    print(f"🌧️ 기상청 강수량 데이터: {data['weather']}")
    print("==================================================\n")

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 🚨 테스트용: 10초(seconds=10)마다 실행! 
    # 실제 서비스 배포 시에는 기획서대로 30분(minutes=30)으로 변경하세요.
    scheduler.add_job(fetch_and_analyze_data, 'interval', seconds=20)
    scheduler.start()
    print("✅ 백그라운드 스케줄러 시작 완료 (10초 주기 테스트 중...)")
    
    yield  # 서버가 돌아가는 동안 대기
    
    # 서버 종료 시 스케줄러 안전하게 종료
    scheduler.shutdown()
    print("🛑 백그라운드 스케줄러 종료")

app = FastAPI(
    title="자율 침수 대응 시스템 API",
    description="보행자 맞춤형 실시간 안전 경로 및 침수 알림 제공 서버",
    version="1.0.0",
    lifespan=lifespan
)

async def fetch_and_analyze_data():
    print("\n==================================================")
    print("🕒 [스케줄러 작동] 실시간 침수 데이터 수집 및 연산 시작...")
    print("==================================================")
    
    # 1. 동적 데이터 수집
    data = await fetch_all_dynamic_data()
    
    # 2. 👈 AI 위험도 엔진 가동!
    analyzed_df = process_risk_pipeline(data['weather'], data['sewage'])
    
    # 3. 연산 결과 콘솔에 출력해서 구경하기 (Pandas DataFrame 형태)
    print("\n📊 [최종 위험도 연산 결과]")
    print(analyzed_df[['grid_id', 'static_risk', 'final_risk_score', 'risk_level']])
    print("==================================================\n")

@app.get("/")
def read_root():
    return {"message": "자율 침수 대응 시스템 백엔드 서버가 정상 작동 중입니다."}


async def fetch_and_analyze_data():
    print("\n==================================================")
    print("🕒 [스케줄러 작동] 실시간 침수 데이터 수집 및 연산 시작...")
    print("==================================================")
    
    # 1. 동적 데이터 수집 (기상청 + 서울시)
    data = await fetch_all_dynamic_data()
    
    # 2. AI 위험도 엔진 연산
    # (실제 강수량과 수위 데이터를 인자로 넘깁니다)
    analyzed_df = process_risk_pipeline(data['weather'], data['sewage'])
    
    # 3. 📥 [핵심] 연산 결과를 DB에 기록
    # 비동기 함수 내에서 동기 방식의 SQLAlchemy 세션을 생성하여 사용합니다.
    db = SessionLocal()
    try:
        # analyzed_df에는 grid_id, static_risk, final_risk_score 등이 들어있습니다.
        crud.save_realtime_risks(db, analyzed_df)
        print("💾 AI 연산 결과가 데이터베이스에 안전하게 기록되었습니다.")
    except Exception as e:
        print(f"❌ DB 저장 중 오류 발생: {e}")
    finally:
        db.close() # 세션은 반드시 닫아줘야 합니다.

    # 4. 연산 결과 콘솔 출력 (디버깅용)
    print("\n📊 [최종 위험도 연산 결과 요약]")
    print(analyzed_df[['grid_id', 'final_risk_score', 'risk_level']])
    print("==================================================\n")


# 클라이언트용 API 라우터 연결
app.include_router(routes.router)

# uvicorn app.main:app --reload
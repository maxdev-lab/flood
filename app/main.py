"""
app/main.py
───────────
FastAPI 앱 진입점

MAPE-K 루프:
  Monitor → Analyze → Plan → Execute
  APScheduler 로 10초(개발) / 60초(운영) 주기 실행
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI
from apscheduler.schedulers.asyncio import AsyncIOScheduler

from app.api import routes
from app.database import engine, Base
from app.db.database import AsyncSessionLocal
from app.mape.monitor import collect_realtime_data
from app.mape.analyze import analyze_all_grids
from app.mape.excute  import execute

# 기존 동기 모델 테이블 생성 (User, GridInfo, RealtimeRisk, RouteHistory)
Base.metadata.create_all(bind=engine)

scheduler = AsyncIOScheduler()


# ── MAPE-K 루프 ──────────────────────────────────────────────

async def run_mape_loop() -> None:
    print("\n" + "=" * 50)
    print("[MAPE-K] 루프 시작")

    # Monitor: 시뮬레이션 모드면 가짜 데이터 사용
    from app.mape.excute import get_simulate
    sim_mode, sim_rain, sim_sewer = get_simulate()

    if sim_mode:
        from app.mape.monitor import RealtimeData
        realtime = RealtimeData(rainfall_mm=sim_rain, sewer_level=sim_sewer)
        print(f"[SIMULATE] 강수량: {sim_rain}mm/hr, 수위: {sim_sewer}m")
    else:
        realtime = await collect_realtime_data()

    async with AsyncSessionLocal() as session:
        results = await analyze_all_grids(session, realtime=realtime)

    execute(results)
    print("=" * 50)


# ── Lifespan ──────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 앱 시작: 스케줄러 등록
    scheduler.add_job(run_mape_loop, "interval", seconds=10)
    scheduler.start()
    print("✅ MAPE-K 자율 루프 시작 (10초 주기)")

    yield  # 앱 실행 중

    # 앱 종료: 스케줄러 정지
    scheduler.shutdown()
    print("🛑 MAPE-K 루프 종료")


# ── FastAPI 앱 ────────────────────────────────────────────────

app = FastAPI(
    title="실시간 침수 대응 시스템 API",
    description="MAPE-K 기반 자율 침수 위험 점수 산출 및 안전 경로 안내",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/")
def read_root():
    return {"message": "실시간 침수 대응 시스템 서버가 정상 동작 중입니다."}


@app.get("/health")
def health_check():
    return {"status": "ok"}


# 라우터 등록
app.include_router(routes.router)

# 실행: python -m uvicorn app.main:app --reload
#docker-compose up -d db

#신논현역
#래미안서초스위트
#원명초등학교
#풍림지오빌아파트
#demo_quick
#gangnam_extreme
#prolonged_rain
#sudden_heavy_rain
#티스테이션 서초점
#브라운스톤 로얄스위트
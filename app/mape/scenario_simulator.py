
"""
app/mape/scenario_simulator.py
 
강남구 도시 침수 시나리오 기반 시뮬레이터.
각 시나리오는 단계(Step)로 구성되며, MAPE-K 루프에 자동으로 주입됩니다.
"""
 
import asyncio
import time
from dataclasses import dataclass, field
from typing import List, Optional
from app.mape.excute import set_simulate, clear_simulate
 
# ──────────────────────────────────────────────────────────────
# 데이터 구조
# ──────────────────────────────────────────────────────────────
 
@dataclass
class ScenarioStep:
    """시나리오의 단일 단계."""
    rainfall_mm: float        # 강수량 (mm/hr)
    sewer_level: float        # 하수관로 수위 (m)
    duration_sec: int         # 이 단계 지속 시간 (초)
    description: str          # 단계 설명 (로그/UI용)
 
 
@dataclass
class Scenario:
    """시나리오 정의."""
    id: str
    name: str
    description: str
    steps: List[ScenarioStep]
 
 
# ──────────────────────────────────────────────────────────────
# 시나리오 정의
# ──────────────────────────────────────────────────────────────
 
SCENARIOS: dict[str, Scenario] = {
 
    # ── 시나리오 1: 갑작스러운 집중호우 ──────────────────────────
    "sudden_heavy_rain": Scenario(
        id="sudden_heavy_rain",
        name="갑작스러운 집중호우",
        description="맑은 날씨에서 갑자기 시작된 집중호우로 강남구 일대가 침수되는 시나리오",
        steps=[
            ScenarioStep(0.0,  0.0,  20, "☀️ 초기 상태: 맑음"),
            ScenarioStep(5.0,  0.1,  20, "🌦 약한 비 시작"),
            ScenarioStep(20.0, 0.3,  20, "🌧 비 강화 — 하수관 수위 상승"),
            ScenarioStep(40.0, 0.8,  20, "⛈ 강한 비 — 일부 구역 침수 시작"),
            ScenarioStep(65.0, 1.3,  20, "🚨 폭우 — 광범위 침수 발생"),
            ScenarioStep(80.0, 1.8,  20, "🔴 최대 침수 — DANGER 구역 급증"),
            ScenarioStep(50.0, 1.5,  20, "📉 비 약화 — 침수 지속"),
            ScenarioStep(20.0, 1.0,  20, "🌦 회복 중 — 수위 서서히 감소"),
            ScenarioStep(0.0,  0.3,  20, "☁️ 비 그침 — 잔류 침수"),
            ScenarioStep(0.0,  0.0,  10, "✅ 정상화"),
        ]
    ),
 
    # ── 시나리오 2: 장기 지속 강우 ───────────────────────────────
    "prolonged_rain": Scenario(
        id="prolonged_rain",
        name="장기 지속 강우",
        description="약한 비가 오랫동안 지속되어 하수관이 포화되고 서서히 침수가 확산되는 시나리오",
        steps=[
            ScenarioStep(0.0,  0.0,  15, "☀️ 초기 상태"),
            ScenarioStep(8.0,  0.1,  20, "🌧 약한 비 시작"),
            ScenarioStep(10.0, 0.2,  20, "🌧 지속 강우 — 하수관 서서히 포화"),
            ScenarioStep(12.0, 0.4,  20, "🌧 하수관 포화 임박"),
            ScenarioStep(15.0, 0.7,  20, "⚠️ 저지대 침수 시작"),
            ScenarioStep(18.0, 1.0,  20, "🚨 침수 구역 확산"),
            ScenarioStep(20.0, 1.2,  20, "🔴 광범위 침수"),
            ScenarioStep(15.0, 1.3,  20, "🔴 침수 지속 (비 약화되어도 수위 유지)"),
            ScenarioStep(5.0,  1.1,  20, "📉 회복 시작"),
            ScenarioStep(0.0,  0.5,  20, "☁️ 비 그침 — 수위 감소 중"),
            ScenarioStep(0.0,  0.0,  10, "✅ 정상화"),
        ]
    ),
 
    # ── 시나리오 3: 강남역 일대 극한 침수 ───────────────────────
    "gangnam_extreme": Scenario(
        id="gangnam_extreme",
        name="강남역 극한 침수 (2022년 재현)",
        description="2022년 8월 강남구 침수 사태를 재현. 단시간 내 극강의 강수로 강남역 일대가 완전 침수",
        steps=[
            ScenarioStep(0.0,   0.0,  15, "☀️ 초기 상태"),
            ScenarioStep(30.0,  0.3,  15, "🌧 갑작스러운 강우"),
            ScenarioStep(70.0,  0.8,  15, "⛈ 시간당 70mm — 극한 강우"),
            ScenarioStep(100.0, 1.5,  15, "🚨 시간당 100mm — 하수관 완전 역류"),
            ScenarioStep(120.0, 2.0,  15, "🔴🔴 최대 침수 — 강남역 일대 통행 불가"),
            ScenarioStep(80.0,  2.0,  15, "🔴 비 약화 — 수위 최고조 유지"),
            ScenarioStep(40.0,  1.8,  15, "📉 강우 감소"),
            ScenarioStep(10.0,  1.3,  15, "📉 서서히 회복"),
            ScenarioStep(0.0,   0.8,  15, "☁️ 비 그침"),
            ScenarioStep(0.0,   0.2,  15, "🌤 회복 중"),
            ScenarioStep(0.0,   0.0,  10, "✅ 정상화"),
        ]
    ),
 
    # ── 시나리오 4: 빠른 데모용 (30초) ──────────────────────────
    "demo_quick": Scenario(
        id="demo_quick",
        name="데모용 빠른 시나리오",
        description="발표 데모를 위한 빠른 시나리오. 30초 만에 침수 발생~회복 전 과정을 보여줌",
        steps=[
            ScenarioStep(0.0,  0.0,  8,  "☀️ 정상"),
            ScenarioStep(40.0, 0.8,  8,  "⛈ 폭우 시작"),
            ScenarioStep(80.0, 1.8,  8,  "🔴 최대 침수"),
            ScenarioStep(20.0, 1.0,  8,  "📉 회복 중"),
            ScenarioStep(0.0,  0.0,  8,  "✅ 정상화"),
        ]
    ),
}
 
 
# ──────────────────────────────────────────────────────────────
# 시뮬레이터 상태
# ──────────────────────────────────────────────────────────────
 
class ScenarioSimulator:
    def __init__(self):
        self._running = False
        self._current_scenario_id: Optional[str] = None
        self._current_step_index: int = 0
        self._current_step_desc: str = ""
        self._task: Optional[asyncio.Task] = None
        self._start_time: float = 0.0
        self._total_steps: int = 0
 
    @property
    def is_running(self) -> bool:
        return self._running
 
    @property
    def status(self) -> dict:
        return {
            "running": self._running,
            "scenario_id": self._current_scenario_id,
            "scenario_name": SCENARIOS[self._current_scenario_id].name
                if self._current_scenario_id else None,
            "current_step": self._current_step_index,
            "total_steps": self._total_steps,
            "step_description": self._current_step_desc,
            "elapsed_sec": int(time.time() - self._start_time) if self._running else 0,
        }
 
    async def start(self, scenario_id: str):
        """시나리오 시작."""
        if scenario_id not in SCENARIOS:
            raise ValueError(f"알 수 없는 시나리오: {scenario_id}")
 
        # 이미 실행 중이면 중단
        await self.stop()
 
        scenario = SCENARIOS[scenario_id]
        self._running = True
        self._current_scenario_id = scenario_id
        self._current_step_index = 0
        self._total_steps = len(scenario.steps)
        self._start_time = time.time()
 
        print(f"\n{'='*50}")
        print(f"[SCENARIO] 시작: {scenario.name}")
        print(f"[SCENARIO] {scenario.description}")
        print(f"{'='*50}")
 
        self._task = asyncio.create_task(self._run(scenario))
 
    async def stop(self):
        """시나리오 중단 및 시뮬레이션 초기화."""
        if self._task and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        self._running = False
        self._current_scenario_id = None
        self._current_step_index = 0
        self._current_step_desc = ""
        clear_simulate()
        print("[SCENARIO] 중단 — 실제 데이터 수집으로 복귀")
 
    async def _run(self, scenario: Scenario):
        """시나리오 단계별 실행 루프."""
        try:
            for i, step in enumerate(scenario.steps):
                self._current_step_index = i + 1
                self._current_step_desc = step.description
 
                print(f"\n[SCENARIO] Step {i+1}/{len(scenario.steps)}: {step.description}")
                print(f"           강수량: {step.rainfall_mm}mm/hr | 수위: {step.sewer_level}m | {step.duration_sec}초")
 
                set_simulate(step.rainfall_mm, step.sewer_level)
                await asyncio.sleep(step.duration_sec)
 
            print(f"\n[SCENARIO] 완료: {scenario.name}")
        except asyncio.CancelledError:
            pass
        finally:
            self._running = False
            clear_simulate()
 
 
# 전역 싱글톤
scenario_simulator = ScenarioSimulator()
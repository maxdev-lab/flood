import math
import time as _time
from fastapi import APIRouter, Depends, Query, HTTPException
from sqlalchemy.orm import Session
from sqlalchemy.ext.asyncio import AsyncSession
from typing import List
from pydantic import BaseModel
from app import schemas, crud
from app.database import get_db
from app.db.database import get_async_db
from app.mape.analyze import analyze_grid_by_id, RealtimeData
from app.mape.excute import get_latest_results, get_last_updated
from app.mape.plan import find_safe_route

router = APIRouter(prefix="/api/v1", tags=["Client API"])

_COLS = 38
_START_LAT = 37.4640
_START_LON = 127.0030
_LAT_STEP = 100 / 111000
_LON_STEP = 100 / (111000 * math.cos(math.radians(37.5)))

def gid_to_center(gid):
    idx = gid - 1
    row = idx // _COLS
    col = idx % _COLS
    return round(_START_LAT + (row + 0.5) * _LAT_STEP, 6), round(_START_LON + (col + 0.5) * _LON_STEP, 6)

class FloodCellOut(BaseModel):
    centerLat: float
    centerLon: float
    riskLevel: int

class FloodDatasetOut(BaseModel):
    cells: List[FloodCellOut]
    timestamp: int
    total: int
    blocked: int

@router.get("/flood/cells", response_model=FloodDatasetOut)
async def get_flood_cells(min_risk: int = Query(default=30), threshold: float = Query(default=65.0)):
    results = get_latest_results()
    if not results:
        return FloodDatasetOut(cells=[], timestamp=int(_time.time()*1000), total=0, blocked=0)
    cells = []
    blocked = 0
    for r in results:
        risk_level = int(r.risk_score)
        if risk_level < min_risk:
            continue
        lat, lon = gid_to_center(r.gid)
        cells.append(FloodCellOut(centerLat=lat, centerLon=lon, riskLevel=max(1, min(100, risk_level))))
        if r.risk_score >= threshold:
            blocked += 1
    last = get_last_updated()
    ts = int(last.timestamp()*1000) if last else int(_time.time()*1000)
    return FloodDatasetOut(cells=cells, timestamp=ts, total=len(results), blocked=blocked)

@router.get("/risk/grids")
async def get_all_risk(threshold: float = Query(default=65.0)):
    results = get_latest_results()
    if not results:
        return {"message": "no data", "total": 0, "grids": []}
    last_updated = get_last_updated()
    grids = []
    for r in results:
        lat, lon = gid_to_center(r.gid)
        grids.append({"gid": r.gid, "centerLat": lat, "centerLon": lon, "risk_score": r.risk_score, "risk_level": r.risk_level, "is_blocked": r.risk_score >= threshold})
    return {"total": len(results), "blocked_count": sum(1 for r in results if r.risk_score >= threshold), "last_updated": last_updated.isoformat() if last_updated else None, "grids": grids}

@router.get("/risk/grids/{gid}")
async def get_single_risk(gid: int, rainfall: float = Query(default=0.0), sewer: float = Query(default=0.0), threshold: float = Query(default=65.0), session: AsyncSession = Depends(get_async_db)):
    rt = RealtimeData(rainfall_mm=rainfall, sewer_level=sewer)
    result = await analyze_grid_by_id(session, gid, realtime=rt, blocked_threshold=threshold)
    if result is None:
        raise HTTPException(status_code=404, detail=f"gid={gid} not found")
    lat, lon = gid_to_center(gid)
    return {"gid": result.gid, "centerLat": lat, "centerLon": lon, "risk_score": result.risk_score, "risk_level": result.risk_level, "is_blocked": result.is_blocked, "detail": result.detail}

@router.get("/risk/summary")
async def get_risk_summary(threshold: float = Query(default=65.0)):
    results = get_latest_results()
    if not results:
        return {"message": "no data", "total": 0}
    levels = {"SAFE": 0, "WARNING": 0, "DANGER": 0}
    for r in results:
        levels[r.risk_level] = levels.get(r.risk_level, 0) + 1
    scores = [r.risk_score for r in results]
    last_updated = get_last_updated()
    return {"total": len(results), "blocked_count": sum(1 for r in results if r.risk_score >= threshold), "level_dist": levels, "stats": {"min": round(min(scores),2), "max": round(max(scores),2), "mean": round(sum(scores)/len(scores),2)}, "last_updated": last_updated.isoformat() if last_updated else None}

@router.get("/routes/safe", response_model=schemas.SafeRouteResponse)
def get_safe_route_api(start_lat: float = Query(...), start_lng: float = Query(...), end_lat: float = Query(...), end_lng: float = Query(...)):
    results = get_latest_results()
    route = find_safe_route(start_lat, start_lng, end_lat, end_lng, results or None)
    if "error" in route:
        raise HTTPException(status_code=400, detail=route["error"])
    return schemas.SafeRouteResponse(is_rerouted=route["is_rerouted"], total_distance_m=route["total_distance_m"], estimated_time_min=route["estimated_time_min"], warning_message=route.get("warning_message"), path=route["path"])

@router.post("/users/{user_id}/location", response_model=schemas.LocationUpdateResponse)
def update_user_location(user_id: str, location_data: schemas.LocationUpdateRequest, db: Session = Depends(get_db)):
    results = get_latest_results()
    in_danger = any(r.risk_level == "DANGER" for r in results) if results else False
    return schemas.LocationUpdateResponse(in_danger_zone=in_danger, action_required="REROUTE" if in_danger else "NONE")

@router.get("/risk/heatmap", response_model=schemas.HeatmapResponse)
def get_risk_heatmap(lat: float = Query(...), lng: float = Query(...), radius: int = Query(1000), db: Session = Depends(get_db)):
    return schemas.HeatmapResponse(grids=crud.get_heatmap_data(db, lat=lat, lng=lng, radius_m=radius))

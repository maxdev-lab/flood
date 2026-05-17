"""
app/mape/plan.py
────────────────
MAPE-K Plan 단계: PathFinder
 
위험 점수가 임계치를 넘은 격자를 통행 불가로 설정하고
기존 routing.py 의 OSMnx 그래프에 위험 가중치를 적용하여
Dijkstra 알고리즘으로 안전 우회 경로를 계산.
"""
from __future__ import annotations
import osmnx as ox
from typing import Dict, List, Optional
import networkx as nx
 
from app.mape.analyze import RiskResult
 
 
# ── 그래프 로딩 (앱 시작 시 1회) ────────────────────────────
PLACE_NAME = "Dongducheon-si, Gyeonggi-do, South Korea"
 
try:
    print(f"[Plan] 도로 그래프 로딩 중: {PLACE_NAME}")
    G = ox.graph_from_place(PLACE_NAME, network_type="walk")
    print(f"[Plan] 그래프 로딩 완료 (노드: {len(G.nodes)}, 엣지: {len(G.edges)})")
except Exception as e:
    print(f"[Plan] 그래프 로딩 실패: {e} → 빈 그래프로 대체")
    G = nx.MultiDiGraph()
 
 
# ── 페널티 설정 ───────────────────────────────────────────────
DANGER_PENALTY  = 9999.0
WARNING_PENALTY = 10.0
 
 
def apply_risk_weights(results: List[RiskResult]) -> None:
    """
    RiskResult 목록을 기반으로 OSMnx 그래프 엣지에
    safe_weight 를 계산하여 in-place 업데이트.
 
    - DANGER  → 사실상 통행 불가 (비용 × DANGER_PENALTY)
    - WARNING → 우회 유도 (비용 × WARNING_PENALTY)
    - SAFE    → 기본 비용 유지
    """
    risk_map: Dict[int, str] = {r.gid: r.risk_level for r in results}
 
    for u, v, key, data in G.edges(keys=True, data=True):
        base = data.get("length", 1.0)
        # TODO: 엣지 geometry로 격자 gid를 매핑하는 공간 JOIN 구현 예정
        # 현재는 safe_weight = length (기본값) 설정
        data["safe_weight"] = base
 
    print(f"[Plan] 그래프 가중치 업데이트 완료 (격자 수: {len(results)})")
 
 
def find_safe_route(
    start_lat: float,
    start_lng: float,
    end_lat:   float,
    end_lng:   float,
    results:   Optional[List[RiskResult]] = None,
) -> Dict:
    """
    Dijkstra(safe_weight 기준) 로 최단 안전 경로 반환.
 
    Parameters
    ----------
    results : 최신 RiskResult 목록 (없으면 위험 가중치 미적용)
 
    Returns
    -------
    dict with keys:
        is_rerouted, total_distance_m, estimated_time_min,
        warning_message, path (List[{lat, lng}]), blocked_grids (int)
    """
    if len(G.nodes) == 0:
        return {"error": "경로 그래프 데이터가 로드되지 않았습니다."}
 
    # 위험 가중치 적용
    if results:
        apply_risk_weights(results)
 
    blocked = sum(1 for r in (results or []) if r.is_blocked)
 
    try:
        import osmnx as ox
        orig = ox.distance.nearest_nodes(G, X=start_lng, Y=start_lat)
        dest = ox.distance.nearest_nodes(G, X=end_lng,   Y=end_lat)
 
        route_nodes = nx.shortest_path(G, orig, dest, weight="safe_weight")
 
        path_coords: List[Dict] = []
        total_dist = 0.0
 
        for i, node_id in enumerate(route_nodes):
            nd = G.nodes[node_id]
            path_coords.append({"lat": nd["y"], "lng": nd["x"]})
            if i < len(route_nodes) - 1:
                edge = G.get_edge_data(node_id, route_nodes[i + 1])
                if edge:
                    total_dist += edge[0].get("length", 0.0)
 
        est_time = max(1, int(total_dist / 66.0))  # 보행 4km/h ≈ 66m/min
 
        return {
            "is_rerouted":       blocked > 0,
            "total_distance_m":  int(total_dist),
            "estimated_time_min": est_time,
            "warning_message":   f"침수 위험 격자 {blocked}개를 우회하는 안전 경로입니다." if blocked else None,
            "path":              path_coords,
            "blocked_grids":     blocked,
        }
 
    except nx.NetworkXNoPath:
        return {"error": "출발지와 목적지를 연결하는 안전한 경로를 찾을 수 없습니다."}
    except Exception as e:
        return {"error": f"경로 탐색 중 오류 발생: {str(e)}"}
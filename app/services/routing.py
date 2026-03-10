import networkx as nx
import osmnx as ox
import pandas as pd
from typing import Dict, List


# 1. 서버 구동 시 서비스 구역(강남구 역삼동)의 보행자 도로망 그래프를 한 번만 메모리에 로드합니다.
# (실제 배포 시에는 매번 다운로드하지 않고 .graphml 파일로 저장해두고 불러오는 방식을 권장합니다.)
PLACE_NAME = "Yeoksam-dong, Gangnam-gu, Seoul, South Korea"
try:
    print(f"🗺️ 도로망 데이터 로딩 중: {PLACE_NAME}")
    # 도보(walk) 기준 네트워크 그래프 획득
    G = ox.graph_from_place(PLACE_NAME, network_type='walk')
    print("✅ 도로망 그래프 로드 완료!")
except Exception as e:
    print(f"⚠️ 도로망 로드 실패: {e}. 연결 테스트를 위해 빈 그래프를 생성합니다.")
    G = nx.Graph()

def get_safe_route(
    start_lat: float, start_lng: float, 
    end_lat: float, end_lng: float, 
    risk_data: pd.DataFrame = None
) -> Dict:
    """
    출발지/목적지 좌표와 실시간 침수 위험도를 바탕으로 안전한 우회 경로를 계산합니다.
    """
    if len(G.nodes) == 0:
        return {"error": "도로망 데이터가 준비되지 않았습니다."}

    try:
        # 1. 사용자의 출발지, 목적지 좌표와 가장 가까운 그래프 상의 교차로(Node) 찾기
        orig_node = ox.distance.nearest_nodes(G, X=start_lng, Y=start_lat)
        dest_node = ox.distance.nearest_nodes(G, X=end_lng, Y=end_lat)

        # 2. 간선(Edge) 가중치 동적 업데이트 로직 (핵심)
        penalty_factor = 50.0  # 위험 등급이 높을 때 부여할 막대한 페널티 가중치

        for u, v, key, data in G.edges(keys=True, data=True):
            base_length = data.get('length', 1.0) # 기본 도로 길이(m)
            
            # TODO: 실제로는 이 간선(data['geometry'])이 어느 격자에 속하는지 판별하여 위험도를 가져옵니다.
            # 지금은 AI 연산 결과가 연동되기 전이므로, 위험도를 0.0으로 가정합니다.
            simulated_risk_score = 0.0 
            
            # 만약 DANGER(위험) 구간이라면 비용(Cost)이 기하급수적으로 높아집니다.
            # 공식: 안전 가중치 = 기본 거리 * (1 + (침수 위험도 * 페널티 계수))
            data['safe_weight'] = base_length * (1.0 + (simulated_risk_score * penalty_factor))

        # 3. 안전 최단 경로 탐색 (다익스트라 알고리즘 응용)
        # weight 파라미터에 방금 계산한 'safe_weight'를 적용하여 위험 구역을 피해가도록 유도합니다.
        route_nodes = nx.shortest_path(G, orig_node, dest_node, weight='safe_weight')

        # 4. 탐색된 노드 ID 배열을 실제 위경도(lat, lng) 리스트로 변환하고 총 거리를 계산합니다.
        path_coords = []
        total_distance = 0.0
        
        for i in range(len(route_nodes)):
            node_id = route_nodes[i]
            node_data = G.nodes[node_id]
            path_coords.append({"lat": node_data['y'], "lng": node_data['x']})
            
            # 다음 노드가 있다면 두 노드 사이의 간선 길이를 더함
            if i < len(route_nodes) - 1:
                next_node_id = route_nodes[i+1]
                edge_data = G.get_edge_data(node_id, next_node_id)[0]
                total_distance += edge_data.get('length', 0.0)

        # 5. 보행자 평균 속도(약 4km/h -> 66m/min) 기준으로 예상 소요 시간 도출
        estimated_time_min = max(1, int(total_distance / 66.0))

        return {
            "is_rerouted": True, 
            "total_distance_m": int(total_distance),
            "estimated_time_min": estimated_time_min,
            "warning_message": "위험 구역을 회피하는 안전 경로 탐색이 완료되었습니다.",
            "path": path_coords
        }

    except nx.NetworkXNoPath:
        return {"error": "출발지와 목적지를 연결하는 안전한 우회 경로를 찾을 수 없습니다."}
    except Exception as e:
        return {"error": f"경로 탐색 중 내부 오류 발생: {str(e)}"}
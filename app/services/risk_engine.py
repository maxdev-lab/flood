import pandas as pd
import numpy as np
import os
import joblib

# 모델 파일이 저장될 경로 (나중에 실제 학습된 .pkl 파일을 여기에 넣으시면 됩니다)
MODEL_PATH = "models/rf_flood_model_v1.pkl"

def load_ai_model():
    """Scikit-learn 머신러닝 모델을 메모리에 로드합니다."""
    if os.path.exists(MODEL_PATH):
        print(f"✅ AI 모델 로드 완료: {MODEL_PATH}")
        return joblib.load(MODEL_PATH)
    else:
        print("⚠️ 학습된 AI 모델(.pkl)을 찾을 수 없습니다. 임시(Dummy) 추론 로직을 사용합니다.")
        return None

# 서버 구동 시 모델을 한 번만 로드하여 메모리에 상주 (속도 최적화)
rf_model = load_ai_model()

def calculate_static_risk(grids_df: pd.DataFrame) -> pd.Series:
    """
    정적 지형 정보(고도, 경사, 불투수율)를 바탕으로 AI 모델을 통해 기본 취약도를 산출합니다.
    """
    # 1. AI 모델이 있을 경우: 실제 Scikit-learn 모델 추론 (predict_proba)
    if rf_model is not None:
        # 모델 학습 시 사용했던 피처(Feature) 컬럼만 추출
        features = grids_df[['elevation', 'slope', 'impervious_rate']]
        # 침수 클래스(예: 1)에 속할 확률(0.0 ~ 1.0) 반환
        static_risk = rf_model.predict_proba(features)[:, 1] 
        return pd.Series(static_risk, index=grids_df.index)
    
    # 2. AI 모델이 없을 경우: 임시 규칙 기반(Rule-based) 가중치 부여
    # 고도가 낮고(-), 불투수율이 높을수록(+) 기본 위험도가 높다고 가정
    base_risk = (100 - grids_df['elevation']) * 0.005 + (grids_df['impervious_rate'] * 0.002)
    # 0.0 ~ 1.0 사이로 값 정규화(Clipping)
    return base_risk.clip(lower=0.1, upper=0.8)

def apply_dynamic_factors(static_risk: pd.Series, rainfall: float, sewage_level: float) -> pd.Series:
    """
    정적 위험도에 실시간 동적 데이터(강수량, 수위)를 곱하여 최종 위험도를 산출합니다.
    공식: 최종 위험 등급 = f(정적 취약 가중치) X g(동적 실시간 요인)
    """
    # 동적 실시간 요인(g) 계산
    # 예시 로직: 강수량이 10mm를 넘거나 하수관 수위가 0.5m를 넘으면 가중치 급증
    rain_factor = 1.0 + (rainfall / 50.0)       # 50mm 기준 비례 증가
    sewage_factor = 1.0 + (sewage_level / 2.0)  # 2m 기준 비례 증가
    
    dynamic_multiplier = rain_factor * sewage_factor
    
    # 최종 연산 및 최대치 1.0 제한
    final_score = static_risk * dynamic_multiplier
    return final_score.clip(lower=0.0, upper=1.0)

def get_risk_level(score: float) -> str:
    """위험도 수치(0.0~1.0)를 바탕으로 텍스트 등급을 반환합니다."""
    if score >= 0.7:
        return "DANGER"  # 70% 이상: 위험 (우회 필요)
    elif score >= 0.4:
        return "WARNING" # 40% 이상: 주의
    else:
        return "SAFE"    # 40% 미만: 안전

def process_risk_pipeline(weather_data: list, sewage_data: list) -> pd.DataFrame:
    """
    스케줄러에서 호출할 메인 파이프라인 함수입니다.
    """
    print("🧠 AI 위험도 엔진 연산 시작...")
    
    # 1. 강남구 역삼동 격자(Grid) 정보를 DB에서 가져왔다고 가정 (더미 데이터프레임 생성)
    # 실제로는 PostGIS DB (grid_info 테이블)에서 가져와야 합니다.
    dummy_grids = pd.DataFrame({
        'grid_id': [101, 102, 103],
        'elevation': [15.0, 25.0, 10.0],       # 고도 (m)
        'slope': [2.5, 5.0, 1.2],              # 경사도
        'impervious_rate': [85.0, 60.0, 95.0]  # 불투수 면적률 (%)
    })
    
    # 2. 수집된 외부 데이터 파싱 (안전장치 포함)
    current_rainfall = weather_data[0].get('RN1', 0) if weather_data else 0
    current_sewage = sewage_data[0].get('MEA_WAL', 0) if sewage_data else 0
    
    # 3. 정적 침수 취약도 (f) 계산 (벡터 연산으로 초고속 처리)
    dummy_grids['static_risk'] = calculate_static_risk(dummy_grids)
    
    # 4. 최종 위험도 (f * g) 계산
    dummy_grids['final_risk_score'] = apply_dynamic_factors(
        dummy_grids['static_risk'], 
        current_rainfall, 
        current_sewage
    )
    
    # 5. 보기 편하게 등급(Label) 추가
    dummy_grids['risk_level'] = dummy_grids['final_risk_score'].apply(get_risk_level)
    
    print("✅ AI 위험도 연산 완료!")
    return dummy_grids
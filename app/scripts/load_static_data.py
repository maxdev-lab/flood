"""
scripts/load_static_data.py
────────────────────────────
정적데이터_수정_.csv → PostgreSQL ddc_grid 테이블 적재

실행 방법:
  cd C:\\Users\\zenon\\OneDrive\\Desktop\\졸작\\flood
  python scripts/load_static_data.py data/정적데이터_수정_.csv
"""
import os
import sys
import pandas as pd
import psycopg2
from psycopg2.extras import execute_values

DB_CONFIG = {
    "host":     os.getenv("DB_HOST",     "localhost"),
    "port":     int(os.getenv("DB_PORT", 5432)),
    "dbname":   os.getenv("DB_NAME",     "flood_db"),
    "user":     os.getenv("DB_USER",     "postgres"),
    "password": os.getenv("DB_PASSWORD", "1234"),
}

CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS ddc_grid (
    gid               INTEGER PRIMARY KEY,
    height_mean       FLOAT   NOT NULL,
    slope_mean        FLOAT   NOT NULL,
    l3_code           INTEGER NOT NULL,
    l3_name           VARCHAR(100) NOT NULL,
    land_risk         FLOAT   NOT NULL DEFAULT 1.0,
    part_area         FLOAT   NOT NULL DEFAULT 0.0,
    land_risk_value   FLOAT   NOT NULL DEFAULT 0.0,
    fldlv_freq        INTEGER NOT NULL DEFAULT 0,
    flood_level       INTEGER NOT NULL DEFAULT 0,
    f_shim            FLOAT   NOT NULL DEFAULT 0.0,
    f_area            FLOAT   NOT NULL DEFAULT 0.0,
    f_yr              INTEGER NOT NULL DEFAULT 0,
    flood_level_score FLOAT   NOT NULL DEFAULT 0.0,
    city_flood_risk   FLOAT   NOT NULL DEFAULT 0.0,
    f_shim_level      INTEGER NOT NULL DEFAULT 0,
    past_flood_risk   FLOAT   NOT NULL DEFAULT 0.0,
    station_id        INTEGER NOT NULL DEFAULT 0,
    station_name      VARCHAR(100) NOT NULL DEFAULT '없음',
    exit_no           FLOAT,
    freq_score        FLOAT   NOT NULL DEFAULT 0.0,
    is_station_zone   BOOLEAN NOT NULL DEFAULT FALSE,
    height_norm       FLOAT   NOT NULL,
    slope_norm        FLOAT   NOT NULL,
    final_score       INTEGER NOT NULL DEFAULT 0,
    geom              GEOMETRY(POLYGON, 4326)
);
"""

CREATE_INDEX_SQL = """
CREATE INDEX IF NOT EXISTS idx_ddc_grid_geom ON ddc_grid USING GIST (geom);
"""

INSERT_SQL = """
INSERT INTO ddc_grid (
    gid, height_mean, slope_mean, l3_code, l3_name,
    land_risk, part_area, land_risk_value,
    fldlv_freq, flood_level, f_shim, f_area, f_yr, flood_level_score,
    city_flood_risk, f_shim_level, past_flood_risk,
    station_id, station_name, exit_no, freq_score, is_station_zone,
    height_norm, slope_norm, final_score
) VALUES %s
ON CONFLICT (gid) DO UPDATE SET
    height_mean       = EXCLUDED.height_mean,
    slope_mean        = EXCLUDED.slope_mean,
    land_risk_value   = EXCLUDED.land_risk_value,
    flood_level_score = EXCLUDED.flood_level_score,
    city_flood_risk   = EXCLUDED.city_flood_risk,
    past_flood_risk   = EXCLUDED.past_flood_risk,
    freq_score        = EXCLUDED.freq_score,
    is_station_zone   = EXCLUDED.is_station_zone,
    height_norm       = EXCLUDED.height_norm,
    slope_norm        = EXCLUDED.slope_norm,
    final_score       = EXCLUDED.final_score;
"""


def load_and_clean(path: str) -> pd.DataFrame:
    print(f"[1/4] CSV 로딩: {path}")
    df = pd.read_csv(path, encoding="euc-kr")

    # 컬럼명 통일
    df = df.rename(columns={
        "FLDLV_FREQ":       "fldlv_freq",
        "floodLevel":       "flood_level",
        "F_SHIM":           "f_shim",
        "F_AREA":           "f_area",
        "F_YR":             "f_yr",
        "floodLevel_score": "flood_level_score",
        "F_SHIM_level":     "f_shim_level",
        "L3_CODE":          "l3_code",
        "L3_NAME":          "l3_name",
    })

    # is_station_zone: '참'/'거짓' → Boolean
    df["is_station_zone"] = df["is_station_zone"].map({"참": True, "거짓": False}).fillna(False)

    # NULL 처리
    df["exit_no"]      = df["exit_no"].where(pd.notna(df["exit_no"]), None)
    df["station_name"] = df["station_name"].fillna("없음")

    # 수치형 변환
    float_cols = ["height_mean","slope_mean","land_risk","part_area","land_risk_value",
                  "f_shim","f_area","flood_level_score","city_flood_risk",
                  "past_flood_risk","freq_score","height_norm","slope_norm"]
    for col in float_cols:
        df[col] = pd.to_numeric(df[col], errors="coerce").fillna(0.0)

    int_cols = ["grid_id","l3_code","fldlv_freq","flood_level","f_yr","f_shim_level",
                "station_id","final_score"]
    for col in int_cols:
        df[col] = pd.to_numeric(df[col], errors="coerce").fillna(0).astype(int)

    print(f"[2/4] 정제 완료: {len(df)}행")
    return df


def to_tuples(df: pd.DataFrame) -> list:
    rows = []
    for _, r in df.iterrows():
        rows.append((
            int(r["grid_id"]),
            float(r["height_mean"]),   float(r["slope_mean"]),
            int(r["l3_code"]),         str(r["l3_name"]),
            float(r["land_risk"]),     float(r["part_area"]),
            float(r["land_risk_value"]),
            int(r["fldlv_freq"]),      int(r["flood_level"]),
            float(r["f_shim"]),        float(r["f_area"]),
            int(r["f_yr"]),            float(r["flood_level_score"]),
            float(r["city_flood_risk"]),
            int(r["f_shim_level"]),    float(r["past_flood_risk"]),
            int(r["station_id"]),      str(r["station_name"]),
            float(r["exit_no"]) if r["exit_no"] is not None else None,
            float(r["freq_score"]),    bool(r["is_station_zone"]),
            float(r["height_norm"]),   float(r["slope_norm"]),
            int(r["final_score"]),
        ))
    return rows


def insert(rows: list) -> None:
    print(f"[3/4] DB 적재 중... ({len(rows)}행)")
    conn = psycopg2.connect(**DB_CONFIG)
    try:
        with conn:
            with conn.cursor() as cur:
                cur.execute("CREATE EXTENSION IF NOT EXISTS postgis;")
                cur.execute(CREATE_TABLE_SQL)
                cur.execute(CREATE_INDEX_SQL)
                execute_values(cur, INSERT_SQL, rows, page_size=500)
        print(f"      → {len(rows)}행 upsert 완료")
    finally:
        conn.close()


def verify(expected: int) -> None:
    print("[4/4] 검증 중...")
    conn = psycopg2.connect(**DB_CONFIG)
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*), MIN(final_score), MAX(final_score) FROM ddc_grid;")
        cnt, mn, mx = cur.fetchone()
    conn.close()
    print(f"      → 총 {cnt}행 (기대: {expected}), score 범위: {mn}~{mx}")
    assert cnt == expected, f"적재 수 불일치: {cnt} != {expected}"
    print("      ✓ 검증 통과")


if __name__ == "__main__":
    csv_path = sys.argv[1] if len(sys.argv) > 1 else "data/정적데이터_수정_.csv"
    df   = load_and_clean(csv_path)
    rows = to_tuples(df)
    insert(rows)
    verify(len(df))
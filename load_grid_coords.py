import pandas as pd
import psycopg2
from psycopg2.extras import execute_values

df = pd.read_csv('data/격자좌표및위험도.csv')
print(f"CSV 로드: {len(df)}행")

conn = psycopg2.connect(host='localhost', port=5432, dbname='flood_db', user='postgres', password='1234')
cur = conn.cursor()

cur.execute("""
CREATE TABLE IF NOT EXISTS grid_coords (
    grid_id   INTEGER PRIMARY KEY,
    latitude  FLOAT   NOT NULL,
    longitude FLOAT   NOT NULL,
    final_risk FLOAT  NOT NULL DEFAULT 0.0
);
""")
cur.execute("CREATE INDEX IF NOT EXISTS idx_grid_coords_id ON grid_coords (grid_id);")

rows = [
    (int(r['grid_id']), float(r['latitude']), float(r['longitude']), float(r['Final_Risk']))
    for _, r in df.iterrows()
]

execute_values(cur, """
    INSERT INTO grid_coords (grid_id, latitude, longitude, final_risk)
    VALUES %s
    ON CONFLICT (grid_id) DO UPDATE
        SET latitude   = EXCLUDED.latitude,
            longitude  = EXCLUDED.longitude,
            final_risk = EXCLUDED.final_risk
""", rows, page_size=500)

conn.commit()
cur.execute("SELECT COUNT(*) FROM grid_coords;")
print(f"적재 완료: {cur.fetchone()[0]}행")
cur.close()
conn.close()

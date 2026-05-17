import pandas as pd, psycopg2
from psycopg2.extras import execute_values

df = pd.read_csv('data/정적데이터(수정).csv', encoding='euc-kr')
df = df.rename(columns={'FLDLV_FREQ':'fldlv_freq','floodLevel':'flood_level','F_SHIM':'f_shim','F_AREA':'f_area','F_YR':'f_yr','floodLevel_score':'flood_level_score','F_SHIM_level':'f_shim_level','L3_CODE':'l3_code','L3_NAME':'l3_name'})
df['is_station_zone'] = df['is_station_zone'].map({'참':True,'거짓':False}).fillna(False)
df['exit_no'] = df['exit_no'].where(pd.notna(df['exit_no']), None)
df['station_name'] = df['station_name'].fillna('없음')
for col in ['height_mean','slope_mean','land_risk','part_area','land_risk_value','f_shim','f_area','flood_level_score','city_flood_risk','past_flood_risk','freq_score','height_norm','slope_norm']:
    df[col] = pd.to_numeric(df[col], errors='coerce').fillna(0.0)
for col in ['grid_id','l3_code','fldlv_freq','flood_level','f_yr','f_shim_level','station_id','final_score']:
    df[col] = pd.to_numeric(df[col], errors='coerce').fillna(0).astype(int)

conn = psycopg2.connect(host='localhost',port=5432,dbname='flood_db',user='postgres',password='1234')
cur = conn.cursor()
cur.execute('CREATE EXTENSION IF NOT EXISTS postgis;')
cur.execute("""CREATE TABLE IF NOT EXISTS ddc_grid (
    gid INTEGER PRIMARY KEY, height_mean FLOAT NOT NULL, slope_mean FLOAT NOT NULL,
    l3_code INTEGER NOT NULL, l3_name VARCHAR(100) NOT NULL,
    land_risk FLOAT NOT NULL DEFAULT 1.0, part_area FLOAT NOT NULL DEFAULT 0.0,
    land_risk_value FLOAT NOT NULL DEFAULT 0.0, fldlv_freq INTEGER NOT NULL DEFAULT 0,
    flood_level INTEGER NOT NULL DEFAULT 0, f_shim FLOAT NOT NULL DEFAULT 0.0,
    f_area FLOAT NOT NULL DEFAULT 0.0, f_yr INTEGER NOT NULL DEFAULT 0,
    flood_level_score FLOAT NOT NULL DEFAULT 0.0, city_flood_risk FLOAT NOT NULL DEFAULT 0.0,
    f_shim_level INTEGER NOT NULL DEFAULT 0, past_flood_risk FLOAT NOT NULL DEFAULT 0.0,
    station_id INTEGER NOT NULL DEFAULT 0, station_name VARCHAR(100) NOT NULL DEFAULT '없음',
    exit_no FLOAT, freq_score FLOAT NOT NULL DEFAULT 0.0,
    is_station_zone BOOLEAN NOT NULL DEFAULT FALSE,
    height_norm FLOAT NOT NULL, slope_norm FLOAT NOT NULL,
    final_score INTEGER NOT NULL DEFAULT 0, geom GEOMETRY(POLYGON,4326));""")

rows = []
for _, r in df.iterrows():
    rows.append((
        int(r['grid_id']),float(r['height_mean']),float(r['slope_mean']),
        int(r['l3_code']),str(r['l3_name']),float(r['land_risk']),float(r['part_area']),
        float(r['land_risk_value']),int(r['fldlv_freq']),int(r['flood_level']),
        float(r['f_shim']),float(r['f_area']),int(r['f_yr']),float(r['flood_level_score']),
        float(r['city_flood_risk']),int(r['f_shim_level']),float(r['past_flood_risk']),
        int(r['station_id']),str(r['station_name']),
        float(r['exit_no']) if r['exit_no'] is not None else None,
        float(r['freq_score']),bool(r['is_station_zone']),
        float(r['height_norm']),float(r['slope_norm']),int(r['final_score'])
    ))

execute_values(cur, '''INSERT INTO ddc_grid (
    gid,height_mean,slope_mean,l3_code,l3_name,land_risk,part_area,land_risk_value,
    fldlv_freq,flood_level,f_shim,f_area,f_yr,flood_level_score,city_flood_risk,
    f_shim_level,past_flood_risk,station_id,station_name,exit_no,freq_score,
    is_station_zone,height_norm,slope_norm,final_score) VALUES %s
    ON CONFLICT (gid) DO NOTHING''', rows, page_size=500)

conn.commit()
cur.execute('SELECT COUNT(*) FROM ddc_grid;')
print(f'적재 완료: {cur.fetchone()[0]}행')
cur.close()
conn.close()

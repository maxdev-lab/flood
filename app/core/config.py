from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    # 1. DB 주소
    DATABASE_URL: str
    
    # 2. 공공 데이터 API 키 (기본값 빈 문자열)
    KMA_API_KEY: str = ""
    SEOUL_OPEN_API_KEY: str = ""
    
    # 3. 지도 및 경로 탐색 API 키
    NAVER_CLIENT_ID: str = ""
    NAVER_CLIENT_SECRET: str = ""
    GOOGLE_MAPS_API_KEY: str = ""
    
    # 4. FCM 및 시스템 설정
    GOOGLE_APPLICATION_CREDENTIALS: str = ""
    APP_ENV: str = "development"
    SECRET_KEY: str = "default_secret_key"

    class Config:
        env_file = ".env"
        # 핵심 해결책: .env에 파일에 정의되지 않은 추가 변수가 있어도 에러를 내지 않고 무시함
        extra = "ignore" 

settings = Settings()
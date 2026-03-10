from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base
from app.core.config import settings  # 추가됨: 중앙 관리되는 설정 객체 임포트

# settings 객체를 통해 .env 파일의 DATABASE_URL을 안전하게 로드
SQLALCHEMY_DATABASE_URL = settings.DATABASE_URL

# SQLAlchemy 엔진 생성 (PostgreSQL 연결)
engine = create_engine(SQLALCHEMY_DATABASE_URL)

# 데이터베이스 세션 팩토리 생성 (자동 커밋/플러시 방지)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

# ORM 모델들이 상속받을 기본 클래스
Base = declarative_base()

# FastAPI 의존성 주입(Dependency Injection)용 DB 세션 생성 함수
# API 요청이 들어올 때마다 세션을 열고, 처리가 끝나면 안전하게 닫아줍니다.
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
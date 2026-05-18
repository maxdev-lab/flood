from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base
from app.core.config import settings  # 異붽??? 以묒븰 愿由щ릺???ㅼ젙 媛앹껜 ?꾪룷??

# settings 媛앹껜瑜??듯빐 .env ?뚯씪??DATABASE_URL???덉쟾?섍쾶 濡쒕뱶
SQLALCHEMY_DATABASE_URL = settings.DATABASE_URL.replace("postgresql+asyncpg://", "postgresql://")

# SQLAlchemy ?붿쭊 ?앹꽦 (PostgreSQL ?곌껐)
engine = create_engine(SQLALCHEMY_DATABASE_URL)

# ?곗씠?곕쿋?댁뒪 ?몄뀡 ?⑺넗由??앹꽦 (?먮룞 而ㅻ컠/?뚮윭??諛⑹?)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

# ORM 紐⑤뜽?ㅼ씠 ?곸냽諛쏆쓣 湲곕낯 ?대옒??
Base = declarative_base()

# FastAPI ?섏〈??二쇱엯(Dependency Injection)??DB ?몄뀡 ?앹꽦 ?⑥닔
# API ?붿껌???ㅼ뼱???뚮쭏???몄뀡???닿퀬, 泥섎━媛 ?앸굹硫??덉쟾?섍쾶 ?レ븘以띾땲??
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

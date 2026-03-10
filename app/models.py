from sqlalchemy import Column, Integer, String, Float, Boolean, ForeignKey, DateTime
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func
from geoalchemy2 import Geometry
from .database import Base

class User(Base):
    __tablename__ = "users"

    user_id = Column(String, primary_key=True, index=True)
    fcm_token = Column(String, nullable=True)
    current_loc = Column(Geometry('POINT', srid=4326), nullable=True) # 공간 데이터
    updated_at = Column(DateTime(timezone=True), onupdate=func.now())

    routes = relationship("RouteHistory", back_populates="user")

class GridInfo(Base):
    __tablename__ = "grid_info"

    grid_id = Column(Integer, primary_key=True, index=True)
    geom = Column(Geometry('POLYGON', srid=4326), nullable=False) # 40m 격자 폴리곤
    elevation = Column(Float, nullable=True)
    slope = Column(Float, nullable=True)
    impervious_rate = Column(Float, nullable=True)
    static_risk_score = Column(Float, nullable=True)

    realtime_risks = relationship("RealtimeRisk", back_populates="grid")

class RealtimeRisk(Base):
    __tablename__ = "realtime_risk"

    log_id = Column(Integer, primary_key=True, index=True, autoincrement=True)
    grid_id = Column(Integer, ForeignKey("grid_info.grid_id"))
    rainfall = Column(Float, nullable=True)
    sewage_level = Column(Float, nullable=True)
    final_risk_score = Column(Float, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    grid = relationship("GridInfo", back_populates="realtime_risks")

class RouteHistory(Base):
    __tablename__ = "route_history"

    route_id = Column(Integer, primary_key=True, index=True, autoincrement=True)
    user_id = Column(String, ForeignKey("users.user_id"))
    start_geom = Column(Geometry('POINT', srid=4326), nullable=True)
    end_geom = Column(Geometry('POINT', srid=4326), nullable=True)
    is_rerouted = Column(Boolean, default=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    user = relationship("User", back_populates="routes")
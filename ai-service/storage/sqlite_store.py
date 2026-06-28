# -*- coding: utf-8 -*-
"""SQLite3存储实现（默认存储方案）"""

import sqlite3
import json
import time
import os
import threading
from typing import List, Optional, Dict, Any
from .base import StorageProvider


class SQLiteStore(StorageProvider):
    """SQLite3存储实现，内置TTL自动清理"""

    def __init__(self, db_path: str = "./data/shadow_eval.db",
                 hot_ttl_days: int = 3, full_ttl_days: int = 30):
        self.db_path = os.path.abspath(db_path)
        self.hot_ttl_days = hot_ttl_days
        self.full_ttl_days = full_ttl_days
        self._lock = threading.Lock()

        os.makedirs(os.path.dirname(self.db_path), exist_ok=True)
        self._init_db()

    def _get_conn(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA cache_size=-8000")
        return conn

    def _init_db(self):
        with self._get_conn() as conn:
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS shadow_raw_data (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    station_id TEXT NOT NULL,
                    epoch_millis INTEGER NOT NULL,
                    original_n REAL DEFAULT 0.0,
                    original_e REAL DEFAULT 0.0,
                    original_u REAL DEFAULT 0.0,
                    velocity_n REAL DEFAULT 0.0,
                    velocity_e REAL DEFAULT 0.0,
                    velocity_u REAL DEFAULT 0.0,
                    acceleration_n REAL DEFAULT 0.0,
                    acceleration_e REAL DEFAULT 0.0,
                    acceleration_u REAL DEFAULT 0.0,
                    f1_north REAL DEFAULT 0.0,
                    f2_east REAL DEFAULT 0.0,
                    f3_up REAL DEFAULT 0.0,
                    f4_horizontal_rate REAL DEFAULT 0.0,
                    f5_vertical_rate REAL DEFAULT 0.0,
                    f6_temporal_residual REAL DEFAULT 0.0,
                    f7_spatial_residual REAL DEFAULT 0.0,
                    f8_neighbor_ratio REAL DEFAULT 0.0,
                    f9_quality_score REAL DEFAULT 0.0,
                    f10_stability REAL DEFAULT 0.0,
                    created_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(station_id, epoch_millis)
                );

                CREATE TABLE IF NOT EXISTS shadow_eval_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    station_id TEXT NOT NULL,
                    epoch_millis INTEGER NOT NULL,
                    original_n REAL DEFAULT 0.0,
                    original_e REAL DEFAULT 0.0,
                    original_u REAL DEFAULT 0.0,
                    candidate_n REAL DEFAULT 0.0,
                    candidate_e REAL DEFAULT 0.0,
                    candidate_u REAL DEFAULT 0.0,
                    have_candidate INTEGER DEFAULT 0,
                    candidate_type TEXT DEFAULT 'NONE',
                    replace_suggest TEXT DEFAULT 'NOT_SUGGEST_REPLACE',
                    rrcf_score REAL DEFAULT 0.0,
                    lstm_result TEXT DEFAULT '',
                    confidence REAL DEFAULT 0.0,
                    risk_level TEXT DEFAULT 'LOW',
                    deform_type TEXT DEFAULT 'UNCERTAIN',
                    inference_time_ms REAL DEFAULT 0.0,
                    model_version TEXT DEFAULT '',
                    created_at TEXT DEFAULT (datetime('now')),
                    UNIQUE(station_id, epoch_millis)
                );

                CREATE TABLE IF NOT EXISTS model_versions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    version TEXT NOT NULL UNIQUE,
                    rrcf_path TEXT DEFAULT '',
                    lstm_path TEXT DEFAULT '',
                    norm_path TEXT DEFAULT '',
                    train_samples INTEGER DEFAULT 0,
                    train_rounds INTEGER DEFAULT 0,
                    avg_loss REAL DEFAULT 0.0,
                    accuracy REAL DEFAULT 0.0,
                    is_active INTEGER DEFAULT 0,
                    created_at TEXT DEFAULT (datetime('now'))
                );

                CREATE INDEX IF NOT EXISTS idx_raw_station_time
                    ON shadow_raw_data(station_id, epoch_millis);
                CREATE INDEX IF NOT EXISTS idx_raw_created
                    ON shadow_raw_data(created_at);
                CREATE INDEX IF NOT EXISTS idx_eval_station_time
                    ON shadow_eval_results(station_id, epoch_millis);
                CREATE INDEX IF NOT EXISTS idx_eval_created
                    ON shadow_eval_results(created_at);
                CREATE INDEX IF NOT EXISTS idx_eval_risk
                    ON shadow_eval_results(risk_level);
                CREATE INDEX IF NOT EXISTS idx_eval_candidate
                    ON shadow_eval_results(have_candidate);
            """)

    def save_raw_data(self, station_id: str, epoch_millis: int,
                      features: Dict[str, Any]) -> bool:
        with self._lock:
            try:
                with self._get_conn() as conn:
                    conn.execute("""
                        INSERT OR REPLACE INTO shadow_raw_data
                        (station_id, epoch_millis, original_n, original_e, original_u,
                         velocity_n, velocity_e, velocity_u,
                         acceleration_n, acceleration_e, acceleration_u,
                         f1_north, f2_east, f3_up,
                         f4_horizontal_rate, f5_vertical_rate,
                         f6_temporal_residual, f7_spatial_residual,
                         f8_neighbor_ratio, f9_quality_score, f10_stability)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, (
                        station_id, epoch_millis,
                        features.get('originalN', 0.0),
                        features.get('originalE', 0.0),
                        features.get('originalU', 0.0),
                        features.get('velocityN', 0.0),
                        features.get('velocityE', 0.0),
                        features.get('velocityU', 0.0),
                        features.get('accelerationN', 0.0),
                        features.get('accelerationE', 0.0),
                        features.get('accelerationU', 0.0),
                        features.get('f1North', 0.0),
                        features.get('f2East', 0.0),
                        features.get('f3Up', 0.0),
                        features.get('f4HorizontalRate', 0.0),
                        features.get('f5VerticalRate', 0.0),
                        features.get('f6TemporalResidual', 0.0),
                        features.get('f7SpatialResidual', 0.0),
                        features.get('f8NeighborRatio', 0.0),
                        features.get('f9QualityScore', 0.0),
                        features.get('f10Stability', 0.0),
                    ))
                return True
            except Exception as e:
                print(f"[SQLiteStore] save_raw_data error: {e}")
                return False

    def save_eval_result(self, station_id: str, epoch_millis: int,
                         result: Dict[str, Any]) -> bool:
        with self._lock:
            try:
                with self._get_conn() as conn:
                    conn.execute("""
                        INSERT OR REPLACE INTO shadow_eval_results
                        (station_id, epoch_millis,
                         original_n, original_e, original_u,
                         candidate_n, candidate_e, candidate_u,
                         have_candidate, candidate_type, replace_suggest,
                         rrcf_score, lstm_result, confidence,
                         risk_level, deform_type,
                         inference_time_ms, model_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, (
                        station_id, epoch_millis,
                        result.get('originalN', 0.0),
                        result.get('originalE', 0.0),
                        result.get('originalU', 0.0),
                        result.get('candidateN', 0.0),
                        result.get('candidateE', 0.0),
                        result.get('candidateU', 0.0),
                        result.get('haveCandidate', 0),
                        result.get('candidateType', 'NONE'),
                        result.get('replaceSuggest', 'NOT_SUGGEST_REPLACE'),
                        result.get('rrcfScore', 0.0),
                        result.get('lstmResult', ''),
                        result.get('confidence', 0.0),
                        result.get('riskLevel', 'LOW'),
                        result.get('deformType', 'UNCERTAIN'),
                        result.get('inferenceTimeMs', 0.0),
                        result.get('modelVersion', ''),
                    ))
                return True
            except Exception as e:
                print(f"[SQLiteStore] save_eval_result error: {e}")
                return False

    def query_results(self, station_id: str,
                      limit: int = 100) -> List[Dict[str, Any]]:
        try:
            with self._get_conn() as conn:
                rows = conn.execute(
                    "SELECT * FROM shadow_eval_results "
                    "WHERE station_id = ? ORDER BY epoch_millis DESC LIMIT ?",
                    (station_id, limit)
                ).fetchall()
                return [dict(r) for r in rows]
        except Exception as e:
            print(f"[SQLiteStore] query_results error: {e}")
            return []

    def query_raw_data(self, station_id: str,
                       start_time: int = 0, end_time: int = 0,
                       limit: int = 10000) -> List[Dict[str, Any]]:
        try:
            with self._get_conn() as conn:
                if start_time > 0 and end_time > 0:
                    rows = conn.execute(
                        "SELECT * FROM shadow_raw_data "
                        "WHERE station_id = ? AND epoch_millis BETWEEN ? AND ? "
                        "ORDER BY epoch_millis ASC LIMIT ?",
                        (station_id, start_time, end_time, limit)
                    ).fetchall()
                else:
                    rows = conn.execute(
                        "SELECT * FROM shadow_raw_data "
                        "WHERE station_id = ? "
                        "ORDER BY epoch_millis ASC LIMIT ?",
                        (station_id, limit)
                    ).fetchall()
                return [dict(r) for r in rows]
        except Exception as e:
            print(f"[SQLiteStore] query_raw_data error: {e}")
            return []

    def get_training_samples(self, start_time: int, end_time: int,
                             limit: int = 10000) -> List[Dict[str, Any]]:
        try:
            with self._get_conn() as conn:
                rows = conn.execute(
                    "SELECT * FROM shadow_raw_data "
                    "WHERE epoch_millis BETWEEN ? AND ? "
                    "ORDER BY station_id, epoch_millis ASC LIMIT ?",
                    (start_time, end_time, limit)
                ).fetchall()
                return [dict(r) for r in rows]
        except Exception as e:
            print(f"[SQLiteStore] get_training_samples error: {e}")
            return []

    def _count_rows(self, table: str) -> int:
        try:
            with self._get_conn() as conn:
                return conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
        except Exception:
            return 0

    def cleanup_expired(self) -> int:
        total = 0
        try:
            with self._lock:
                with self._get_conn() as conn:
                    if self.full_ttl_days > 0:
                        cur = conn.execute(
                            "DELETE FROM shadow_eval_results "
                            "WHERE created_at < datetime('now', ?)",
                            (f'-{self.full_ttl_days} days',)
                        )
                        total += cur.rowcount
                    if self.hot_ttl_days > 0:
                        cur = conn.execute(
                            "DELETE FROM shadow_raw_data "
                            "WHERE created_at < datetime('now', ?)",
                            (f'-{self.hot_ttl_days} days',)
                        )
                        total += cur.rowcount
                    conn.execute("PRAGMA optimize")
                    conn.execute("VACUUM")
        except Exception as e:
            print(f"[SQLiteStore] cleanup_expired error: {e}")
        return total

    def is_available(self) -> bool:
        try:
            with self._get_conn() as conn:
                conn.execute("SELECT 1")
            return True
        except Exception:
            return False

    def get_stats(self) -> Dict[str, Any]:
        return {
            'type': 'sqlite',
            'db_path': self.db_path,
            'raw_count': self._count_rows('shadow_raw_data'),
            'eval_count': self._count_rows('shadow_eval_results'),
            'version_count': self._count_rows('model_versions'),
            'hot_ttl_days': self.hot_ttl_days,
            'full_ttl_days': self.full_ttl_days,
        }

    def save_model_version(self, version: str, rrcf_path: str,
                           lstm_path: str, norm_path: str,
                           train_samples: int = 0, train_rounds: int = 0,
                           avg_loss: float = 0.0, accuracy: float = 0.0) -> bool:
        with self._lock:
            try:
                with self._get_conn() as conn:
                    conn.execute(
                        "UPDATE model_versions SET is_active = 0 WHERE is_active = 1"
                    )
                    conn.execute(
                        "INSERT OR REPLACE INTO model_versions "
                        "(version, rrcf_path, lstm_path, norm_path, "
                        "train_samples, train_rounds, avg_loss, accuracy, is_active) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                        (version, rrcf_path, lstm_path, norm_path,
                         train_samples, train_rounds, avg_loss, accuracy)
                    )
                return True
            except Exception as e:
                print(f"[SQLiteStore] save_model_version error: {e}")
                return False

    def get_active_version(self) -> Optional[Dict[str, Any]]:
        try:
            with self._get_conn() as conn:
                row = conn.execute(
                    "SELECT * FROM model_versions WHERE is_active = 1"
                ).fetchone()
                return dict(row) if row else None
        except Exception:
            return None

    def get_version_history(self, limit: int = 15) -> List[Dict[str, Any]]:
        try:
            with self._get_conn() as conn:
                rows = conn.execute(
                    "SELECT * FROM model_versions ORDER BY id DESC LIMIT ?",
                    (limit,)
                ).fetchall()
                return [dict(r) for r in rows]
        except Exception:
            return []
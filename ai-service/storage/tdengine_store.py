# -*- coding: utf-8 -*-
"""TDengine时序库存储实现（REST API连接方式）

数据流: Java端调用 /api/v1/infer 传入特征 → AI端推理 + 存储到TDengine → 训练时从TDengine读取
"""

import time
import threading
import json
import base64
from typing import List, Optional, Dict, Any
from .base import StorageProvider


class TdengineStore(StorageProvider):
    """TDengine时序库存储实现，使用REST API连接，无需本地C客户端库"""

    def __init__(self, host: str = "localhost", port: int = 6042,
                 username: str = "root", password: str = "taosdata",
                 database: str = "gnss_shadow", table_prefix: str = "shadow_",
                 hot_ttl_days: int = 3, full_ttl_days: int = 30):
        self.host = host
        self.port = port
        self.username = username
        self.password = password
        self.database = database
        self.table_prefix = table_prefix
        self.hot_ttl_days = hot_ttl_days
        self.full_ttl_days = full_ttl_days
        self._lock = threading.Lock()
        self._url = f"http://{self.host}:{self.port}"
        self._auth_header = 'Basic ' + base64.b64encode(
            f"{self.username}:{self.password}".encode()
        ).decode()
        self._init_db()

    def _rest_sql(self, sql: str, database: str = None) -> Dict:
        import urllib.request
        db = database or self.database
        url = f"{self._url}/rest/sql/{db}"
        req = urllib.request.Request(
            url,
            data=sql.encode('utf-8'),
            headers={
                'Authorization': self._auth_header,
                'Content-Type': 'text/plain',
            },
        )
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode('utf-8'))

    def _execute(self, sql: str, database: str = None) -> bool:
        try:
            result = self._rest_sql(sql, database)
            return result.get('code', -1) == 0
        except Exception as e:
            print(f"[TdengineStore] execute error: {e}, sql={sql[:100]}")
            return False

    def _query(self, sql: str, database: str = None) -> List[Dict]:
        try:
            result = self._rest_sql(sql, database)
            if result.get('code', -1) != 0:
                print(f"[TdengineStore] query error: {result.get('desc', '')}")
                return []
            column_meta = result.get('column_meta', [])
            data = result.get('data', [])
            col_names = [c[0] for c in column_meta]
            rows = []
            for row in data:
                rows.append(dict(zip(col_names, row)))
            return rows
        except Exception as e:
            print(f"[TdengineStore] query error: {e}")
            return []

    def _init_db(self):
        try:
            self._execute(
                f"CREATE DATABASE IF NOT EXISTS {self.database} "
                f"PRECISION 'ms' KEEP {max(self.full_ttl_days, 30)} "
                f"BUFFER 96 WAL_LEVEL 1",
                database=None,
            )

            self._execute(f"""
                CREATE STABLE IF NOT EXISTS {self.table_prefix}raw_data (
                    ts TIMESTAMP,
                    original_n DOUBLE,
                    original_e DOUBLE,
                    original_u DOUBLE,
                    velocity_n DOUBLE,
                    velocity_e DOUBLE,
                    velocity_u DOUBLE,
                    acceleration_n DOUBLE,
                    acceleration_e DOUBLE,
                    acceleration_u DOUBLE,
                    f1_north DOUBLE,
                    f2_east DOUBLE,
                    f3_up DOUBLE,
                    f4_horizontal_rate DOUBLE,
                    f5_vertical_rate DOUBLE,
                    f6_temporal_residual DOUBLE,
                    f7_spatial_residual DOUBLE,
                    f8_neighbor_ratio DOUBLE,
                    f9_quality_score DOUBLE,
                    f10_stability DOUBLE
                ) TAGS (station_id NCHAR(64))
            """)

            self._execute(f"""
                CREATE STABLE IF NOT EXISTS {self.table_prefix}eval_results (
                    ts TIMESTAMP,
                    original_n DOUBLE,
                    original_e DOUBLE,
                    original_u DOUBLE,
                    candidate_n DOUBLE,
                    candidate_e DOUBLE,
                    candidate_u DOUBLE,
                    have_candidate TINYINT,
                    candidate_type NCHAR(32),
                    replace_suggest NCHAR(32),
                    rrcf_score DOUBLE,
                    lstm_result NCHAR(32),
                    confidence DOUBLE,
                    risk_level NCHAR(16),
                    deform_type NCHAR(32),
                    inference_time_ms BIGINT,
                    model_version NCHAR(64)
                ) TAGS (station_id NCHAR(64))
            """)

            self._execute(f"""
                CREATE TABLE IF NOT EXISTS {self.table_prefix}model_versions (
                    ts TIMESTAMP,
                    version NCHAR(64),
                    rrcf_path NCHAR(256),
                    lstm_path NCHAR(256),
                    norm_path NCHAR(256),
                    train_samples INT,
                    train_rounds INT,
                    avg_loss DOUBLE,
                    accuracy DOUBLE,
                    is_active TINYINT
                )
            """)

            print(f"[TdengineStore] Initialized, database={self.database}")
        except Exception as e:
            print(f"[TdengineStore] Init failed: {e}")

    def _sub_table_name(self, stable: str, station_id: str) -> str:
        safe_id = station_id.lower().replace('-', '_')
        return f"{self.table_prefix}{stable}_{safe_id}"

    def _ensure_sub_table(self, stable: str, station_id: str, tag_value: str):
        sub = self._sub_table_name(stable, station_id)
        self._execute(
            f"CREATE TABLE IF NOT EXISTS {sub} "
            f"USING {self.table_prefix}{stable} TAGS ('{tag_value}')"
        )

    def save_raw_data(self, station_id: str, epoch_millis: int,
                      features: Dict[str, Any]) -> bool:
        with self._lock:
            try:
                self._ensure_sub_table("raw_data", station_id, station_id)
                sub = self._sub_table_name("raw_data", station_id)
                ts = epoch_millis
                return self._execute(f"""
                    INSERT INTO {sub} (ts, original_n, original_e, original_u,
                        velocity_n, velocity_e, velocity_u,
                        acceleration_n, acceleration_e, acceleration_u,
                        f1_north, f2_east, f3_up,
                        f4_horizontal_rate, f5_vertical_rate,
                        f6_temporal_residual, f7_spatial_residual,
                        f8_neighbor_ratio, f9_quality_score, f10_stability)
                    VALUES ({ts}, {features.get('originalN', 0.0)}, {features.get('originalE', 0.0)},
                        {features.get('originalU', 0.0)}, {features.get('velocityN', 0.0)},
                        {features.get('velocityE', 0.0)}, {features.get('velocityU', 0.0)},
                        {features.get('accelerationN', 0.0)}, {features.get('accelerationE', 0.0)},
                        {features.get('accelerationU', 0.0)}, {features.get('f1North', 0.0)},
                        {features.get('f2East', 0.0)}, {features.get('f3Up', 0.0)},
                        {features.get('f4HorizontalRate', 0.0)}, {features.get('f5VerticalRate', 0.0)},
                        {features.get('f6TemporalResidual', 0.0)}, {features.get('f7SpatialResidual', 0.0)},
                        {features.get('f8NeighborRatio', 0.0)}, {features.get('f9QualityScore', 0.0)},
                        {features.get('f10Stability', 0.0)})
                """)
            except Exception as e:
                print(f"[TdengineStore] save_raw_data error: {e}")
                return False

    def save_eval_result(self, station_id: str, epoch_millis: int,
                         result: Dict[str, Any]) -> bool:
        with self._lock:
            try:
                self._ensure_sub_table("eval_results", station_id, station_id)
                sub = self._sub_table_name("eval_results", station_id)
                ts = epoch_millis
                have_candidate = 1 if result.get('haveCandidate', 0) else 0
                return self._execute(f"""
                    INSERT INTO {sub} (ts, original_n, original_e, original_u,
                        candidate_n, candidate_e, candidate_u,
                        have_candidate, candidate_type, replace_suggest,
                        rrcf_score, lstm_result, confidence,
                        risk_level, deform_type, inference_time_ms, model_version)
                    VALUES ({ts}, {result.get('originalN', 0.0)}, {result.get('originalE', 0.0)},
                        {result.get('originalU', 0.0)}, {result.get('candidateN', 0.0)},
                        {result.get('candidateE', 0.0)}, {result.get('candidateU', 0.0)},
                        {have_candidate}, '{result.get('candidateType', 'NONE')}',
                        '{result.get('replaceSuggest', 'NOT_SUGGEST_REPLACE')}',
                        {result.get('rrcfScore', 0.0)}, '{result.get('lstmResult', '')}',
                        {result.get('confidence', 0.0)}, '{result.get('riskLevel', 'LOW')}',
                        '{result.get('deformType', 'UNCERTAIN')}',
                        {int(result.get('inferenceTimeMs', 0))}, '{result.get('modelVersion', '')}')
                """)
            except Exception as e:
                print(f"[TdengineStore] save_eval_result error: {e}")
                return False

    def query_results(self, station_id: str,
                      limit: int = 100) -> List[Dict[str, Any]]:
        try:
            sub = self._sub_table_name("eval_results", station_id)
            rows = self._query(
                f"SELECT ts, original_n, original_e, original_u, "
                f"candidate_n, candidate_e, candidate_u, "
                f"have_candidate, candidate_type, replace_suggest, "
                f"rrcf_score, lstm_result, confidence, "
                f"risk_level, deform_type, inference_time_ms, model_version "
                f"FROM {sub} ORDER BY ts DESC LIMIT {limit}"
            )
            for r in rows:
                r['station_id'] = station_id
            return rows
        except Exception as e:
            print(f"[TdengineStore] query_results error: {e}")
            return []

    def query_raw_data(self, station_id: str,
                       start_time: int = 0, end_time: int = 0,
                       limit: int = 10000) -> List[Dict[str, Any]]:
        try:
            sub = self._sub_table_name("raw_data", station_id)
            where = ""
            if start_time > 0 and end_time > 0:
                where = f"WHERE ts >= {start_time} AND ts <= {end_time}"
            rows = self._query(
                f"SELECT ts, original_n, original_e, original_u, "
                f"velocity_n, velocity_e, velocity_u, "
                f"acceleration_n, acceleration_e, acceleration_u, "
                f"f1_north, f2_east, f3_up, "
                f"f4_horizontal_rate, f5_vertical_rate, "
                f"f6_temporal_residual, f7_spatial_residual, "
                f"f8_neighbor_ratio, f9_quality_score, f10_stability "
                f"FROM {sub} {where} ORDER BY ts ASC LIMIT {limit}"
            )
            for r in rows:
                r['station_id'] = station_id
            return rows
        except Exception as e:
            print(f"[TdengineStore] query_raw_data error: {e}")
            return []

    def get_training_samples(self, start_time: int, end_time: int,
                             limit: int = 10000) -> List[Dict[str, Any]]:
        try:
            rows = self._query(
                f"SELECT ts, station_id, original_n, original_e, original_u, "
                f"velocity_n, velocity_e, velocity_u, "
                f"acceleration_n, acceleration_e, acceleration_u, "
                f"f1_north, f2_east, f3_up, "
                f"f4_horizontal_rate, f5_vertical_rate, "
                f"f6_temporal_residual, f7_spatial_residual, "
                f"f8_neighbor_ratio, f9_quality_score, f10_stability "
                f"FROM {self.table_prefix}raw_data "
                f"WHERE ts >= {start_time} AND ts <= {end_time} "
                f"ORDER BY ts ASC LIMIT {limit}"
            )
            return rows
        except Exception as e:
            print(f"[TdengineStore] get_training_samples error: {e}")
            return []

    def cleanup_expired(self) -> int:
        total = 0
        try:
            if self.hot_ttl_days > 0:
                cutoff = int(time.time() * 1000) - self.hot_ttl_days * 86400000
                rows = self._query(
                    f"SELECT COUNT(*) as cnt FROM {self.table_prefix}raw_data "
                    f"WHERE ts < {cutoff}"
                )
                for r in rows:
                    total += r.get('cnt', 0)
                self._execute(
                    f"DELETE FROM {self.table_prefix}raw_data WHERE ts < {cutoff}"
                )
            if self.full_ttl_days > 0:
                cutoff = int(time.time() * 1000) - self.full_ttl_days * 86400000
                rows = self._query(
                    f"SELECT COUNT(*) as cnt FROM {self.table_prefix}eval_results "
                    f"WHERE ts < {cutoff}"
                )
                for r in rows:
                    total += r.get('cnt', 0)
                self._execute(
                    f"DELETE FROM {self.table_prefix}eval_results WHERE ts < {cutoff}"
                )
        except Exception as e:
            print(f"[TdengineStore] cleanup_expired error: {e}")
        return total

    def is_available(self) -> bool:
        try:
            result = self._rest_sql("SELECT SERVER_VERSION()")
            return result.get('code', -1) == 0
        except Exception:
            return False

    def get_stats(self) -> Dict[str, Any]:
        raw_count = 0
        eval_count = 0
        version_count = 0
        try:
            rows = self._query(
                f"SELECT COUNT(*) as cnt FROM {self.table_prefix}raw_data"
            )
            for r in rows:
                raw_count = r.get('cnt', 0)
            rows = self._query(
                f"SELECT COUNT(*) as cnt FROM {self.table_prefix}eval_results"
            )
            for r in rows:
                eval_count = r.get('cnt', 0)
            rows = self._query(
                f"SELECT COUNT(*) as cnt FROM {self.table_prefix}model_versions"
            )
            for r in rows:
                version_count = r.get('cnt', 0)
        except Exception:
            pass
        return {
            'type': 'tdengine',
            'host': self.host,
            'port': self.port,
            'database': self.database,
            'raw_count': raw_count,
            'eval_count': eval_count,
            'version_count': version_count,
            'hot_ttl_days': self.hot_ttl_days,
            'full_ttl_days': self.full_ttl_days,
        }

    def save_model_version(self, version: str, rrcf_path: str,
                           lstm_path: str, norm_path: str,
                           train_samples: int = 0, train_rounds: int = 0,
                           avg_loss: float = 0.0, accuracy: float = 0.0) -> bool:
        with self._lock:
            try:
                self._execute(
                    f"UPDATE {self.table_prefix}model_versions SET is_active = 0 "
                    f"WHERE is_active = 1"
                )
                ts = int(time.time() * 1000)
                return self._execute(f"""
                    INSERT INTO {self.table_prefix}model_versions
                    (ts, version, rrcf_path, lstm_path, norm_path,
                     train_samples, train_rounds, avg_loss, accuracy, is_active)
                    VALUES ({ts}, '{version}', '{rrcf_path}', '{lstm_path}', '{norm_path}',
                     {train_samples}, {train_rounds}, {avg_loss}, {accuracy}, 1)
                """)
            except Exception as e:
                print(f"[TdengineStore] save_model_version error: {e}")
                return False

    def get_active_version(self) -> Optional[Dict[str, Any]]:
        try:
            rows = self._query(
                f"SELECT ts, version, rrcf_path, lstm_path, norm_path, "
                f"train_samples, train_rounds, avg_loss, accuracy, is_active "
                f"FROM {self.table_prefix}model_versions "
                f"WHERE is_active = 1 LIMIT 1"
            )
            if rows:
                return rows[0]
            return None
        except Exception:
            return None

    def get_version_history(self, limit: int = 15) -> List[Dict[str, Any]]:
        try:
            rows = self._query(
                f"SELECT ts, version, rrcf_path, lstm_path, norm_path, "
                f"train_samples, train_rounds, avg_loss, accuracy, is_active "
                f"FROM {self.table_prefix}model_versions "
                f"ORDER BY ts DESC LIMIT {limit}"
            )
            return rows
        except Exception:
            return []
# -*- coding: utf-8 -*-
"""存储工厂"""

from .base import StorageProvider
from .sqlite_store import SQLiteStore


def create_storage(config: dict) -> StorageProvider:
    """根据配置创建存储实例"""
    storage_type = config.get('type', 'sqlite').lower()

    if storage_type == 'sqlite':
        sqlite_cfg = config.get('sqlite', {})
        return SQLiteStore(
            db_path=sqlite_cfg.get('db_path', './data/shadow_eval.db'),
            hot_ttl_days=sqlite_cfg.get('hot_ttl_days', 3),
            full_ttl_days=sqlite_cfg.get('full_ttl_days', 30),
        )

    elif storage_type == 'redis':
        try:
            from .redis_store import RedisStore
            redis_cfg = config.get('redis', {})
            return RedisStore(**redis_cfg)
        except ImportError:
            print("[StorageFactory] Redis not installed, fallback to SQLite")
            return SQLiteStore()

    elif storage_type == 'mysql':
        try:
            from .mysql_store import MySQLStore
            mysql_cfg = config.get('mysql', {})
            return MySQLStore(**mysql_cfg)
        except ImportError:
            print("[StorageFactory] MySQL not installed, fallback to SQLite")
            return SQLiteStore()

    else:
        print(f"[StorageFactory] Unknown storage type '{storage_type}', "
              f"fallback to SQLite")
        return SQLiteStore()
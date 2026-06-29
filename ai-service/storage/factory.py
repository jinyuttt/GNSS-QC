# -*- coding: utf-8 -*-
"""存储工厂 — 动态反射加载

设计原则:
- 通过配置 type 指定存储实现，支持任意实现了 StorageProvider 接口的类
- 内置快捷名: sqlite → SQLiteStore
- 自定义实现: type 指定完整模块路径 (如 ai_service.storage.redis_store.RedisStore)
- 加载失败自动 fallback 到 SQLiteStore
"""

import importlib
from .base import StorageProvider
from .sqlite_store import SQLiteStore

_BUILTIN_MAP = {
    'sqlite': ('ai_service.storage.sqlite_store', 'SQLiteStore'),
}


def create_storage(config: dict) -> StorageProvider:
    """根据配置动态创建存储实例

    配置格式:
      # 内置快捷名
      storage:
        type: "sqlite"
        sqlite:
          db_path: "./data/shadow_eval.db"

      # 自定义实现 (完整 模块路径.类名)
      storage:
        type: "ai_service.storage.redis_store.RedisStore"
        redis:
          host: "localhost"
          port: 6379

      # 第三方包
      storage:
        type: "my_package.timeseries_store.TimeseriesStore"
        timeseries:
          endpoint: "http://tsdb:8086"
    """
    storage_type = config.get('type', 'sqlite').strip()
    if not storage_type:
        storage_type = 'sqlite'

    if storage_type.lower() in _BUILTIN_MAP:
        module_path, class_name = _BUILTIN_MAP[storage_type.lower()]
    else:
        parts = storage_type.rsplit('.', 1)
        if len(parts) != 2:
            print(f"[StorageFactory] Invalid type format '{storage_type}', "
                  f"expected 'module.ClassName', fallback to SQLite")
            return _create_sqlite_fallback(config)
        module_path, class_name = parts

    try:
        module = importlib.import_module(module_path)
        cls = getattr(module, class_name)
    except (ImportError, AttributeError) as e:
        print(f"[StorageFactory] Cannot load '{module_path}.{class_name}': {e}, "
              f"fallback to SQLite")
        return _create_sqlite_fallback(config)

    if not (isinstance(cls, type) and issubclass(cls, StorageProvider)):
        print(f"[StorageFactory] '{module_path}.{class_name}' is not a "
              f"StorageProvider subclass, fallback to SQLite")
        return _create_sqlite_fallback(config)

    sub_key = _extract_sub_key(storage_type)
    sub_config = config.get(sub_key, {})
    try:
        instance = cls(**sub_config)
        if not instance.is_available():
            print(f"[StorageFactory] '{module_path}.{class_name}' "
                  f"is_available()=False, fallback to SQLite")
            return _create_sqlite_fallback(config)
        print(f"[StorageFactory] Loaded: {module_path}.{class_name}")
        return instance
    except Exception as e:
        print(f"[StorageFactory] Failed to instantiate "
              f"'{module_path}.{class_name}': {e}, fallback to SQLite")
        return _create_sqlite_fallback(config)


def _extract_sub_key(storage_type: str) -> str:
    if storage_type.lower() in _BUILTIN_MAP:
        return storage_type.lower()
    class_name = storage_type.rsplit('.', 1)[-1]
    import re
    snake = re.sub(r'(?<!^)(?=[A-Z])', '_', class_name).lower()
    snake = snake.replace('_store', '').replace('_provider', '')
    return snake if snake else 'storage'


def _create_sqlite_fallback(config: dict) -> SQLiteStore:
    sqlite_cfg = config.get('sqlite', {})
    return SQLiteStore(
        db_path=sqlite_cfg.get('db_path', './data/shadow_eval.db'),
        hot_ttl_days=sqlite_cfg.get('hot_ttl_days', 3),
        full_ttl_days=sqlite_cfg.get('full_ttl_days', 30),
    )
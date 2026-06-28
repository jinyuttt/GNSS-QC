# -*- coding: utf-8 -*-
"""模型版本管理与热加载"""

import os
import time
import json
import shutil
import threading
from typing import Dict, Optional, Callable
from datetime import datetime


class ModelManager:
    """模型版本管理器，支持双实例热加载和自动回滚"""

    def __init__(self, snapshot_dir: str, keep_versions: int = 15,
                 auto_rollback: bool = True, error_threshold: float = 0.15):
        self.snapshot_dir = os.path.abspath(snapshot_dir)
        self.keep_versions = keep_versions
        self.auto_rollback = auto_rollback
        self.error_threshold = error_threshold

        self._current_version = 0
        self._version_log = []
        self._lock = threading.Lock()
        self._error_metrics = []
        self._hot_reload_callbacks = []

        os.makedirs(self.snapshot_dir, exist_ok=True)
        self._load_version_log()

    def _load_version_log(self):
        log_path = os.path.join(self.snapshot_dir, 'version_log.json')
        if os.path.exists(log_path):
            with open(log_path, 'r') as f:
                log = json.load(f)
            self._version_log = log.get('versions', [])
            self._current_version = log.get('current_version', 0)

    def _save_version_log(self):
        log_path = os.path.join(self.snapshot_dir, 'version_log.json')
        with open(log_path, 'w') as f:
            json.dump({
                'current_version': self._current_version,
                'versions': self._version_log,
            }, f, indent=2)

    def archive_version(self, rrcf: 'RRCFModel', lstm: 'LSTMAttentionModel',
                        normalizer: 'FeatureNormalizer',
                        train_samples: int = 0, train_rounds: int = 0,
                        avg_loss: float = 0.0, accuracy: float = 0.0) -> str:
        with self._lock:
            self._current_version += 1
            version = f"v{self._current_version}_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
            version_dir = os.path.join(self.snapshot_dir, version)
            os.makedirs(version_dir, exist_ok=True)

            rrcf_path = os.path.join(version_dir, 'rrcf')
            lstm_path = os.path.join(version_dir, 'lstm.pth')
            norm_path = os.path.join(version_dir, 'norm.params')

            rrcf.save(rrcf_path)
            lstm.save(lstm_path)
            normalizer.save(norm_path)

            entry = {
                'version': version,
                'rrcf_path': rrcf_path,
                'lstm_path': lstm_path,
                'norm_path': norm_path,
                'train_samples': train_samples,
                'train_rounds': train_rounds,
                'avg_loss': avg_loss,
                'accuracy': accuracy,
                'created_at': datetime.now().isoformat(),
            }
            self._version_log.append(entry)
            self._save_version_log()
            self._cleanup_old_versions()

            return version

    def _cleanup_old_versions(self):
        while len(self._version_log) > self.keep_versions:
            old = self._version_log.pop(0)
            old_dir = os.path.join(self.snapshot_dir, old['version'])
            if os.path.exists(old_dir):
                shutil.rmtree(old_dir, ignore_errors=True)
        self._save_version_log()

    def get_latest_version(self) -> Optional[Dict]:
        if self._version_log:
            return self._version_log[-1]
        return None

    def get_version(self, version: str) -> Optional[Dict]:
        for entry in self._version_log:
            if entry['version'] == version:
                return entry
        return None

    def get_previous_stable(self) -> Optional[Dict]:
        if len(self._version_log) >= 2:
            return self._version_log[-2]
        return None

    def register_hot_reload_callback(self, callback: Callable):
        self._hot_reload_callbacks.append(callback)

    def trigger_hot_reload(self, version_info: Dict):
        for cb in self._hot_reload_callbacks:
            try:
                cb(version_info)
            except Exception as e:
                print(f"[ModelManager] hot_reload callback error: {e}")

    def report_error(self, error_rate: float):
        self._error_metrics.append(error_rate)
        if len(self._error_metrics) > 10:
            self._error_metrics.pop(0)

        if self.auto_rollback and self._should_rollback():
            self._do_rollback()

    def _should_rollback(self) -> bool:
        if len(self._error_metrics) < 5:
            return False
        recent = self._error_metrics[-5:]
        return sum(recent) / len(recent) > self.error_threshold

    def _do_rollback(self):
        prev = self.get_previous_stable()
        if prev:
            print(f"[ModelManager] Auto-rollback to {prev['version']}")
            self.trigger_hot_reload(prev)

    def get_stats(self) -> Dict:
        return {
            'current_version': self._current_version,
            'total_versions': len(self._version_log),
            'latest': self.get_latest_version(),
            'error_metrics': self._error_metrics[-10:] if self._error_metrics else [],
        }
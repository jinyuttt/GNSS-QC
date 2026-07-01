"""定时增量学习调度器 — 双模型自动训练 + 版本质量判断"""

import time
import threading
import numpy as np
from datetime import datetime, timedelta
from typing import Dict, Optional, List

from models.rrcf import RRCFModel
from models.lstm_attention import LSTMAttentionModel
from models.features import FeatureNormalizer
from storage.base import StorageProvider
from training.model_manager import ModelManager


class IncrementalTrainer:
    """定时增量学习调度器

    训练策略:
    - RRCF: 全量增量更新，每次将新数据加入采样池，重建森林
    - LSTM: 冻结主干网络，仅对浅层参数做小步长微调
    - 版本归档前验证：新模型异常分数分布不退化才归档
    """

    def __init__(self, rrcf: RRCFModel, lstm: LSTMAttentionModel,
                 normalizer: FeatureNormalizer, storage: StorageProvider,
                 model_manager: ModelManager,
                 inference_engine=None,
                 interval_min: int = 5, max_fetch_num: int = 10000,
                 trigger_sample: int = 5000, trigger_round: int = 6,
                 daily_hour: int = 2, daily_fetch_hours: int = 24):
        self.rrcf = rrcf
        self.lstm = lstm
        self.normalizer = normalizer
        self.storage = storage
        self.model_manager = model_manager
        self.inference_engine = inference_engine

        self.interval_min = interval_min
        self.max_fetch_num = max_fetch_num
        self.trigger_sample = trigger_sample
        self.trigger_round = trigger_round
        self.daily_hour = daily_hour
        self.daily_fetch_hours = daily_fetch_hours

        self._running = False
        self._thread = None
        self._daily_thread = None
        self._lock = threading.Lock()
        self._total_samples = 0
        self._total_rounds = 0
        self._last_train_time = int(time.time() * 1000)
        self._last_daily_date = None

        self._baseline_conflict_rate = None
        self._lstm_train_counter = 0

    def start(self):
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        self._daily_thread = threading.Thread(target=self._daily_loop, daemon=True)
        self._daily_thread.start()
        print(f"[Trainer] Started, interval={self.interval_min}min, daily@{self.daily_hour}:00")

    def stop(self):
        self._running = False
        if self._thread:
            self._thread.join(timeout=10)
        if self._daily_thread:
            self._daily_thread.join(timeout=10)

    def _loop(self):
        while self._running:
            try:
                self._run_once()
            except Exception as e:
                print(f"[Trainer] loop error: {e}")
            time.sleep(self.interval_min * 60)

    def _daily_loop(self):
        while self._running:
            now = datetime.now()
            target = now.replace(hour=self.daily_hour, minute=0, second=0, microsecond=0)
            if now >= target:
                target += timedelta(days=1)
            wait_sec = (target - now).total_seconds()
            print(f"[Trainer] Daily training scheduled at {target}, "
                  f"waiting {wait_sec:.0f}s")
            while self._running and wait_sec > 0:
                sleep_chunk = min(wait_sec, 60)
                time.sleep(sleep_chunk)
                wait_sec -= sleep_chunk
                now = datetime.now()
                target = now.replace(hour=self.daily_hour, minute=0,
                                     second=0, microsecond=0)
                if now >= target:
                    break
            if not self._running:
                break
            today = datetime.now().strftime('%Y-%m-%d')
            if self._last_daily_date == today:
                continue
            try:
                print("[Trainer] === Daily training triggered ===")
                self._run_daily()
                self._last_daily_date = today
            except Exception as e:
                print(f"[Trainer] Daily training error: {e}")

    def _run_daily(self):
        if not self._lock.acquire(blocking=True, timeout=300):
            print("[Trainer] Daily training: cannot acquire lock, skip")
            return
        try:
            now = int(time.time() * 1000)
            start_time = now - self.daily_fetch_hours * 3600 * 1000
            print(f"[Trainer] Daily training fetching samples "
                  f"[{start_time}, {now}], window={self.daily_fetch_hours}h")

            samples = self.storage.get_training_samples(
                start_time, now, self.max_fetch_num
            )

            if not samples:
                print("[Trainer] Daily training: no samples, skip")
                return

            valid_samples = self._filter_samples(samples)
            print(f"[Trainer] Daily training: got {len(samples)} samples, "
                  f"{len(valid_samples)} valid")

            if len(valid_samples) < 10:
                return

            features_dict = self._extract_features(valid_samples)
            pre_scores = self._get_current_score_distribution(features_dict)

            self.rrcf.update(features_dict)
            self.normalizer.update(features_dict)
            self._train_lstm(valid_samples)

            self._total_samples += len(valid_samples)
            self._total_rounds += 1
            self._last_train_time = now

            quality_ok = self._validate_model_quality(features_dict, pre_scores)
            if quality_ok:
                self._do_archive()
                print("[Trainer] Daily training: archived new version")
            else:
                print("[Trainer] Daily training: quality degraded, skip archiving")
                self._total_samples = 0
                self._total_rounds = 0
        finally:
            self._lock.release()

    def _run_once(self):
        if not self._lock.acquire(blocking=False):
            print("[Trainer] Previous round still running, skip")
            return

        try:
            now = int(time.time() * 1000)
            print(f"[Trainer] Round {self._total_rounds + 1} "
                  f"fetching samples [{self._last_train_time}, {now}]")

            samples = self.storage.get_training_samples(
                self._last_train_time, now, self.max_fetch_num
            )

            if not samples:
                print("[Trainer] No new samples, skip")
                return

            valid_samples = self._filter_samples(samples)
            print(f"[Trainer] Got {len(samples)} samples, "
                  f"{len(valid_samples)} valid")

            if len(valid_samples) < 10:
                return

            features_dict = self._extract_features(valid_samples)

            pre_scores = self._get_current_score_distribution(features_dict)

            self.rrcf.update(features_dict)
            self.normalizer.update(features_dict)

            self._train_lstm(valid_samples)

            self._total_samples += len(valid_samples)
            self._total_rounds += 1
            self._last_train_time = now

            should_archive = (
                self._total_samples >= self.trigger_sample
                or self._total_rounds >= self.trigger_round
            )

            if should_archive:
                quality_ok = self._validate_model_quality(features_dict, pre_scores)
                if quality_ok:
                    self._do_archive()
                else:
                    print("[Trainer] Model quality degraded, skip archiving")
                    self._total_samples = 0
                    self._total_rounds = 0

        finally:
            self._lock.release()

    def _do_archive(self):
        """归档新版本并触发热加载"""
        version = self.model_manager.archive_version(
            self.rrcf, self.lstm, self.normalizer,
            train_samples=self._total_samples,
            train_rounds=self._total_rounds,
        )
        print(f"[Trainer] New version archived: {version}")
        self._total_samples = 0
        self._total_rounds = 0

        latest = self.model_manager.get_latest_version()
        if latest:
            self.model_manager.trigger_hot_reload(latest)

    def _get_current_score_distribution(self, features: List[Dict]) -> Dict:
        """获取当前模型对新样本的评分分布（用于版本对比）"""
        if len(features) == 0:
            return {}
        scores = self.rrcf.score_batch(features)
        return {
            'mean': float(np.mean(scores)),
            'std': float(np.std(scores)),
            'p90': float(np.percentile(scores, 90)),
            'p50': float(np.percentile(scores, 50)),
        }

    def _validate_model_quality(self, features: List[Dict],
                                 pre_scores: Dict) -> bool:
        """验证更新后模型质量是否退化

        判断标准:
        1. 异常分数分布不能剧烈变化（std变化 < 50%）
        2. 冲突率不能高于基线2倍
        """
        if not pre_scores:
            return True

        post_scores = self._get_current_score_distribution(features)
        if not post_scores:
            return True

        if pre_scores['std'] > 0.01:
            std_change = abs(post_scores['std'] - pre_scores['std']) / pre_scores['std']
            if std_change > 0.5:
                print(f"[Trainer] Score std changed {std_change:.2%}, too large")
                return False

        if self.inference_engine is not None:
            quality = self.inference_engine.get_quality_stats()
            conflict_rate = quality['conflict_rate']

            if self._baseline_conflict_rate is None:
                self._baseline_conflict_rate = conflict_rate
            elif conflict_rate > self._baseline_conflict_rate * 2.0:
                print(f"[Trainer] Conflict rate {conflict_rate:.2%} "
                      f"> 2x baseline {self._baseline_conflict_rate:.2%}")
                return False

        print(f"[Trainer] Quality check passed: "
              f"pre={pre_scores['mean']:.4f} post={post_scores['mean']:.4f}")
        return True

    def _filter_samples(self, samples: list) -> list:
        valid = []
        for s in samples:
            status = s.get('status', '')
            f6 = s.get('f6_temporal_residual', 0)
            if status == 'REJECTED':
                continue
            if abs(f6) > 5.0:
                continue
            valid.append(s)
        return valid

    def _extract_features(self, samples: list) -> List[Dict]:
        return [{
            'f1North': s.get('f1_north', 0),
            'f2East': s.get('f2_east', 0),
            'f3Up': s.get('f3_up', 0),
            'f4HorizontalRate': s.get('f4_horizontal_rate', 0),
            'f5VerticalRate': s.get('f5_vertical_rate', 0),
            'f6TemporalResidual': s.get('f6_temporal_residual', 0),
            'f7SpatialResidual': s.get('f7_spatial_residual', 0),
            'f8NeighborRatio': s.get('f8_neighbor_ratio', 0),
            'f9QualityScore': s.get('f9_quality_score', 0),
            'f10Stability': s.get('f10_stability', 0),
        } for s in samples]

    def _train_lstm(self, samples: list):
        """LSTM增量训练：冻结主干，仅微调浅层参数"""
        self._lstm_train_counter += 1
        if self._lstm_train_counter % 3 != 0:
            return

        if len(samples) < self.lstm.window_size:
            return

        try:
            X = self._build_lstm_windows(samples)
            y = self._auto_label(samples)

            loss = self.lstm.train_step(X, y, learning_rate=0.0001)
            print(f"[Trainer] LSTM fine-tuned, loss={loss:.4f}")
        except Exception as e:
            print(f"[Trainer] LSTM train error: {e}")

    def _build_lstm_windows(self, samples: list) -> np.ndarray:
        """从样本构建时序窗口"""
        ws = self.lstm.window_size
        features = self._extract_features(samples)
        arr = np.array([[f[k] for k in sorted(f.keys())] for f in features],
                       dtype=np.float32)

        windows = []
        for i in range(ws, len(arr)):
            windows.append(arr[i - ws:i])
        if not windows:
            return np.zeros((1, ws, 10), dtype=np.float32)
        return np.array(windows, dtype=np.float32)

    def _auto_label(self, samples: list) -> np.ndarray:
        """自动标注：基于RRCF分数 + 时序残差"""
        ws = self.lstm.window_size
        labels = []
        for i in range(ws, len(samples)):
            s = samples[i]
            f6 = abs(s.get('f6_temporal_residual', 0))
            if f6 > 3.0:
                labels.append(1)  # 伪形变
            elif f6 > 2.0:
                labels.append(2)  # 真实形变
            else:
                labels.append(0)  # 正常
        if not labels:
            return np.zeros(1, dtype=np.int64)
        return np.array(labels, dtype=np.int64)

    def get_stats(self) -> Dict:
        return {
            'running': self._running,
            'total_samples': self._total_samples,
            'total_rounds': self._total_rounds,
            'interval_min': self.interval_min,
            'daily_hour': self.daily_hour,
            'daily_fetch_hours': self.daily_fetch_hours,
            'last_daily_date': self._last_daily_date,
            'last_train_time': self._last_train_time,
            'lstm_train_counter': self._lstm_train_counter,
            'baseline_conflict_rate': self._baseline_conflict_rate,
        }
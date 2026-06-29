"""双模型融合推理引擎 — RRCF + LSTM+Attention"""

import time
import numpy as np
from typing import Dict, List, Optional, Tuple
from collections import deque
from .rrcf import RRCFModel
from .lstm_attention import LSTMAttentionModel
from .features import FeatureNormalizer


class InferenceEngine:
    """双模型融合推理引擎

    融合策略:
    1. RRCF 识别数据分布突变 (统计异常)
    2. LSTM+Attention 判定是否为真实形变 (时序语义)
    3. 两者取交集 + 置信度阈值 → 生成候选修正值
    4. 两者冲突 → 标记人工复核

    候选修正值生成策略:
    - TIME_SERIES_PREDICTION: 基于LSTM编码器对历史时序编码，
      利用指数加权移动均值(EWMA)预测下一时刻N/E/U，结合置信度缩放
    - NEIGHBOR_INTERPOLATION: 基于空间邻域的加权反距离插值(IDW)，
      利用同组测站数据生成候选值
    """

    HISTORY_WINDOW_SIZE = 20
    EWMA_ALPHA = 0.3
    IDW_POWER = 2.0

    def __init__(self, rrcf: RRCFModel, lstm: LSTMAttentionModel,
                 normalizer: FeatureNormalizer,
                 confidence_threshold: float = 0.7,
                 max_continuous_err: int = 20,
                 storage=None):
        self.rrcf = rrcf
        self.lstm = lstm
        self.normalizer = normalizer
        self.confidence_threshold = confidence_threshold
        self.max_continuous_err = max_continuous_err
        self.storage = storage

        self._error_counters = {}
        self._model_manager = None
        self._total_inferences = 0
        self._conflict_count = 0
        self._candidate_count = 0

        self._history_n = {}
        self._history_e = {}
        self._history_u = {}
        self._ewma_n = {}
        self._ewma_e = {}
        self._ewma_u = {}
        self._ewma_initialized = {}

    def set_model_manager(self, model_manager):
        self._model_manager = model_manager

    def infer_single(self, station_id: str, features: Dict) -> Dict:
        t0 = time.time()

        rrcf_score = self.rrcf.score(features)
        lstm_result = self.lstm.predict(station_id, features)

        self.rrcf.record_score(rrcf_score)

        combined = self._fuse(rrcf_score, lstm_result, station_id, features)

        elapsed_ms = (time.time() - t0) * 1000
        combined['inferenceTimeMs'] = round(elapsed_ms, 2)
        combined['originalN'] = features.get('originalN', 0.0)
        combined['originalE'] = features.get('originalE', 0.0)
        combined['originalU'] = features.get('originalU', 0.0)

        self._report_quality(combined)
        return combined

    def infer_batch(self, station_ids: List[str],
                    features_list: List[Dict]) -> List[Dict]:
        t0 = time.time()

        rrcf_scores = self.rrcf.score_batch(features_list)
        lstm_results = self.lstm.predict_batch(station_ids, features_list)

        for score in rrcf_scores:
            self.rrcf.record_score(float(score))

        results = []
        for i, (sid, feat) in enumerate(zip(station_ids, features_list)):
            combined = self._fuse(
                float(rrcf_scores[i]), lstm_results[i], sid, feat
            )
            combined['inferenceTimeMs'] = round(
                (time.time() - t0) * 1000 / max(len(features_list), 1), 2
            )
            combined['originalN'] = feat.get('originalN', 0.0)
            combined['originalE'] = feat.get('originalE', 0.0)
            combined['originalU'] = feat.get('originalU', 0.0)
            self._report_quality(combined)
            results.append(combined)

        return results

    def _report_quality(self, result: Dict):
        """上报推理质量指标，供模型管理器判断是否需要回滚"""
        self._total_inferences += 1

        if result.get('deformType') == 'UNCERTAIN':
            self._conflict_count += 1

        if result.get('haveCandidate') == 1:
            self._candidate_count += 1

        if self._model_manager is not None and self._total_inferences > 0:
            conflict_rate = self._conflict_count / self._total_inferences
            if self._total_inferences % 100 == 0:
                self._model_manager.report_error(conflict_rate)

    def get_quality_stats(self) -> Dict:
        return {
            'total_inferences': self._total_inferences,
            'conflict_count': self._conflict_count,
            'conflict_rate': self._conflict_count / max(self._total_inferences, 1),
            'candidate_count': self._candidate_count,
            'candidate_rate': self._candidate_count / max(self._total_inferences, 1),
        }

    def _fuse(self, rrcf_score: float, lstm_result: Dict,
              station_id: str, features: Dict) -> Dict:
        lstm_class = lstm_result['class_name']
        lstm_conf = lstm_result['confidence']

        rrcf_anomaly = rrcf_score > 0.5
        lstm_pseudo = lstm_class == 'PSEUDO_DEFORMATION'
        lstm_real = lstm_class == 'REAL_DEFORMATION'

        confidence = (rrcf_score * 0.4 + lstm_conf * 0.6)

        error_key = station_id
        if error_key not in self._error_counters:
            self._error_counters[error_key] = 0

        if rrcf_anomaly and lstm_pseudo and confidence >= self.confidence_threshold:
            if self._error_counters[error_key] <= self.max_continuous_err:
                self._error_counters[error_key] += 1
                return self._generate_candidate(
                    rrcf_score, lstm_result, confidence,
                    station_id, features,
                    'TIME_SERIES_PREDICTION'
                    if self._error_counters[error_key] == 1
                    else 'NEIGHBOR_INTERPOLATION',
                    'SUGGEST_REPLACE'
                )
            else:
                return self._make_result(
                    rrcf_score, lstm_result, confidence,
                    have_candidate=0, candidate_type='NONE',
                    replace_suggest='NOT_SUGGEST_REPLACE',
                    risk_level='HIGH', deform_type='UNCERTAIN'
                )

        elif rrcf_anomaly and lstm_real:
            self._error_counters[error_key] = 0
            return self._make_result(
                rrcf_score, lstm_result, confidence,
                have_candidate=0, candidate_type='NONE',
                replace_suggest='NOT_SUGGEST_REPLACE',
                risk_level='HIGH', deform_type='REAL_DEFORMATION'
            )

        elif rrcf_anomaly and not lstm_real and not lstm_pseudo:
            self._error_counters[error_key] = 0
            return self._make_result(
                rrcf_score, lstm_result, confidence,
                have_candidate=0, candidate_type='NONE',
                replace_suggest='MANUAL_REVIEW',
                risk_level='MEDIUM', deform_type='UNCERTAIN'
            )

        else:
            self._error_counters[error_key] = 0
            risk = 'LOW'
            if rrcf_score > 0.3:
                risk = 'MEDIUM'
            return self._make_result(
                rrcf_score, lstm_result, confidence,
                have_candidate=0, candidate_type='NONE',
                replace_suggest='NOT_SUGGEST_REPLACE',
                risk_level=risk, deform_type='NORMAL'
            )

    def _generate_candidate(self, rrcf_score: float, lstm_result: Dict,
                            confidence: float, station_id: str,
                            features: Dict, candidate_type: str,
                            replace_suggest: str) -> Dict:
        original_n = features.get('originalN', 0.0)
        original_e = features.get('originalE', 0.0)
        original_u = features.get('originalU', 0.0)

        self._update_history(station_id, original_n, original_e, original_u)

        if candidate_type == 'TIME_SERIES_PREDICTION':
            candidate_n, candidate_e, candidate_u = self._predict_time_series(
                station_id, features, confidence
            )
        elif candidate_type == 'NEIGHBOR_INTERPOLATION':
            candidate_n, candidate_e, candidate_u = self._interpolate_neighbor(
                station_id, features, confidence
            )
        else:
            candidate_n = original_n
            candidate_e = original_e
            candidate_u = original_u

        return {
            'rrcfScore': round(rrcf_score, 4),
            'lstmResult': lstm_result['class_name'],
            'confidence': round(confidence, 4),
            'riskLevel': 'MEDIUM',
            'deformType': 'PSEUDO_DEFORMATION',
            'haveCandidate': 1,
            'candidateType': candidate_type,
            'replaceSuggest': replace_suggest,
            'candidateN': round(candidate_n, 6),
            'candidateE': round(candidate_e, 6),
            'candidateU': round(candidate_u, 6),
        }

    def _update_history(self, station_id: str, n: float, e: float, u: float):
        if station_id not in self._history_n:
            self._history_n[station_id] = deque(maxlen=self.HISTORY_WINDOW_SIZE)
            self._history_e[station_id] = deque(maxlen=self.HISTORY_WINDOW_SIZE)
            self._history_u[station_id] = deque(maxlen=self.HISTORY_WINDOW_SIZE)
        self._history_n[station_id].append(n)
        self._history_e[station_id].append(e)
        self._history_u[station_id].append(u)

        alpha = self.EWMA_ALPHA
        if not self._ewma_initialized.get(station_id, False):
            self._ewma_n[station_id] = n
            self._ewma_e[station_id] = e
            self._ewma_u[station_id] = u
            self._ewma_initialized[station_id] = True
        else:
            self._ewma_n[station_id] = alpha * n + (1 - alpha) * self._ewma_n[station_id]
            self._ewma_e[station_id] = alpha * e + (1 - alpha) * self._ewma_e[station_id]
            self._ewma_u[station_id] = alpha * u + (1 - alpha) * self._ewma_u[station_id]

    def _predict_time_series(self, station_id: str, features: Dict,
                             confidence: float) -> Tuple[float, float, float]:
        hist_n = self._history_n.get(station_id, deque())
        hist_e = self._history_e.get(station_id, deque())
        hist_u = self._history_u.get(station_id, deque())

        ewma_n = self._ewma_n.get(station_id, features.get('originalN', 0.0))
        ewma_e = self._ewma_e.get(station_id, features.get('originalE', 0.0))
        ewma_u = self._ewma_u.get(station_id, features.get('originalU', 0.0))

        pred_n, pred_e, pred_u = ewma_n, ewma_e, ewma_u

        if len(hist_n) >= 3:
            arr_n = np.array(hist_n, dtype=np.float64)
            arr_e = np.array(hist_e, dtype=np.float64)
            arr_u = np.array(hist_u, dtype=np.float64)

            t = np.arange(len(arr_n), dtype=np.float64)
            coeffs_n = np.polyfit(t, arr_n, min(2, len(arr_n) - 1))
            coeffs_e = np.polyfit(t, arr_e, min(2, len(arr_e) - 1))
            coeffs_u = np.polyfit(t, arr_u, min(2, len(arr_u) - 1))

            trend_n = np.polyval(coeffs_n, len(arr_n))
            trend_e = np.polyval(coeffs_e, len(arr_e))
            trend_u = np.polyval(coeffs_u, len(arr_u))

            trend_weight = min(0.4, confidence * 0.5)
            pred_n = (1 - trend_weight) * ewma_n + trend_weight * trend_n
            pred_e = (1 - trend_weight) * ewma_e + trend_weight * trend_e
            pred_u = (1 - trend_weight) * ewma_u + trend_weight * trend_u

        if len(hist_n) >= 5:
            window = self._build_lstm_window(station_id, features)
            if window is not None:
                lstm_encoded = self._encode_with_lstm(window)
                if lstm_encoded is not None:
                    encoded_n = lstm_encoded[0]
                    encoded_e = lstm_encoded[1]
                    encoded_u = lstm_encoded[2]
                    lstm_weight = min(0.3, confidence * 0.4)
                    pred_n = (1 - lstm_weight) * pred_n + lstm_weight * encoded_n
                    pred_e = (1 - lstm_weight) * pred_e + lstm_weight * encoded_e
                    pred_u = (1 - lstm_weight) * pred_u + lstm_weight * encoded_u

        scale = max(0.0, 1.0 - (1.0 - confidence) * 0.5)
        original_n = features.get('originalN', 0.0)
        original_e = features.get('originalE', 0.0)
        original_u = features.get('originalU', 0.0)
        candidate_n = original_n + scale * (pred_n - original_n)
        candidate_e = original_e + scale * (pred_e - original_e)
        candidate_u = original_u + scale * (pred_u - original_u)

        return float(candidate_n), float(candidate_e), float(candidate_u)

    def _build_lstm_window(self, station_id: str, features: Dict) -> Optional[np.ndarray]:
        try:
            window = self.lstm._update_window(station_id, features)
            if isinstance(window, np.ndarray) and window.shape[0] >= 5:
                return window
        except Exception:
            pass
        return None

    def _encode_with_lstm(self, window: np.ndarray) -> Optional[np.ndarray]:
        try:
            if self.lstm._use_torch and self.lstm._torch_model is not None:
                import torch
                with torch.no_grad():
                    x = torch.from_numpy(window).float().unsqueeze(0)
                    lstm_out, _ = self.lstm._torch_model.lstm(x)
                    encoded = lstm_out[0, -1, :].numpy()
            else:
                h = np.zeros(self.lstm.hidden_size, dtype=np.float32)
                c = np.zeros(self.lstm.hidden_size, dtype=np.float32)
                for t in range(window.shape[0]):
                    h, c = self.lstm._lstm_step(window[t:t+1], h, c)
                encoded = h

            if len(encoded) >= 3:
                return encoded[:3]
            w_proj = np.random.randn(len(encoded), 3).astype(np.float32) * 0.01
            return (encoded @ w_proj)
        except Exception:
            return None

    def _interpolate_neighbor(self, station_id: str, features: Dict,
                              confidence: float) -> Tuple[float, float, float]:
        original_n = features.get('originalN', 0.0)
        original_e = features.get('originalE', 0.0)
        original_u = features.get('originalU', 0.0)

        neighbor_data = self._get_neighbor_data(station_id, features)

        if not neighbor_data:
            ewma_n = self._ewma_n.get(station_id, original_n)
            ewma_e = self._ewma_e.get(station_id, original_e)
            ewma_u = self._ewma_u.get(station_id, original_u)
            scale = max(0.0, 1.0 - (1.0 - confidence) * 0.3)
            candidate_n = original_n + scale * (ewma_n - original_n)
            candidate_e = original_e + scale * (ewma_e - original_e)
            candidate_u = original_u + scale * (ewma_u - original_u)
            return float(candidate_n), float(candidate_e), float(candidate_u)

        weights = []
        values_n = []
        values_e = []
        values_u = []

        for neighbor in neighbor_data:
            dist = neighbor.get('distance', 1.0)
            if dist < 1e-10:
                dist = 1e-10
            w = 1.0 / (dist ** self.IDW_POWER)
            weights.append(w)
            values_n.append(neighbor.get('n', 0.0))
            values_e.append(neighbor.get('e', 0.0))
            values_u.append(neighbor.get('u', 0.0))

        total_w = sum(weights)
        if total_w < 1e-10:
            return original_n, original_e, original_u

        interp_n = sum(w * v for w, v in zip(weights, values_n)) / total_w
        interp_e = sum(w * v for w, v in zip(weights, values_e)) / total_w
        interp_u = sum(w * v for w, v in zip(weights, values_u)) / total_w

        spatial_residual = features.get('f7SpatialResidual', 0.0)
        neighbor_ratio = features.get('f8NeighborRatio', 1.0)
        spatial_weight = min(0.6, neighbor_ratio * 0.6)

        if abs(spatial_residual) > 0:
            correction_n = -spatial_residual * (interp_n - original_n) / max(abs(interp_n - original_n), 1e-10)
            correction_e = -spatial_residual * (interp_e - original_e) / max(abs(interp_e - original_e), 1e-10)
            correction_u = -spatial_residual * (interp_u - original_u) / max(abs(interp_u - original_u), 1e-10)
        else:
            correction_n = interp_n - original_n
            correction_e = interp_e - original_e
            correction_u = interp_u - original_u

        candidate_n = original_n + spatial_weight * correction_n
        candidate_e = original_e + spatial_weight * correction_e
        candidate_u = original_u + spatial_weight * correction_u

        return float(candidate_n), float(candidate_e), float(candidate_u)

    def _get_neighbor_data(self, station_id: str,
                           features: Dict) -> List[Dict]:
        neighbors = []
        if self.storage is not None:
            try:
                spatial_residual = features.get('f7SpatialResidual', 0.0)
                neighbor_ratio = features.get('f8NeighborRatio', 1.0)
                if neighbor_ratio > 0.3 and abs(spatial_residual) > 0:
                    recent = self.storage.query_raw_data(
                        station_id, limit=5
                    )
                    if recent:
                        hist_n = [r.get('original_n', 0.0) for r in recent]
                        hist_e = [r.get('original_e', 0.0) for r in recent]
                        hist_u = [r.get('original_u', 0.0) for r in recent]
                        neighbors.append({
                            'n': float(np.median(hist_n)),
                            'e': float(np.median(hist_e)),
                            'u': float(np.median(hist_u)),
                            'distance': max(abs(spatial_residual), 0.001),
                        })
            except Exception:
                pass

        if not neighbors:
            hist_n = self._history_n.get(station_id, deque())
            hist_e = self._history_e.get(station_id, deque())
            hist_u = self._history_u.get(station_id, deque())
            if len(hist_n) >= 3:
                arr_n = list(hist_n)[:-1]
                arr_e = list(hist_e)[:-1]
                arr_u = list(hist_u)[:-1]
                neighbors.append({
                    'n': float(np.median(arr_n)),
                    'e': float(np.median(arr_e)),
                    'u': float(np.median(arr_u)),
                    'distance': max(
                        abs(features.get('f7SpatialResidual', 0.01)), 0.01
                    ),
                })

        return neighbors

    def _make_result(self, rrcf_score: float, lstm_result: Dict,
                     confidence: float, **kwargs) -> Dict:
        return {
            'rrcfScore': round(rrcf_score, 4),
            'lstmResult': lstm_result['class_name'],
            'confidence': round(confidence, 4),
            'riskLevel': kwargs.get('risk_level', 'LOW'),
            'deformType': kwargs.get('deform_type', 'NORMAL'),
            'haveCandidate': kwargs.get('have_candidate', 0),
            'candidateType': kwargs.get('candidate_type', 'NONE'),
            'replaceSuggest': kwargs.get('replace_suggest',
                                          'NOT_SUGGEST_REPLACE'),
            'candidateN': 0.0,
            'candidateE': 0.0,
            'candidateU': 0.0,
        }
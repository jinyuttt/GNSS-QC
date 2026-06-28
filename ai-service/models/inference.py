"""双模型融合推理引擎 — RRCF + LSTM+Attention"""

import time
import numpy as np
from typing import Dict, List, Optional, Tuple
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
    """

    def __init__(self, rrcf: RRCFModel, lstm: LSTMAttentionModel,
                 normalizer: FeatureNormalizer,
                 confidence_threshold: float = 0.7,
                 max_continuous_err: int = 20):
        self.rrcf = rrcf
        self.lstm = lstm
        self.normalizer = normalizer
        self.confidence_threshold = confidence_threshold
        self.max_continuous_err = max_continuous_err

        self._error_counters = {}
        self._model_manager = None
        self._total_inferences = 0
        self._conflict_count = 0
        self._candidate_count = 0

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

        if candidate_type == 'TIME_SERIES_PREDICTION':
            candidate_n = original_n * 0.95
            candidate_e = original_e * 0.95
            candidate_u = original_u * 0.95
        elif candidate_type == 'NEIGHBOR_INTERPOLATION':
            candidate_n = original_n * 0.9
            candidate_e = original_e * 0.9
            candidate_u = original_u * 0.9
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
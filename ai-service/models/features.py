import numpy as np
import os
import json
from typing import Dict, List, Optional


class FeatureNormalizer:

    def __init__(self, norm_file: Optional[str] = None):
        self.norm_file = norm_file
        self._count = 0
        self._mean = np.zeros(10, dtype=np.float64)
        self._std = np.ones(10, dtype=np.float64)
        self._m2 = np.zeros(10, dtype=np.float64)
        if norm_file and os.path.exists(norm_file):
            self.load(norm_file)

    def _extract_features(self, features: Dict) -> np.ndarray:
        return np.array([
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
        ], dtype=np.float64)

    def normalize(self, features: Dict) -> np.ndarray:
        x = self._extract_features(features)
        return (x - self._mean) / np.maximum(self._std, 1e-8)

    def normalize_batch(self, features_list: List[Dict]) -> np.ndarray:
        X = np.array([self._extract_features(f) for f in features_list])
        return (X - self._mean) / np.maximum(self._std, 1e-8)

    def update(self, features_list: List[Dict]):
        X = np.array([self._extract_features(f) for f in features_list])
        for x in X:
            self._count += 1
            delta = x - self._mean
            self._mean += delta / self._count
            delta2 = x - self._mean
            self._m2 += delta * delta2
        if self._count > 1:
            self._std = np.sqrt(self._m2 / (self._count - 1))
            self._std = np.maximum(self._std, 1e-8)

    def save(self, filepath: str):
        os.makedirs(os.path.dirname(filepath), exist_ok=True)
        params = {
            'count': self._count,
            'mean': self._mean.tolist(),
            'std': self._std.tolist(),
        }
        with open(filepath, 'w') as f:
            json.dump(params, f)

    def load(self, filepath: str):
        with open(filepath, 'r') as f:
            params = json.load(f)
        self._count = params['count']
        self._mean = np.array(params['mean'], dtype=np.float64)
        self._std = np.array(params['std'], dtype=np.float64)

    def get_stats(self) -> Dict:
        return {
            'count': self._count,
            'mean': self._mean.tolist(),
            'std': self._std.tolist(),
        }
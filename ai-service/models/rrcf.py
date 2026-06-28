"""RRCF 鲁棒随机割森林 — 基于统计分布的异常检测"""

import numpy as np
import os
import pickle
from typing import Dict, List, Optional


class RRCFModel:
    """基于随机割森林的异常检测模型

    核心思路：用多棵随机树对特征空间做分区，每个点在各树中的
    孤立深度（depth）越小，说明越容易被隔离 → 异常分数越高。
    """

    def __init__(self, num_trees: int = 100, tree_size: int = 256,
                 shingle_size: int = 4, random_seed: int = 42):
        self.num_trees = num_trees
        self.tree_size = tree_size
        self.shingle_size = shingle_size
        self.rng = np.random.RandomState(random_seed)

        self._trees = []
        self._point_count = 0
        self._sample_pool = None
        self._pool_size = 0
        self._max_pool = 5000
        self._score_threshold = 0.0
        self._score_history = []

    def _build_tree(self, data: np.ndarray) -> Dict:
        """递归构建一棵随机割树"""
        n, d = data.shape
        if n <= 1:
            return {'type': 'leaf', 'size': n}

        dim = self.rng.randint(d)
        col = data[:, dim]
        min_v, max_v = col.min(), col.max()
        if max_v - min_v < 1e-10:
            return {'type': 'leaf', 'size': n}

        cut = self.rng.uniform(min_v, max_v)
        left_mask = col <= cut
        right_mask = ~left_mask

        if left_mask.sum() == 0 or right_mask.sum() == 0:
            return {'type': 'leaf', 'size': n}

        return {
            'type': 'node',
            'dim': dim,
            'cut': cut,
            'size': n,
            'left': self._build_tree(data[left_mask]),
            'right': self._build_tree(data[right_mask]),
        }

    def _path_length(self, point: np.ndarray, tree: Dict, depth: int = 0) -> float:
        """计算点在某棵树中的路径长度"""
        if tree['type'] == 'leaf':
            if tree['size'] <= 1:
                return depth
            return depth + self._avg_path_length(tree['size'])

        if point[tree['dim']] <= tree['cut']:
            return self._path_length(point, tree['left'], depth + 1)
        else:
            return self._path_length(point, tree['right'], depth + 1)

    def _avg_path_length(self, n: int) -> float:
        """n个点的平均路径长度（调和数近似）"""
        if n <= 1:
            return 0.0
        return 2.0 * (np.log(n - 1) + 0.5772156649) - 2.0 * (n - 1) / n

    def _rebuild_forest(self):
        """用采样池重建所有树"""
        if self._sample_pool is None or self._pool_size < 10:
            return

        sample_size = min(self.tree_size, self._pool_size)
        self._trees = []
        for _ in range(self.num_trees):
            idx = self.rng.choice(self._pool_size, sample_size, replace=True)
            sample = self._sample_pool[idx]
            self._trees.append(self._build_tree(sample))

    def _feature_to_array(self, features: Dict) -> np.ndarray:
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

    def score(self, features: Dict) -> float:
        """计算单个点的异常分数（0~1，越大越异常）"""
        if len(self._trees) == 0:
            return 0.0

        point = self._feature_to_array(features)
        total_depth = 0.0
        for tree in self._trees:
            total_depth += self._path_length(point, tree)

        avg_depth = total_depth / len(self._trees)
        avg_len = self._avg_path_length(self.tree_size)

        score = 2.0 ** (-avg_depth / avg_len)

        if self._score_threshold > 0:
            score = min(score / self._score_threshold, 1.0)

        return float(score)

    def score_batch(self, features_list: List[Dict]) -> np.ndarray:
        return np.array([self.score(f) for f in features_list])

    def update(self, features_list: List[Dict]):
        """增量更新：将新数据加入采样池，触发重建"""
        new_points = np.array([self._feature_to_array(f) for f in features_list])

        if self._sample_pool is None:
            self._sample_pool = new_points.copy()
            self._pool_size = len(new_points)
        else:
            self._sample_pool = np.vstack([self._sample_pool, new_points])
            self._pool_size = len(self._sample_pool)

        if self._pool_size > self._max_pool:
            keep = self._max_pool
            idx = self.rng.choice(self._pool_size, keep, replace=False)
            self._sample_pool = self._sample_pool[idx]
            self._pool_size = keep

        self._point_count += len(features_list)
        self._rebuild_forest()

        if self._point_count % 500 == 0:
            self._update_threshold()

    def _update_threshold(self):
        """根据历史分数分布动态调整阈值"""
        if len(self._score_history) < 50:
            self._score_threshold = 0.5
            return
        scores = np.array(self._score_history[-200:])
        self._score_threshold = float(np.percentile(scores, 90))

    def record_score(self, score: float):
        """记录推理分数，用于动态阈值校准"""
        self._score_history.append(score)
        if len(self._score_history) > 2000:
            self._score_history = self._score_history[-2000:]

    def save(self, dirpath: str):
        os.makedirs(dirpath, exist_ok=True)
        state = {
            'num_trees': self.num_trees,
            'tree_size': self.tree_size,
            'shingle_size': self.shingle_size,
            'point_count': self._point_count,
            'score_threshold': self._score_threshold,
            'pool_size': self._pool_size,
            'sample_pool': self._sample_pool.tolist() if self._sample_pool is not None else None,
            'score_history': self._score_history[-500:] if self._score_history else [],
        }
        with open(os.path.join(dirpath, 'rrcf_state.pkl'), 'wb') as f:
            pickle.dump(state, f)

    def load(self, dirpath: str):
        state_path = os.path.join(dirpath, 'rrcf_state.pkl')
        if not os.path.exists(state_path):
            return False
        with open(state_path, 'rb') as f:
            state = pickle.load(f)
        self.num_trees = state['num_trees']
        self.tree_size = state['tree_size']
        self.shingle_size = state['shingle_size']
        self._point_count = state['point_count']
        self._score_threshold = state['score_threshold']
        self._pool_size = state.get('pool_size', 0)
        pool = state.get('sample_pool')
        self._sample_pool = np.array(pool) if pool else None
        self._score_history = state.get('score_history', [])
        self._rebuild_forest()
        return True
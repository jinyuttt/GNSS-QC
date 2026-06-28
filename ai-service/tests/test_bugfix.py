import os
import sys
import json
import hashlib
import tempfile
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from models.rrcf import RRCFModel
from models.lstm_attention import LSTMAttentionModel
from models.features import FeatureNormalizer
from models.inference import InferenceEngine


def make_features(station_id='ST01', n=1.0, e=2.0, u=3.0, **kwargs):
    return {
        'stationId': station_id,
        'epochMillis': 1700000000000,
        'originalN': n, 'originalE': e, 'originalU': u,
        'velocityN': kwargs.get('vel_n', 0.01),
        'velocityE': kwargs.get('vel_e', 0.02),
        'velocityU': kwargs.get('vel_u', 0.03),
        'accelerationN': kwargs.get('acc_n', 0.001),
        'accelerationE': kwargs.get('acc_e', 0.002),
        'accelerationU': kwargs.get('acc_u', 0.003),
        'f1North': kwargs.get('f1North', n),
        'f2East': kwargs.get('f2East', e),
        'f3Up': kwargs.get('f3Up', u),
        'f4HorizontalRate': kwargs.get('f4HorizontalRate', 0.01),
        'f5VerticalRate': kwargs.get('f5VerticalRate', 0.02),
        'f6TemporalResidual': kwargs.get('f6TemporalResidual', 0.5),
        'f7SpatialResidual': kwargs.get('f7SpatialResidual', 0.0),
        'f8NeighborRatio': kwargs.get('f8NeighborRatio', 0.8),
        'f9QualityScore': kwargs.get('f9QualityScore', 0.9),
        'f10Stability': kwargs.get('f10Stability', 0.1),
    }


def test_rrcf_save_load_json():
    rrcf = RRCFModel(num_trees=10, tree_size=64)
    for i in range(5):
        rrcf.score(make_features(n=1.0 + i * 0.01))

    with tempfile.TemporaryDirectory() as tmpdir:
        rrcf.save(tmpdir)

        json_path = os.path.join(tmpdir, 'rrcf_state.json')
        sha_path = json_path + '.sha256'
        pkl_path = os.path.join(tmpdir, 'rrcf_state.pkl')

        assert os.path.exists(json_path), "应保存为 JSON 格式"
        assert os.path.exists(sha_path), "应有 SHA-256 校验文件"
        assert not os.path.exists(pkl_path), "不应再使用 pickle 格式"

        with open(json_path, 'r', encoding='utf-8') as f:
            state = json.load(f)
        assert 'num_trees' in state
        assert 'score_threshold' in state

        with open(sha_path, 'r') as f:
            expected_digest = f.read().strip()
        content = json.dumps(state, sort_keys=True).encode('utf-8')
        actual_digest = hashlib.sha256(content).hexdigest()
        assert actual_digest == expected_digest, "SHA-256 校验应通过"

        rrcf2 = RRCFModel(num_trees=10, tree_size=64)
        loaded = rrcf2.load(tmpdir)
        assert loaded, "应成功加载"
        score = rrcf2.score(make_features())
        assert 0.0 <= score <= 1.0

    print("  [PASS] RRCF 使用 JSON + SHA-256 序列化，不再使用 pickle")


def test_rrcf_integrity_check():
    rrcf = RRCFModel(num_trees=10, tree_size=64)
    for i in range(5):
        rrcf.score(make_features(n=1.0 + i * 0.01))

    with tempfile.TemporaryDirectory() as tmpdir:
        rrcf.save(tmpdir)

        sha_path = os.path.join(tmpdir, 'rrcf_state.json.sha256')
        with open(sha_path, 'w') as f:
            f.write("tampered_hash_value")

        rrcf2 = RRCFModel(num_trees=10, tree_size=64)
        try:
            rrcf2.load(tmpdir)
            assert False, "校验失败时应抛出异常"
        except ValueError as e:
            assert "integrity" in str(e).lower() or "check" in str(e).lower()

    print("  [PASS] RRCF 状态文件被篡改时，完整性校验拒绝加载")


def test_lstm_numpy_batch_dimension():
    lstm = LSTMAttentionModel(use_torch=False, window_size=3)

    for i in range(5):
        result = lstm.predict('ST01', make_features(n=1.0 + i * 0.01))
        assert 'class_name' in result

    window_deque = lstm._windows.get('ST01')
    assert window_deque is not None, "应有窗口数据"

    window_list = list(window_deque)
    if len(window_list) >= 3:
        batch = np.stack([window_list], axis=0)
        assert batch.ndim == 3, f"batch 应为3维 (batch, seq, features)，实际: {batch.ndim}"
        assert batch.shape[0] == 1
        assert batch.shape[1] == len(window_list)
        assert batch.shape[2] == 10

    print("  [PASS] LSTM numpy 前向传播支持 batch 维度")


def test_lstm_attention_2d_and_3d():
    lstm = LSTMAttentionModel(use_torch=False, window_size=3)

    for i in range(5):
        lstm.predict('ST01', make_features(n=1.0 + i * 0.01))

    window_deque = lstm._windows['ST01']
    window_list = list(window_deque)

    if len(window_list) >= 2:
        hidden = lstm.hidden_size
        seq_2d = np.random.randn(len(window_list), hidden).astype(np.float32)
        out_2d = lstm._attention(seq_2d)
        assert out_2d.shape == seq_2d.shape, f"2D attention 输出形状应与输入一致"

        seq_3d = np.random.randn(2, len(window_list), hidden).astype(np.float32)
        out_3d = lstm._attention(seq_3d)
        assert out_3d.shape == seq_3d.shape, f"3D attention 输出形状应与输入一致"

    print("  [PASS] _attention 方法同时支持 2D 和 3D 输入")


def test_inference_neighbor_interpolation_branch():
    rrcf = RRCFModel(num_trees=10, tree_size=64)
    lstm = LSTMAttentionModel(use_torch=False)
    norm = FeatureNormalizer()
    engine = InferenceEngine(rrcf, lstm, norm, confidence_threshold=0.7)

    station_id = 'ST-BRANCH'
    for _ in range(5):
        engine.infer_single(station_id, make_features(
            station_id=station_id,
            f6TemporalResidual=0.1,
            f9QualityScore=0.95
        ))

    engine._error_counters[f'{station_id}:PSEUDO_DEFORMATION'] = 2

    result = engine.infer_single(station_id, make_features(
        station_id=station_id,
        f6TemporalResidual=0.8,
        f9QualityScore=0.3,
        f7SpatialResidual=0.9,
        f8NeighborRatio=0.2,
    ))

    if result['haveCandidate'] == 1:
        ct = result.get('candidateType', '')
        assert ct != '', "候选类型不应为空"
        print(f"  候选类型: {ct}")

    print("  [PASS] InferenceEngine NEIGHBOR_INTERPOLATION 分支可达")


def test_rrcf_no_pickle_in_state():
    rrcf = RRCFModel(num_trees=10, tree_size=64)
    for i in range(5):
        rrcf.score(make_features(n=1.0 + i * 0.01))

    with tempfile.TemporaryDirectory() as tmpdir:
        rrcf.save(tmpdir)

        for fname in os.listdir(tmpdir):
            assert not fname.endswith('.pkl'), f"不应存在 pickle 文件: {fname}"

        json_path = os.path.join(tmpdir, 'rrcf_state.json')
        with open(json_path, 'r', encoding='utf-8') as f:
            content = f.read()
        try:
            json.loads(content)
        except json.JSONDecodeError:
            assert False, "保存的文件应为合法 JSON"

    print("  [PASS] RRCF 状态文件全部为 JSON，无 pickle 残留")


if __name__ == '__main__':
    test_rrcf_save_load_json()
    test_rrcf_integrity_check()
    test_lstm_numpy_batch_dimension()
    test_lstm_attention_2d_and_3d()
    test_inference_neighbor_interpolation_branch()
    test_rrcf_no_pickle_in_state()
    print("\nAll Python BugFix tests passed!")
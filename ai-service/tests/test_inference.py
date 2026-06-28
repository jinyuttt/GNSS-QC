import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from models.rrcf import RRCFModel
from models.lstm_attention import LSTMAttentionModel
from models.features import FeatureNormalizer
from models.inference import InferenceEngine


def make_features(station_id='ST01', n=1.0, e=2.0, u=3.0,
                  vel_n=0.01, vel_e=0.02, vel_u=0.03,
                  acc_n=0.001, acc_e=0.002, acc_u=0.003,
                  **kwargs):
    return {
        'stationId': station_id,
        'epochMillis': 1700000000000,
        'originalN': n, 'originalE': e, 'originalU': u,
        'velocityN': vel_n, 'velocityE': vel_e, 'velocityU': vel_u,
        'accelerationN': acc_n, 'accelerationE': acc_e, 'accelerationU': acc_u,
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


def test_rrcf_create():
    rrcf = RRCFModel(num_trees=10, tree_size=64)
    score = rrcf.score(make_features())
    assert 0.0 <= score <= 1.0


def test_rrcf_batch():
    rrcf = RRCFModel(num_trees=10, tree_size=64)
    features = [make_features() for _ in range(5)]
    scores = rrcf.score_batch(features)
    assert len(scores) == 5
    assert all(0.0 <= s <= 1.0 for s in scores)


def test_lstm_create():
    lstm = LSTMAttentionModel(use_torch=False)
    result = lstm.predict('ST01', make_features())
    assert 'class_name' in result
    assert result['class_name'] in ['NORMAL', 'PSEUDO_DEFORMATION', 'REAL_DEFORMATION']
    assert 0.0 <= result['confidence'] <= 1.0


def test_lstm_window():
    lstm = LSTMAttentionModel(use_torch=False, window_size=5)
    for i in range(10):
        result = lstm.predict('ST01', make_features(n=1.0 + i * 0.01))
        assert 'class_name' in result
    lstm.clear_window('ST01')


def test_normalizer():
    norm = FeatureNormalizer()
    features = [make_features() for _ in range(20)]
    norm.update(features)
    stats = norm.get_stats()
    assert stats['count'] == 20
    normalized = norm.normalize(make_features())
    assert normalized.shape == (10,)


def test_inference_normal():
    rrcf = RRCFModel(num_trees=10, tree_size=64)
    lstm = LSTMAttentionModel(use_torch=False)
    norm = FeatureNormalizer()
    engine = InferenceEngine(rrcf, lstm, norm, confidence_threshold=0.7)

    result = engine.infer_single('ST01', make_features(
        n=1.0, f6TemporalResidual=0.1, f9QualityScore=0.95
    ))
    assert result['riskLevel'] == 'LOW'
    assert result['haveCandidate'] == 0
    assert result['deformType'] == 'NORMAL'
    assert 'inferenceTimeMs' in result


def test_inference_batch():
    rrcf = RRCFModel(num_trees=10, tree_size=64)
    lstm = LSTMAttentionModel(use_torch=False)
    norm = FeatureNormalizer()
    engine = InferenceEngine(rrcf, lstm, norm)

    features = [make_features(f'ST{i:02d}') for i in range(10)]
    results = engine.infer_batch(
        [f'ST{i:02d}' for i in range(10)], features
    )
    assert len(results) == 10
    for r in results:
        assert 'riskLevel' in r
        assert 'haveCandidate' in r
        assert 'rrcfScore' in r
        assert 'lstmResult' in r
        assert 'confidence' in r


def test_inference_result_fields():
    rrcf = RRCFModel(num_trees=10, tree_size=64)
    lstm = LSTMAttentionModel(use_torch=False)
    norm = FeatureNormalizer()
    engine = InferenceEngine(rrcf, lstm, norm)

    result = engine.infer_single('ST01', make_features())
    required_fields = [
        'rrcfScore', 'lstmResult', 'confidence',
        'riskLevel', 'deformType', 'haveCandidate',
        'candidateType', 'replaceSuggest',
        'candidateN', 'candidateE', 'candidateU',
        'originalN', 'originalE', 'originalU',
        'inferenceTimeMs',
    ]
    for field in required_fields:
        assert field in result, f"Missing field: {field}"


if __name__ == '__main__':
    test_rrcf_create()
    test_rrcf_batch()
    test_lstm_create()
    test_lstm_window()
    test_normalizer()
    test_inference_normal()
    test_inference_batch()
    test_inference_result_fields()
    print("All inference tests passed!")
import os
import sys
import time
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from storage.sqlite_store import SQLiteStore


def _temp_db():
    fd, path = tempfile.mkstemp(suffix='.db')
    os.close(fd)
    os.unlink(path)
    return path


def test_sqlite_create():
    db_path = _temp_db()
    try:
        store = SQLiteStore(db_path=db_path)
        assert store.is_available()
        stats = store.get_stats()
        assert stats['type'] == 'sqlite'
        assert stats['raw_count'] == 0
        assert stats['eval_count'] == 0
    finally:
        try:
            os.unlink(db_path)
        except Exception:
            pass


def test_save_and_query_raw():
    db_path = _temp_db()
    try:
        store = SQLiteStore(db_path=db_path)
        features = {
            'originalN': 1.0, 'originalE': 2.0, 'originalU': 3.0,
            'velocityN': 0.1, 'velocityE': 0.2, 'velocityU': 0.3,
            'accelerationN': 0.01, 'accelerationE': 0.02, 'accelerationU': 0.03,
            'f1North': 1.0, 'f2East': 2.0, 'f3Up': 3.0,
            'f4HorizontalRate': 0.1, 'f5VerticalRate': 0.2,
            'f6TemporalResidual': 0.5, 'f7SpatialResidual': 0.0,
            'f8NeighborRatio': 0.8, 'f9QualityScore': 0.9, 'f10Stability': 0.1,
        }
        epoch = int(time.time() * 1000)
        assert store.save_raw_data('ST01', epoch, features)

        results = store.query_raw_data('ST01', limit=10)
        assert len(results) == 1
        assert results[0]['station_id'] == 'ST01'
        assert abs(results[0]['original_n'] - 1.0) < 0.001
    finally:
        try:
            os.unlink(db_path)
        except Exception:
            pass


def test_save_and_query_eval():
    db_path = _temp_db()
    try:
        store = SQLiteStore(db_path=db_path)
        result = {
            'originalN': 1.0, 'originalE': 2.0, 'originalU': 3.0,
            'candidateN': 0.95, 'candidateE': 1.9, 'candidateU': 2.85,
            'haveCandidate': 1, 'candidateType': 'TIME_SERIES_PREDICTION',
            'replaceSuggest': 'SUGGEST_REPLACE',
            'rrcfScore': 0.85, 'lstmResult': 'PSEUDO_DEFORMATION',
            'confidence': 0.82, 'riskLevel': 'MEDIUM',
            'deformType': 'PSEUDO_DEFORMATION',
            'inferenceTimeMs': 35.5, 'modelVersion': 'v1',
        }
        epoch = int(time.time() * 1000)
        assert store.save_eval_result('ST01', epoch, result)

        results = store.query_results('ST01', limit=10)
        assert len(results) == 1
        assert results[0]['have_candidate'] == 1
        assert results[0]['candidate_type'] == 'TIME_SERIES_PREDICTION'
        assert results[0]['risk_level'] == 'MEDIUM'
    finally:
        try:
            os.unlink(db_path)
        except Exception:
            pass


def test_model_version():
    db_path = _temp_db()
    try:
        store = SQLiteStore(db_path=db_path)
        assert store.save_model_version(
            'v1', '/tmp/rrcf', '/tmp/lstm.pth', '/tmp/norm.params',
            train_samples=1000, train_rounds=2, avg_loss=0.05, accuracy=0.92
        )
        active = store.get_active_version()
        assert active is not None
        assert active['version'] == 'v1'
        assert active['is_active'] == 1

        assert store.save_model_version(
            'v2', '/tmp/rrcf2', '/tmp/lstm2.pth', '/tmp/norm2.params',
            train_samples=2000, train_rounds=3, avg_loss=0.03, accuracy=0.95
        )
        active = store.get_active_version()
        assert active['version'] == 'v2'

        history = store.get_version_history()
        assert len(history) >= 2
    finally:
        try:
            os.unlink(db_path)
        except Exception:
            pass


if __name__ == '__main__':
    test_sqlite_create()
    test_save_and_query_raw()
    test_save_and_query_eval()
    test_model_version()
    print("All storage tests passed!")
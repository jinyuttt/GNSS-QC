# -*- coding: utf-8 -*-
"""Flask HTTP API路由"""

import time
from flask import Blueprint, request, jsonify

api_bp = Blueprint('api', __name__, url_prefix='/api/v1')

_inference_engine = None
_storage = None
_model_manager = None
_trainer = None
_config = None


def init_api(inference_engine, storage, model_manager, trainer, config):
    global _inference_engine, _storage, _model_manager, _trainer, _config
    _inference_engine = inference_engine
    _storage = storage
    _model_manager = model_manager
    _trainer = trainer
    _config = config


@api_bp.route('/health', methods=['GET'])
def health():
    storage_ok = _storage.is_available() if _storage else False
    return jsonify({
        'status': 'ok' if storage_ok else 'degraded',
        'storage': storage_ok,
        'models': _inference_engine is not None,
        'trainer': _trainer is not None and _trainer._running,
        'timestamp': int(time.time() * 1000),
    })


@api_bp.route('/infer', methods=['POST'])
def infer():
    if _inference_engine is None:
        return jsonify({'error': 'Inference engine not initialized'}), 503

    data = request.get_json()
    if not data:
        return jsonify({'error': 'Empty request body'}), 400

    features_list = data.get('features', [])
    if not features_list:
        return jsonify({'error': 'No features provided'}), 400

    station_ids = []
    feats = []
    for item in features_list:
        sid = item.get('stationId', 'unknown')
        station_ids.append(sid)
        feats.append(item)

    results = _inference_engine.infer_batch(station_ids, feats)

    if _storage and _storage.is_available():
        for i, result in enumerate(results):
            sid = station_ids[i]
            epoch = feats[i].get('epochMillis', int(time.time() * 1000))
            _storage.save_raw_data(sid, epoch, feats[i])
            result_with_meta = {
                **result,
                'stationId': sid,
                'epochMillis': epoch,
                'modelVersion': str(_model_manager._current_version) if _model_manager else '',
            }
            _storage.save_eval_result(sid, epoch, result_with_meta)

    return jsonify({
        'results': [
            {
                'stationId': station_ids[i],
                'epochMillis': feats[i].get('epochMillis', 0),
                **results[i],
            }
            for i in range(len(results))
        ],
        'count': len(results),
    })


@api_bp.route('/shadow/<station_id>', methods=['GET'])
def query_shadow(station_id):
    if _storage is None:
        return jsonify({'error': 'Storage not initialized'}), 503

    limit = request.args.get('limit', 100, type=int)
    results = _storage.query_results(station_id, limit)
    return jsonify({
        'stationId': station_id,
        'count': len(results),
        'results': results,
    })


@api_bp.route('/model/version', methods=['GET'])
def model_version():
    if _model_manager is None:
        return jsonify({'error': 'Model manager not initialized'}), 503
    return jsonify(_model_manager.get_stats())


@api_bp.route('/model/rollback', methods=['POST'])
def model_rollback():
    if _model_manager is None:
        return jsonify({'error': 'Model manager not initialized'}), 503

    data = request.get_json() or {}
    target_version = data.get('version')

    if target_version:
        version_info = _model_manager.get_version(target_version)
        if not version_info:
            return jsonify({'error': f'Version {target_version} not found'}), 404
    else:
        version_info = _model_manager.get_previous_stable()
        if not version_info:
            return jsonify({'error': 'No previous version available'}), 404

    _model_manager.trigger_hot_reload(version_info)
    return jsonify({
        'status': 'rollback_triggered',
        'version': version_info['version'],
    })


@api_bp.route('/stats', methods=['GET'])
def stats():
    result = {
        'storage': _storage.get_stats() if _storage else {},
        'model': _model_manager.get_stats() if _model_manager else {},
        'trainer': _trainer.get_stats() if _trainer else {},
        'config': {
            'confidence_threshold': _config.get('eval', {}).get(
                'confidence_threshold', 0.7
            ),
            'max_continuous_err': _config.get('eval', {}).get(
                'max_continuous_err', 20
            ),
        },
    }
    return jsonify(result)


@api_bp.route('/cleanup', methods=['POST'])
def cleanup():
    if _storage is None:
        return jsonify({'error': 'Storage not initialized'}), 503
    count = _storage.cleanup_expired()
    return jsonify({'status': 'ok', 'cleaned': count})
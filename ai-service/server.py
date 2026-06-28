# -*- coding: utf-8 -*-
"""GNSS-QC 影子评测服务 主入口"""

import os
import sys
import yaml
from flask import Flask

from models import RRCFModel, LSTMAttentionModel, FeatureNormalizer, InferenceEngine
from storage import create_storage, SQLiteStore
from training import ModelManager, IncrementalTrainer
from api.routes import api_bp, init_api


def load_config(config_path: str = "config.yaml") -> dict:
    if not os.path.exists(config_path):
        config_path = os.path.join(
            os.path.dirname(os.path.abspath(__file__)), config_path
        )
    with open(config_path, 'r', encoding='utf-8') as f:
        return yaml.safe_load(f)


def create_app(config: dict = None) -> Flask:
    if config is None:
        config = load_config()

    app = Flask(__name__)
    app.register_blueprint(api_bp)

    base_dir = os.path.dirname(os.path.abspath(__file__))

    eval_cfg = config.get('eval', {})
    model_cfg = config.get('model', {})
    lstm_cfg = config.get('lstm', {})
    rrcf_cfg = config.get('rrcf', {})
    train_cfg = config.get('train_task', {})
    update_cfg = config.get('model_update', {})
    storage_cfg = config.get('storage', {})
    server_cfg = config.get('server', {})

    storage = create_storage(storage_cfg)

    norm_file = os.path.join(base_dir, model_cfg.get('norm_file', './model_store/norm.params'))
    normalizer = FeatureNormalizer(norm_file=norm_file)

    rrcf = RRCFModel(
        num_trees=rrcf_cfg.get('num_trees', 100),
        tree_size=rrcf_cfg.get('tree_size', 256),
        shingle_size=rrcf_cfg.get('shingle_size', 4),
    )

    lstm = LSTMAttentionModel(
        input_size=lstm_cfg.get('input_size', 10),
        hidden_size=lstm_cfg.get('hidden_size', 64),
        num_layers=lstm_cfg.get('num_layers', 2),
        attention_heads=lstm_cfg.get('attention_heads', 4),
        dropout=lstm_cfg.get('dropout', 0.2),
        window_size=lstm_cfg.get('window_size', 20),
        output_classes=lstm_cfg.get('output_classes', 3),
    )

    init_rrcf = os.path.join(base_dir, model_cfg.get('init_rrcf', './model_store/rrcf_default'))
    init_lstm = os.path.join(base_dir, model_cfg.get('init_lstm', './model_store/lstm_default.pth'))

    if os.path.exists(os.path.join(init_rrcf, 'rrcf_state.pkl')):
        rrcf.load(init_rrcf)
        print(f"[Server] Loaded default RRCF model from {init_rrcf}")
    if os.path.exists(init_lstm):
        lstm.load(init_lstm)
        print(f"[Server] Loaded default LSTM model from {init_lstm}")

    inference_engine = InferenceEngine(
        rrcf=rrcf, lstm=lstm, normalizer=normalizer,
        confidence_threshold=eval_cfg.get('confidence_threshold', 0.7),
        max_continuous_err=eval_cfg.get('max_continuous_err', 20),
    )

    snapshot_dir = os.path.join(base_dir, model_cfg.get('snapshot_dir', './model_store/snapshot'))
    model_manager = ModelManager(
        snapshot_dir=snapshot_dir,
        keep_versions=update_cfg.get('keep_versions', 15),
        auto_rollback=update_cfg.get('auto_rollback', True),
        error_threshold=update_cfg.get('error_threshold', 0.15),
    )

    inference_engine.set_model_manager(model_manager)

    trainer = IncrementalTrainer(
        rrcf=rrcf, lstm=lstm, normalizer=normalizer,
        storage=storage, model_manager=model_manager,
        inference_engine=inference_engine,
        interval_min=train_cfg.get('interval_min', 5),
        max_fetch_num=train_cfg.get('max_fetch_num', 10000),
        trigger_sample=update_cfg.get('trigger_sample', 5000),
        trigger_round=update_cfg.get('trigger_round', 6),
    )

    def on_hot_reload(version_info):
        print(f"[Server] Hot reloading to {version_info['version']}")
        if os.path.exists(version_info['rrcf_path']):
            rrcf.load(version_info['rrcf_path'])
        if os.path.exists(version_info['lstm_path']):
            lstm.load(version_info['lstm_path'])
        if os.path.exists(version_info['norm_path']):
            normalizer.load(version_info['norm_path'])
        print(f"[Server] Hot reload complete: {version_info['version']}")

    model_manager.register_hot_reload_callback(on_hot_reload)

    init_api(inference_engine, storage, model_manager, trainer, config)

    trainer.start()

    print(f"[Server] Shadow evaluation service initialized")
    print(f"[Server] Storage: {storage.get_stats()}")
    print(f"[Server] Confidence threshold: {eval_cfg.get('confidence_threshold', 0.7)}")
    print(f"[Server] Trainer interval: {train_cfg.get('interval_min', 5)}min")

    return app


def main():
    config = load_config()
    app = create_app(config)
    server_cfg = config.get('server', {})
    host = server_cfg.get('host', '0.0.0.0')
    port = server_cfg.get('port', 8500)
    debug = server_cfg.get('debug', False)

    print(f"[Server] Starting on {host}:{port}")
    app.run(host=host, port=port, debug=debug, threaded=True)


if __name__ == '__main__':
    main()
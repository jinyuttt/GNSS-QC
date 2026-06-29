import numpy as np
import os
import json
from typing import Dict, List, Optional, Tuple
from collections import deque


class LSTMAttentionModel:

    def __init__(self, input_size=10, hidden_size=64,
                 num_layers=2, attention_heads=4,
                 dropout=0.2, window_size=20,
                 output_classes=3, use_torch=True):
        self.input_size = input_size
        self.hidden_size = hidden_size
        self.num_layers = num_layers
        self.attention_heads = attention_heads
        self.dropout = dropout
        self.window_size = window_size
        self.output_classes = output_classes
        self._windows = {}
        self._torch_model = None
        self._use_torch = False
        if use_torch:
            try:
                import torch
                self._build_torch_model()
                self._use_torch = True
            except ImportError:
                print("[LSTM] PyTorch not installed, using numpy fallback")
                self._build_numpy_model()
        else:
            self._build_numpy_model()

    def _build_torch_model(self):
        import torch
        import torch.nn as nn

        class AttentionLayer(nn.Module):
            def __init__(self, hidden_size, num_heads):
                super().__init__()
                self.attention = nn.MultiheadAttention(
                    hidden_size, num_heads, dropout=0.1, batch_first=True
                )
            def forward(self, x):
                attn_out, _ = self.attention(x, x, x)
                return attn_out + x

        class LSTMAttentionNet(nn.Module):
            def __init__(self, input_size, hidden_size, num_layers,
                         num_heads, dropout, output_classes):
                super().__init__()
                self.lstm = nn.LSTM(
                    input_size, hidden_size, num_layers,
                    batch_first=True, dropout=dropout
                )
                self.attention = AttentionLayer(hidden_size, num_heads)
                self.layer_norm = nn.LayerNorm(hidden_size)
                self.dropout = nn.Dropout(dropout)
                self.classifier = nn.Sequential(
                    nn.Linear(hidden_size, hidden_size // 2),
                    nn.ReLU(),
                    nn.Dropout(dropout),
                    nn.Linear(hidden_size // 2, output_classes),
                )
            def forward(self, x):
                lstm_out, _ = self.lstm(x)
                attn_out = self.attention(lstm_out)
                normed = self.layer_norm(attn_out)
                pooled = normed.mean(dim=1)
                pooled = self.dropout(pooled)
                return self.classifier(pooled)

        self._torch_model = LSTMAttentionNet(
            self.input_size, self.hidden_size, self.num_layers,
            self.attention_heads, self.dropout, self.output_classes
        )

    def _build_numpy_model(self):
        scale = np.sqrt(2.0 / self.input_size)
        self._w_lstm = np.random.randn(
            self.input_size, self.hidden_size * 4
        ).astype(np.float32) * scale
        self._u_lstm = np.random.randn(
            self.hidden_size, self.hidden_size * 4
        ).astype(np.float32) * scale
        self._b_lstm = np.zeros(self.hidden_size * 4, dtype=np.float32)
        self._w_attn = np.random.randn(
            self.hidden_size, self.hidden_size
        ).astype(np.float32) * scale
        self._w_cls = np.random.randn(
            self.hidden_size, self.output_classes
        ).astype(np.float32) * scale
        self._b_cls = np.zeros(self.output_classes, dtype=np.float32)

    def _sigmoid(self, x):
        return 1.0 / (1.0 + np.exp(-np.clip(x, -15, 15)))

    def _softmax(self, x, axis=-1):
        x_max = x.max(axis=axis, keepdims=True)
        e_x = np.exp(x - x_max)
        return e_x / e_x.sum(axis=axis, keepdims=True)

    def _lstm_step(self, x, h, c):
        gates = x @ self._w_lstm + h @ self._u_lstm + self._b_lstm
        i = self._sigmoid(gates[:, :self.hidden_size])
        f = self._sigmoid(gates[:, self.hidden_size:2 * self.hidden_size])
        g = np.tanh(gates[:, 2 * self.hidden_size:3 * self.hidden_size])
        o = self._sigmoid(gates[:, 3 * self.hidden_size:])
        c_new = f * c + i * g
        h_new = o * np.tanh(c_new)
        return h_new, c_new

    def _attention(self, sequence):
        if sequence.ndim == 2:
            scores = sequence @ self._w_attn @ sequence.T
            attn_weights = self._softmax(scores, axis=-1)
            return attn_weights @ sequence
        batch_size, seq_len, hidden = sequence.shape
        output = np.zeros_like(sequence)
        for b in range(batch_size):
            s = sequence[b]
            scores = s @ self._w_attn @ s.T
            attn_weights = self._softmax(scores, axis=-1)
            output[b] = attn_weights @ s
        return output

    def _numpy_forward(self, X):
        batch_size = X.shape[0] if X.ndim == 3 else 1
        if X.ndim == 2:
            X = X[np.newaxis, :, :]
        h = np.zeros((batch_size, self.hidden_size), dtype=np.float32)
        c = np.zeros((batch_size, self.hidden_size), dtype=np.float32)
        seq_len = X.shape[1]
        outputs = np.zeros((batch_size, seq_len, self.hidden_size), dtype=np.float32)
        for t in range(seq_len):
            h, c = self._lstm_step(X[:, t, :], h, c)
            outputs[:, t, :] = h
        attended = self._attention(outputs)
        pooled = attended.mean(axis=1)
        logits = pooled @ self._w_cls + self._b_cls
        if batch_size == 1:
            return self._softmax(logits[0])
        return self._softmax(logits)

    def _update_window(self, station_id, features):
        point = np.array([
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
        ], dtype=np.float32)
        if station_id not in self._windows:
            self._windows[station_id] = deque(maxlen=self.window_size)
        self._windows[station_id].append(point)
        if len(self._windows[station_id]) < self.window_size:
            pad_count = self.window_size - len(self._windows[station_id])
            window = list(self._windows[station_id])
            for _ in range(pad_count):
                window.insert(0, np.zeros(self.input_size, dtype=np.float32))
            return np.array(window)
        return np.array(self._windows[station_id])

    def predict(self, station_id, features):
        window = self._update_window(station_id, features)
        if self._use_torch and self._torch_model is not None:
            import torch
            self._torch_model.eval()
            with torch.no_grad():
                x = torch.from_numpy(window).float().unsqueeze(0)
                logits = self._torch_model(x)
                probs = torch.softmax(logits, dim=-1).numpy()[0]
        else:
            probs = self._numpy_forward(window)
            if probs.ndim == 2:
                probs = probs[0]
        class_idx = int(np.argmax(probs))
        class_names = ['NORMAL', 'PSEUDO_DEFORMATION', 'REAL_DEFORMATION']
        confidence = float(probs[class_idx])
        return {
            'class_id': class_idx,
            'class_name': class_names[class_idx],
            'confidence': confidence,
            'probabilities': probs.tolist(),
        }

    def predict_batch(self, station_ids, features_list):
        return [
            self.predict(sid, feat)
            for sid, feat in zip(station_ids, features_list)
        ]

    def save(self, filepath):
        os.makedirs(os.path.dirname(filepath), exist_ok=True)
        if self._use_torch and self._torch_model is not None:
            import torch
            torch.save(self._torch_model.state_dict(), filepath)
        else:
            state = {
                'w_lstm': self._w_lstm.tolist(),
                'u_lstm': self._u_lstm.tolist(),
                'b_lstm': self._b_lstm.tolist(),
                'w_attn': self._w_attn.tolist(),
                'w_cls': self._w_cls.tolist(),
                'b_cls': self._b_cls.tolist(),
                'input_size': self.input_size,
                'hidden_size': self.hidden_size,
                'output_classes': self.output_classes,
            }
            with open(filepath, 'w') as f:
                json.dump(state, f)

    def load(self, filepath):
        if not os.path.exists(filepath):
            return False
        if self._use_torch and self._torch_model is not None:
            import torch
            self._torch_model.load_state_dict(
                torch.load(filepath, map_location='cpu')
            )
        else:
            with open(filepath, 'r') as f:
                state = json.load(f)
            self._w_lstm = np.array(state['w_lstm'], dtype=np.float32)
            self._u_lstm = np.array(state['u_lstm'], dtype=np.float32)
            self._b_lstm = np.array(state['b_lstm'], dtype=np.float32)
            self._w_attn = np.array(state['w_attn'], dtype=np.float32)
            self._w_cls = np.array(state['w_cls'], dtype=np.float32)
            self._b_cls = np.array(state['b_cls'], dtype=np.float32)
        return True

    def train_step(self, X, y, learning_rate=0.001):
        batch_size = min(X.shape[0], 32)
        total_loss = 0.0
        for i in range(0, X.shape[0], batch_size):
            batch_X = X[i:i + batch_size]
            batch_y = y[i:i + batch_size]
            if self._use_torch and self._torch_model is not None:
                import torch
                import torch.nn as nn
                self._torch_model.train()
                optimizer = torch.optim.Adam(
                    self._torch_model.parameters(), lr=learning_rate
                )
                criterion = nn.CrossEntropyLoss()
                x_t = torch.from_numpy(batch_X).float()
                y_t = torch.from_numpy(batch_y).long()
                logits = self._torch_model(x_t)
                loss = criterion(logits, y_t)
                optimizer.zero_grad()
                loss.backward()
                optimizer.step()
                total_loss += loss.item()
            else:
                for j in range(len(batch_X)):
                    probs = self._numpy_forward(batch_X[j])
                    loss = -np.log(max(probs[batch_y[j]], 1e-8))
                    total_loss += loss
        return total_loss / max(X.shape[0], 1)

    def clear_window(self, station_id):
        if station_id in self._windows:
            del self._windows[station_id]
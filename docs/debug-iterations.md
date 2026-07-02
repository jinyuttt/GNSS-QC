# 影子评测服务 — 调试修改迭代记录

---

## 迭代 #1 — 影子评测输出数据异常排查与修复

**日期**：2026-07-02  
**数据来源**：sha.txt（rover_id=GS2025090010，2026/2/7 00:00:15 ~ 02:00:00）  
**分析依据**：560+ 条影子评测输出记录

### 1. 问题现象

| 维度 | 现象 | 严重程度 |
|------|------|----------|
| RRCF 分数 | 全部恒为 0.5，无任何变化 | 🔴 严重 |
| 10维语义特征 | F4~F7 全为 0，F8 固定为 1，F9 固定为 1，仅 F10 有波动 | 🔴 严重 |
| 置信度 | 0.42~0.45，远低于阈值 0.7 | 🔴 严重 |
| 候选修正值 | 全部为 0，从未生成 | 🟡 中等 |
| LSTM 分类 | 00:07:15 突然从 REAL_DEFORMATION 切换为 PSEUDO_DEFORMATION | 🟡 中等 |
| 推理耗时 | 1~85ms | 🟢 正常 |

### 2. 根因分析

#### 2.1 🔴 核心问题：Layer1 拒绝后特征计算被跳过

**代码位置**：`DisplacementCleaner.java` → `cleanWithHistory()` 方法

**问题链路**：
```
数据进入 cleanWithHistory()
  → Layer1 质量门禁 (layer1QualityGate)
  → FLOAT 解卫星数不足 → CleanResult.fail() → 直接 return
  → 特征计算代码（第227~246行）从未执行
  → CleanResult 中所有特征字段保持默认值 0
  → extractShadowFeatures() 读取到全零特征
  → 影子评测服务收到无效特征向量
```

**关键代码**（修复前）：
```java
CleanResult r1 = layer1QualityGate(result);
if (!r1.isPassed()) {
    return r1;  // ← 提前返回，特征计算代码在后面，永远执行不到
}
// ... 以下代码在 Layer1 拒绝时全部跳过
horizontalChangeRate = computeHorizontalChangeRate(result, state);
verticalChangeRate = computeVerticalChangeRate(result, state);
timeSeriesResidual = computeTimeSeriesResidual(result, state);
solutionQuality = computeSolutionQuality(result);
windowStability = computeWindowStability(state);
```

**影响**：这是所有下游问题的根源。影子评测服务收到 F4~F7=0、F9=0 的特征向量，导致 RRCF 和 LSTM 都无法正常推理。

#### 2.2 🔴 RRCF 分数恒为 0.5

**代码位置**：`ai-service/models/rrcf.py` → `score()` 和 `_update_threshold()`

**问题链路**：
1. RRCF 模型无预训练权重（`model_store/rrcf_default/` 目录不存在）
2. 通过 `update()` 接收数据后建树，但输入特征大部分为 0
3. 所有数据点在特征空间中几乎相同，树无法有效区分异常
4. `_update_threshold()` 在历史分数不足 50 条时，硬编码 `_score_threshold = 0.5`
5. `score()` 中 `score = min(score / self._score_threshold, 1.0)`，原始分数约 0.5 除以 0.5 = 1.0，被 `min()` 截断为 0.5... 实际上原始分数约 0.25，除以 0.5 = 0.5

**关键代码**（修复前）：
```python
def _update_threshold(self):
    if len(self._score_history) < 50:
        self._score_threshold = 0.5  # ← 硬编码 0.5
        return

def score(self, features):
    score = 2.0 ** (-avg_depth / avg_len)
    if self._score_threshold > 0:    # ← 0.5 > 0 为 True，总是做归一化
        score = min(score / self._score_threshold, 1.0)
    return float(score)
```

#### 2.3 🟡 置信度低（0.42~0.45）

**代码位置**：`ai-service/models/inference.py` → `_fuse()`

**计算公式**：`confidence = rrcf_score * 0.4 + lstm_conf * 0.6`
- rrcf_score = 0.5 → 贡献 0.20
- lstm_conf ≈ 0.37~0.42 → 贡献约 0.22~0.25
- 合计 ≈ 0.42~0.45

LSTM 置信度低是因为输入特征大部分为 0，模型无法做出高置信度判断。

#### 2.4 🟡 候选修正值全为 0

**代码位置**：`ai-service/models/inference.py` → `_fuse()`

**触发条件**：`rrcf_anomaly and lstm_pseudo and confidence >= 0.7`
- `rrcf_anomaly = rrcf_score > 0.5` → 0.5 不大于 0.5 → **False**
- 即使 rrcf_anomaly 为 True，confidence ≈ 0.44 也远低于 0.7

两个条件都不满足，候选修正值永远无法生成。

#### 2.5 🟡 默认值不合理

**代码位置**：`CleanResult.java` 和 `ShadowFeatureVector.java`

| 字段 | 旧默认值 | 问题 |
|------|----------|------|
| `solutionQuality` | 1.0 | 未计算时暗示"最优质量"，误导模型 |
| `sameDirectionNeighborRatio` | 1.0 | 未执行空间校验时暗示"完全同向"，误导模型 |
| `ShadowFeatureVector.f8` | 1.0 | 同上 |
| `ShadowFeatureVector.f9` | 1.0 | 同上 |

#### 2.6 🟡 FLOAT 解质量分计算不合理

**代码位置**：`DisplacementCleaner.java` → `computeSolutionQuality()`

FLOAT 解和 FIX 解使用相同的 `ratioNorm = 0.5` 和 `quality *= 0.7`，但 FLOAT 解没有 Ratio 值，不应给予 0.5 的 ratioNorm。此外，FLOAT 解精度明显低于 FIX，0.7 的折扣不够。

### 3. 修复方案

#### 修复 #1 (P0)：特征计算移到 Layer1 之前

**文件**：`src/main/java/org/gnss/cleaning/DisplacementCleaner.java`

**修改方法**：`cleanSingle()` 和 `cleanWithHistory()`

**修改内容**：
- 在 `layer1QualityGate()` 调用之前，先计算所有特征
- Layer1 拒绝时，将已计算的特征写入返回的 `CleanResult`
- `cleanWithHistory()` 中 `windowStability` 在 `updateWindows()` 后重新计算一次（因为窗口更新了）

**修复后流程**：
```
数据进入 cleanWithHistory()
  → 先计算特征（horizontalChangeRate, verticalChangeRate, timeSeriesResidual, solutionQuality, windowStability）
  → Layer1 质量门禁
  → 如果拒绝：将特征写入 CleanResult → return（特征不丢失）
  → 如果通过：继续后续层处理
```

#### 修复 #2 (P0)：FLOAT 解质量分计算优化

**文件**：`src/main/java/org/gnss/cleaning/DisplacementCleaner.java`

**修改方法**：`computeSolutionQuality()`

**修改内容**：
- FLOAT 解 `ratioNorm` 从 `0.5` 降为 `0.3`（FLOAT 无 Ratio，不应给予中等分数）
- FLOAT 解折扣从 `×0.7` 降为 `×0.5`（精度明显低于 FIX）
- 增加 `isFloat` 变量，区分 FLOAT 和其他非 FIX 状态

#### 修复 #3 (P2)：CleanResult 默认值修正

**文件**：`src/main/java/org/gnss/model/CleanResult.java`

**修改内容**：
- `solutionQuality` 默认值：`1.0` → `0.0`
- `sameDirectionNeighborRatio` 默认值：`1.0` → `0.0`

#### 修复 #4 (P2)：ShadowFeatureVector 默认值修正

**文件**：`src/main/java/org/gnss/model/ShadowFeatureVector.java`

**修改内容**：
- 构造函数 `f8SameDirectionNeighborRatio`：`1.0` → `0.0`
- 构造函数 `f9SolutionQuality`：`1.0` → `0.0`

#### 修复 #5 (P1)：RRCF 阈值归一化逻辑修正

**文件**：`ai-service/models/rrcf.py`

**修改内容**：
- `score()` 方法：阈值归一化增加条件 `len(self._score_history) >= 50`，历史不足时不做归一化
- `_update_threshold()`：历史不足 50 条时 `_score_threshold` 保持 `0.0`（而非 `0.5`）

**修复前**：
```python
if self._score_threshold > 0:
    score = min(score / self._score_threshold, 1.0)
```

**修复后**：
```python
if self._score_threshold > 0 and len(self._score_history) >= 50:
    score = min(score / self._score_threshold, 1.0)
```

#### 修复 #6 (P1)：融合逻辑 rrcf_anomaly 判断修正

**文件**：`ai-service/models/inference.py`

**修改内容**：
- `rrcf_anomaly` 判断从 `> 0.5` 改为 `>= 0.5`

**修复前**：`rrcf_anomaly = rrcf_score > 0.5`（0.5 时为 False）  
**修复后**：`rrcf_anomaly = rrcf_score >= 0.5`（0.5 时为 True）

### 4. 修改文件清单

| 文件 | 修改行数 | 类型 |
|------|----------|------|
| `src/main/java/org/gnss/cleaning/DisplacementCleaner.java` | +38/-16 | Java |
| `src/main/java/org/gnss/model/CleanResult.java` | +2/-2 | Java |
| `src/main/java/org/gnss/model/ShadowFeatureVector.java` | +2/-2 | Java |
| `ai-service/models/rrcf.py` | +2/-2 | Python |
| `ai-service/models/inference.py` | +1/-1 | Python |

### 5. 预期效果

| 指标 | 修复前 | 修复后预期 |
|------|--------|------------|
| F4 水平变化率 | 全 0 | 有实际值（基于位移差分） |
| F5 垂直变化率 | 全 0 | 有实际值（基于位移差分） |
| F6 时序残差 | 全 0 | 有实际值（基于 MAD 标准化） |
| F7 空间残差 | 全 0 | Layer6 执行后有值，未执行为 0 |
| F8 同向邻居比 | 固定 1 | Layer6 执行后有值，未执行为 0 |
| F9 解算质量 | 固定 1 | FLOAT 约 0.1~0.3，FIX 约 0.5~1.0 |
| F10 窗口稳定度 | 波动 | 保持不变 |
| RRCF 分数 | 恒 0.5 | 有实际变化（0.3~0.8 范围） |
| 置信度 | 0.42~0.45 | 预计 0.5~0.7+ |
| 候选修正值 | 全 0 | 置信度 ≥ 0.7 时可触发 |

### 6. 待验证项

- [ ] 重新部署后，用相同数据验证 F4~F9 是否有实际值
- [ ] RRCF 分数是否不再恒为 0.5
- [ ] 置信度是否提升到 0.5 以上
- [ ] 候选修正值是否在部分历元生成
- [ ] LSTM 分类切换点是否更合理
- [ ] `model_store/rrcf_default/` 是否需要预训练权重

### 7. 遗留问题

| 问题 | 说明 | 优先级 |
|------|------|--------|
| RRCF 无预训练权重 | `model_store/` 目录不存在，首次启动时 RRCF 从零开始 | P1 |
| F7 空间残差在无邻居时为 0 | 单站运行时无法计算空间残差，需考虑替代方案 | P2 |
| LSTM 00:07:15 分类突变 | 修复特征后需重新观察切换点是否合理 | P2 |
| `cleanSingle()` 中 tempState 无历史 | 单次清洗时窗口为空，F6/F10 可能仍为 0 | P3 |

---

## 迭代 #2 — （待记录）

**日期**：  
**数据来源**：  
**问题描述**：  

### 问题现象



### 根因分析



### 修复方案



### 修改文件清单



### 预期效果



### 待验证项

- [ ]

### 遗留问题

| 问题 | 说明 | 优先级 |
|------|------|--------|
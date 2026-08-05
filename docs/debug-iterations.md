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

## 迭代 #2 — L3 拟合污染致死循环 & L6 PCA 方向反转修复

**日期**：2026-08-05  
**数据来源**：GS2025090001，2026-08-05 全天数据（TDengine l7 表 + MySQL clean_result/clean_layer5_result）  
**分析依据**：611 条 L7 记录、591 条 L1-passed L5 记录、图表 API 实时数据

### 1. 问题现象

| 维度 | 现象 | 严重程度 |
|------|------|----------|
| L5 北向值 | 484/611 条恒为 -0.003761（图表显示 -3.761mm），占比 79% | 🔴 严重 |
| L6 替换值 | L5 正常值 +0.002393 被 L6 替换为 -0.011507（方向反转） | 🔴 严重 |
| 原始数据 | disp_n ≈ 0.003（正常范围 -0.0009 ~ 0.0053） | 🟢 正常 |
| 影子旁路 | candidate_n=0（1369/1375），特征 f6_ts_residual=13.2 异常大 | 🟡 中等 |

### 2. 根因分析

#### 2.1 🔴 L3 detrendedResiduals 让当前值参与拟合，导致 L4 死循环

**代码位置**：`DisplacementCleaner.java` → `detrendedResiduals()` / `checkHampel()`

**问题链路**：
```
窗口全是 L4 替换值 -0.003761（wasL4Replaced 已阻止更新 lastValid，但替换值仍入窗口）
  → 当前原始值 0.003 与窗口一起参与线性拟合
  → 拟合线被当前值拉偏，产生上升趋势
  → 窗口点残差 ≈ ±0.001（小），当前值残差 ≈ 0.0056（大）
  → MAD ≈ 0.0005（不为0，不触发 fallback）
  → 阈值 = 2.5 × 0.0005 = 0.00125
  → 0.0056 > 0.00125 → 判为异常 → L4 用中位数 -0.003761 替换
  → 替换值入窗口 → 回到步骤1 → 死循环
```

**关键缺陷**：`detrendedResiduals` 将当前值加入窗口一起做线性拟合，导致：
- 窗口全相同时，当前值参与拟合使拟合线被拉偏
- 当前值残差被人为放大，窗口点残差被人为缩小
- MAD 不为 0，无法触发 `windowInitFallbackSigma` 兜底

#### 2.2 🔴 L6 PCA 重构值方向反转

**代码位置**：`PcaSpatialCheckService.java` → `spatialCheck()`

**问题链路**：
```
L5 输出正常值 +0.002393（+2.4mm，北向正向）
  → PCA 提取群体共有主成分（群体大部分为负位移）
  → PCA 重构值 = -0.011507（-11.5mm，北向负向）
  → 方向完全相反！
  → spatial_residual = 0.0706（7cm），same_direction_ratio = 0.5
  → outlier 判断仅用相对 MAD（3.0 × MAD），未用方向/同向占比
  → 判为异常 → 用 PCA 重构值 -0.011507 替换
```

**关键缺陷**：
1. PCA 重构值是群体趋势投影，不是设备真实位移，方向可能反转
2. `sameDirectionRatio` 计算了但未参与判决
3. 仅用相对 MAD 阈值，低方差组（MAD 极小）时正常值也被判异常
4. 替换值用 PCA 重构值（含自身数据），自身异常会污染重构结果

### 3. 修复方案

#### 修复 #1 (P0)：detrendedResiduals 当前值不参与拟合

**文件**：`src/main/java/org/gnss/cleaning/DisplacementCleaner.java`

**修改内容**：
- `detrendedResiduals` 只用窗口历史数据建立趋势基线，当前值不参与拟合
- 窗口全相同时残差全为 0 → MAD=0 → 触发 `windowInitFallbackSigma=0.01` 兜底
- 阈值 = 2.5 × 0.01 = 0.025m = 25mm，原始值偏离仅 6.8mm < 25mm → 不判异常

#### 修复 #2 (P0)：L6 PCA 废弃重构值作为替换输出

**文件**：`src/main/java/org/gnss/cleaning/PcaSpatialCheckService.java`（重写）

**修改内容**：
- PCA 依旧运行，仅用于计算公共模式残差 `pcaResidual`（保存到 `SpatialCheckResult#pcaResidual`）
- 不再用重构值改写观测，彻底解决方向反转问题

#### 修复 #3 (P0)：sameDirectionRatio 参与判决

**修改内容**：
- 同向占比 ≥ `spatialSameDirectionThreshold`（0.5）时不触发替换
- 允许测点真实局部位移，指标原样输出给上层

#### 修复 #4 (P0)：双阈值联合判决

**修改内容**：
- 只有同时满足三个条件才判空间异常：
  - `directionOpposite`：设备与邻居中位数方向相反
  - `absoluteExceed`：绝对残差 > `spatialAbsoluteResidualThreshold`（3cm）
  - `madExceed`：相对 MAD 超限（`outlierThreshold × MAD`）
- 解决低方差组 MAD 误判

#### 修复 #5 (P0)：替换值改为剔除自身后的邻居中位数

**修改内容**：
- `replacedN/E/U` 由剔除自身后的邻居中位数计算
- 物理含义明确，不会出现符号反转
- 规避待测站自身异常污染 PCA 矩阵的连锁问题

#### 修复 #6 (P1)：两种修复模式

**文件**：`src/main/java/org/gnss/config/SpatialRepairMode.java`（新建）

**修改内容**：
- `CAUTIOUS`（默认）：满足严格条件才替换
- `NO_REPAIR`：L6 仅计算指标，直接透传 L5 结果，不修改任何数据

#### 修复 #7 (P2)：DefaultSpatialCheckService 同步判决逻辑

**文件**：`src/main/java/org/gnss/cleaning/DefaultSpatialCheckService.java`

**修改内容**：
- fallback 路径（设备数<3）也加入方向相反 + 同向占比判断
- 保持与 PCA 路径逻辑一致

### 4. 修改文件清单

| 文件 | 修改行数 | 类型 |
|------|----------|------|
| `src/main/java/org/gnss/cleaning/DisplacementCleaner.java` | +30/-29 | Java |
| `src/main/java/org/gnss/cleaning/PcaSpatialCheckService.java` | +120/-41 | Java（重写） |
| `src/main/java/org/gnss/cleaning/DefaultSpatialCheckService.java` | +13/-6 | Java |
| `src/main/java/org/gnss/config/CleanConfig.java` | +12/-0 | Java |
| `src/main/java/org/gnss/config/SpatialRepairMode.java` | +11/-0 | Java（新建） |
| `src/main/java/org/gnss/model/SpatialCheckResult.java` | +6/-0 | Java |

### 5. 预期效果

| 指标 | 修复前 | 修复后预期 |
|------|--------|------------|
| L5 北向值 | 79% 恒为 -0.003761 | 恢复为真实测量值（≈0.003） |
| L6 替换 | 正常值被反向替换为 -0.011507 | 方向相反+双阈值超限才替换 |
| 图表显示 | 长时间固定 -3.761mm | 跟随真实位移波动 |
| 影子 f6_ts_residual | 13.2（异常大） | 恢复正常范围 |
| 影子 f10_window_stability | 0（异常小） | 恢复正常范围 |

### 6. 待验证项

- [ ] 重启服务后，L5 不再出现 -0.003761 固定值
- [ ] L6 不再将正向 L5 值替换为负向值
- [ ] 图表 clean 曲线跟随真实位移波动
- [ ] 影子旁路特征值恢复正常
- [ ] NO_REPAIR 模式下 L6 完全透传 L5

### 7. 遗留问题

| 问题 | 说明 | 优先级 |
|------|------|--------|
| L4 中位数替换值仍入窗口 | wasL4Replaced 已阻止更新 lastValid，但替换值仍参与窗口中位数计算，可能影响后续检测 | P2 |
| OutlierPostProcessor 全局 MAD 兜底 | 已添加全局 median/MAD fallback 处理平台型离群点，需验证长期效果 | P2 |
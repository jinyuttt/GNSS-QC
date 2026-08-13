
# GNSS-QC - GNSS变形监测数据质量控制引擎

## 概述

GNSS-QC 是一个用于 GNSS 变形监测数据质量控制的七层递进式清洗引擎，旨在提高数据可靠性和准确性。该引擎采用分层递进架构，从基础质量控制到高级异常检测，逐步提升数据质量。

## 架构概览

### 七层递进式质量控制架构

| 层级 | 名称 | 功能 | 状态 |
|------|------|------|------|
| **L0** | 小波去噪 | 使用 Daubechies 4 小波 + 软阈值去噪预处理 | 新增 |
| **L1** | 质量门禁 | 基于解类型、Ratio、RMS、PDOP、卫星数的质量筛选 + 位移阈值门控（FIX/FLOAT分别配置） | 增强 |
| **L2** | 跳变检测 | Hampel 滤波器检测突变 + CUSUM 累积和检测缓慢漂移 | 增强 |
| **L3** | 统计粗差 | Hampel/IQR/3σ 基础检测 + 小波残差双模检测 | 增强 |
| **L4** | 值替换 | 单点中位数替换 + 连续粗差分段替换 + 无效数据段标记 | 升级 |
| **L5** | 基线记忆 | LOESS 局部加权回归计算慢基线（替换原中位数方法） | 替换 |
| **L6** | 空间校验 | PCA 计算公共模式残差 + 双阈值联合判决 + 邻居中位数替换 + 两种修复模式 | 重构 |
| **L7** | 综合仲裁 | 加权综合异常分（WCS）判决 + 三项判决 + 趋势保护 | 重构 |
| **L7s** | 影子评测（旁支） | RRCF + LSTM+Attention 双模型融合推理，候选修正值仅写影子表 | 新增（旁支） |
| **L8** | 事后检验（旁支） | Pettitt 非参数变点检验，异步定时扫描外部存储，检测结构突变点 | 新增（旁支） |

### 分层设计原则

1. **递进式过滤**：每一层基于前一层的输出进行处理，逐步提升数据质量
2. **可配置性**：每层功能均可独立启用/禁用，参数可配置
3. **状态管理**：通过 DeviceState 维护设备级别的历史状态
4. **旁路设计**：L7s 影子评测和 L8 事后检验均为旁支，不影响实时清洗主链路
5. **清洗轨迹**：每层输出 LayerResult 记录通过状态、替换值、替换方式、失败原因，汇总到 CleanResult.layerResults 实现完整追溯

### 数据流程图

`
原始位移数据
    ↓
L0 小波去噪 ──────────────────┐
    ↓                        │
L1 质量门禁                  │
    ↓                        │
L2 跳变检测 + CUSUM漂移       │
    ↓                        │
L3 双模粗差检测 ←─────────────┘ （小波残差检测）
    ↓
L4 值替换（单点/连续/无效段）
    ↓
L5 LOESS慢基线
    ↓
L6 PCA空间校验
    ↓
L7 综合仲裁（WCS + 三项判决 + 趋势保护）
    ↓
清洗结果输出（含 layerResults 清洗轨迹）
    ↓
historyProvider.saveHistory() → 外部存储（Redis/时序库）

┌──────────────────────────────────────────────────────────────────────┐
│  L7s 影子评测（旁支）                                                │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  同步：CleanResult → 10维特征 → RRCF+LSTM推理 → 候选修正值    │   │
│  │  候选修正值仅写影子表，永不替换线上正式数据                       │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  L8 事后检验（旁支，独立异步）                                       │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  定时查询外部存储 → Pettitt检验 → 检测变点 → 生成修正记录       │   │
│  │  通过 CorrectionCallback 持久化，可回溯修正线上数据              │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
`

### L7 分支关系

```
                    ┌─────────────────────────────────────┐
                    │       L7 综合仲裁（主链路）           │
                    │  WCS分数 → 三项判决 → 趋势保护        │
                    └────────┬────────────┬──────────────┘
                             │            │
                    ┌────────▼──────┐  ┌──▼──────────────────┐
                    │ L7s 影子评测   │  │ L8 事后检验          │
                    │ (同步旁支)     │  │ (异步旁支)           │
                    │               │  │                      │
                    │ 每历元同步推理  │  │ 定时查外部存储        │
                    │ 写影子表       │  │ 生成修正记录          │
                    │ 不替换线上数据  │  │ 可修正线上数据        │
                    └───────────────┘  └──────────────────────┘
```


## 清洗轨迹追溯

每层清洗独立输出 `LayerResult`，汇总到 `CleanResult.layerResults`，实现完整清洗过程可追溯。

### LayerResult 结构

| 字段 | 类型 | 说明 |
|------|------|------|
| layer | int | 层级编号（1~7） |
| passed | boolean | 是否通过 |
| valueN/E/U | double | 该层输出的位移值 |
| replacementMethod | enum | 替换方式（LAST_VALID / MEDIAN / MEAN / INITIAL_BASELINE） |
| reason | String | 未通过原因 |
| stepFlag | double | 阶跃标记 |

### 替换方式枚举

| 方法 | 说明 | 适用层 |
|------|------|--------|
| LAST_VALID | 上一合法值替换 | L1 |
| MEDIAN | 窗口中位数替换 | L4, L6, L7 |
| MEAN | 窗口均值替换 | L4 |
| INITIAL_BASELINE | 初始基线替换 | L5 |

### CleanResult 汇总

| 字段 | 说明 |
|------|------|
| layerResults | 各层 LayerResult 列表（List\<LayerResult\>） |
| replacementSummary | 替换汇总标记（如 "L1:LAST_VALID" 或 "L4:MEDIAN"） |

### 各层 LayerResult 产出

| 层级 | 产出方式 | 替换触发条件 |
|------|----------|-------------|
| L1 | layer1QualityGate 返回 LayerResult | 质量不达标时用 lastValid 替换 |
| L2 | 构造 LayerResult.builder(2) | 仅标记，不替换 |
| L3 | 构造 LayerResult.builder(3) | 仅标记，不替换 |
| L4 | layer4AnomalyReplacement 返回 LayerResult | L3 检测到粗差时用中位数/均值替换 |
| L5 | 构造 LayerResult.builder(5) | 仅标记阶跃，不替换 |
| L6 | SpatialCheckResult.toLayerResult() | 空间异常时用邻居中位数替换 |
| L7 | Layer7ArbitrationResult.toLayerResult() | 三项判决全满足时用历史低分点中位数替换 |

---

## L7s 影子评测（旁支）

独立部署的微服务，与主清洗服务解耦，关停不影响主链路。基于 RRCF + LSTM+Attention 双模型融合架构。

### 双环路设计

| 环路 | 功能 | 频率 | SLA |
|------|------|------|-----|
| 环路1 | 同步实时推理 + 生成候选修正数据 | 每历元 | ≤50ms |
| 环路2 | 增量学习 + 模型自动迭代 + 无感知热加载 | 5分钟 | - |

### 10维语义特征向量

| 特征 | 来源层 | 含义 |
|------|--------|------|
| F1~F3 | L5/L6 | N/E/U 位移值 |
| F4~F5 | L2 | 水平/垂直变化率 |
| F6 | L3 | 时序标准化残差 |
| F7 | L6 | 空间残差（无则置0） |
| F8 | L6 | 同向邻居占比（无则置0） |
| F9 | L1 | 解算质量分 |
| F10 | 历史 | 窗口稳定度（40条 max-min） |

### 推理判定规则

- RRCF 识别为突变 **且** LSTM 判定为非真实形变 **且** 置信度 ≥ 0.7 → 生成候选修正值
- 判定为真实滑坡形变 → 不生成修正值，仅标记告警
- 结论冲突 / 置信度不足 → 标记待人工复核

### 形变类型

| 类型 | 说明 |
|------|------|
| PSEUDO_DEFORMATION | 伪形变（多径等引起） |
| REAL_DEFORMATION | 真实滑坡形变 |
| UNCERTAIN | 不确定，需人工复核 |

### 候选修正值类型

| 类型 | 说明 | 适用场景 |
|------|------|----------|
| TIME_SERIES_PREDICTION | LSTM 时序预测 | 单点异常 |
| NEIGHBOR_INTERPOLATION | 邻域插值 | 连续短时异常（≤20历元） |
| NONE | 不生成 | 长时段失锁（>20历元） |

### 核心约束

- **候选修正值仅写入独立影子表，永不替换线上正式数据**
- 服务不可用或异常时静默返回 null，不影响主链路
- Python 服务端实现 RRCF + LSTM+Attention，HTTP API 对外提供推理

---

## L8 事后检验（旁支）

完全独立的异步旁路，定时从外部存储拉取历史数据，检测 L3/L4 可能漏掉的隐藏漂移块。

### 运行机制

```
ChangePointScanner.start()
  → ScheduledExecutorService（每 N 分钟触发）
    → historyProvider.getActiveStationIds()    // 查活跃测站
      → historyProvider.queryRecent(stationId, windowSize)  // 查最近窗口历史
        → Pettitt 非参数变点检验（N/E/U 三分量）
          → 检测到变点（p < 0.05）且偏移量 ≥ 阈值
            → 生成 DataCorrectionRecord
              → CorrectionCallback.onCorrection(record)  // 持久化到数据库
```

### 与主链路的关系

主链路每历元通过 `historyProvider.saveHistory()` 写入外部存储，L8 定时从同一存储读取——**主链路写，L8 读，完全解耦**。

### 与 L7s 影子评测的区别

| 维度 | L7s 影子评测 | L8 事后检验 |
|------|-------------|-------------|
| 触发方式 | 同步，每历元 | 异步，定时扫描 |
| 算法 | RRCF + LSTM（Python） | Pettitt 检验（Java） |
| 输出 | 候选修正值（影子表，不替换线上） | 修正记录（可回溯修正线上数据） |
| 目的 | 效果评估 + 模型迭代 | 发现漏检漂移块，事后修正 |
| 对线上数据影响 | **无** | **有**（通过 CorrectionCallback） |
| LayerResult 适配 | 不适用（旁路影子表） | 不适用（跨历元批量修正） |

### DataCorrectionRecord 结构

| 字段 | 说明 |
|------|------|
| correctionId | 修正ID（格式：YYYYMMDDHHmmSS_CP） |
| deviceId | 设备ID |
| fromDataId / toDataId | 修正范围 |
| type | 修正类型（TREND_MISJUDGE） |
| corrections | 逐历元修正值列表（DataCorrectionItem） |
| status | 修正状态（APPLIED / PENDING） |

---

## 增强功能清单

基于七层清洗引擎增强方案，本阶段实现了以下核心能力提升：

### ✅ 时频分析能力（小波去噪）—— 看到"形状不对"的脏数据

**实现方式**：基于 Smile 库的 Daubechies 4 小波 + 软阈值去噪

**核心价值**：
- 有效分离信号与噪声，保留真实形变信息
- 识别数据中的高频噪声和异常波动
- 为后续粗差检测提供更干净的输入数据

**配置参数**：
- waveletEnabled — 启用/禁用小波去噪
- waveletWindowSize — 小波窗口大小（默认32）
- waveletThresholdScale — 软阈值缩放系数（默认0.7）

---

### ✅ 累积漂移嗅觉（CUSUM）—— 提前嗅到缓慢爬坡

**实现方式**：基于 MAD 的累积和控制图

**核心价值**：
- 检测缓慢的系统性漂移趋势
- 比传统阈值检测更早发现异常
- 三维分量独立检测，精准定位漂移方向

**检测原理**：
`
CUSUM+ = max(0, CUSUM+_prev + (x - median) - K×MAD)
CUSUM- = max(0, CUSUM-_prev - (x - median) - K×MAD)
触发条件：CUSUM+ > H×MAD 或 CUSUM- > H×MAD
`

**配置参数**：
- cusumEnabled — 启用/禁用CUSUM检测
- cusumK — 灵敏度系数（默认0.5×MAD）
- cusumH — 报警阈值（默认5.0×MAD）

---

### ✅ 空间滤波能力（PCA）—— 根治集体跑偏与方向反转

**实现方式**：基于 Hipparchus 的主成分分析（仅计算公共模式残差，不再用重构值改写观测）

**核心价值**：
- 提取空间公共模式残差（pcaResidual），作为空间异常判决的辅助指标
- 彻底解决 PCA 重构值方向反转问题（重构值不再作为替换输出）
- 双阈值联合判决（相对 MAD + 绝对残差）+ 方向相反条件，解决低方差组误判
- 同向邻居占比参与判决，允许测点真实局部位移
- 替换值采用剔除自身后的邻居中位数，物理含义明确，不会出现符号反转

**处理流程**：
1. 收集同组测站位移数据
2. PCA 分解提取主成分，计算各测站公共模式残差（pcaResidual）
3. 计算邻居中位数、同向占比、相对 MAD、绝对残差
4. 联合判决：方向相反 && 相对MAD超限 && 绝对残差超限 && 同向占比<阈值 → 判为空间异常
5. 异常时用剔除自身后的邻居中位数替换；否则透传 L5 结果
6. 设备数不足 3 时 fallback 到 DefaultSpatialCheckService（中位数方法，同样应用方向+同向占比判决）

**修复模式**：
- `CAUTIOUS`（默认）：满足严格条件才替换
- `NO_REPAIR`：L6 仅计算指标，直接透传 L5 结果，不修改任何数据

**配置参数**：
- pcaEnabled — 启用/禁用PCA空间校验
- pcaWindowSize — PCA窗口大小（默认20）
- pcaVarianceThreshold — 方差阈值（默认0.6）
- spatialRepairMode — 修复模式（CAUTIOUS / NO_REPAIR，默认 CAUTIOUS）
- spatialSameDirectionThreshold — 同向占比阈值（默认0.5，同向占比≥此值不替换）
- spatialAbsoluteResidualThreshold — 绝对残差阈值（默认0.03m）

---

### ✅ 平滑基线（LOESS）—— 不被温漂绑架

**实现方式**：基于 Hipparchus 的 LOESS 局部加权回归

**核心价值**：
- 更准确地拟合缓慢变化的基线
- 有效分离趋势项与噪声项
- 避免被温度漂移等缓慢变化误导

**技术特性**：
- 自适应带宽参数（默认0.3）
- 定期重算机制（默认每20历元）
- 结果缓存减少计算量

**配置参数**：
- loessSlowBaselineEnabled — 启用/禁用LOESS
- loessBandwidth — LOESS带宽参数（默认0.3）
- loessRecalculateInterval — 重算间隔（默认20历元）

---

### ✅ 分段修复能力（分段插值）—— 整段拉直，不留痕迹

**实现方式**：连续粗差跟踪 + 分段替换策略

**核心价值**：
- 单点粗差：窗口中位数替换
- 连续粗差（≥3个）：上一个有效值替换
- 无效数据段（≥10个）：整段标记，不强行插值

**处理逻辑**：
`
连续粗差计数 < 3  → 窗口中位数替换
3 ≤ 连续粗差计数 < 10 → 上一个有效值填充
连续粗差计数 ≥ 10 → 标记为无效数据段
`

**配置参数**：
- consecutiveOutlierThreshold — 连续粗差阈值（默认3）
- maxInterpolationLength — 最大插值长度（默认10）

---

### ✅ 异步复核机制（变点检测旁路）—— 实时漏了，30分钟追认

**实现方式**：Pettitt 检验异步扫描历史数据

**核心价值**：
- 异步执行，不影响实时清洗性能
- 定期扫描历史数据，检测结构突变点
- 支持告警和自动修正两种模式

**技术特性**：
- 非参数检验，无需假设数据分布
- 周期性扫描（默认30分钟）
- 可配置最小检测点数和位移变化量

**配置参数**：
- changePointDetectionEnabled — 启用/禁用变点检测
- changePointScanIntervalMinutes — 扫描间隔（默认30分钟）
- changePointScanWindowSize — 扫描窗口大小（默认60历元）
- changePointMinShift — 最小位移变化量（默认0.03m）
- changePointAutoApply — 是否自动应用修正（默认false）
- changePointAlert — 是否触发告警（默认true）

---

### ✅ L1 FIX可信性验证 —— 识别false fix，拒绝不可信FIX解

**实现方式**：在L1质量门禁中，对FIX解附加位移显著性检查

**核心价值**：
- 检测FIX解位移远超自身声称精度的异常情况（false fix）
- 防止模糊度错误固定的FIX解污染后续清洗流程
- 使用初始基线（L5前10个FIX解均值）作为参考，而非lastValid，避免漂移累积

**检测原理**：
```
仅当 FIX解 + initBaseline已初始化 时执行：
  deltaN = N - baselineN
  σ_combined = sqrt(σ_current² + σ_baseline²)
  如果 |deltaN| > k * σ_combined → FIX不可信
  k 默认5.0（3σ太紧，false fix位移通常>>10mm）
  任一分量不可信 → 拒绝，用lastValid替换
  LayerResult记录原因："FIX false fix suspected: |dN|=XX > k*σ=YY"
```

**配置参数**：
- fixCredibilityCheck — 启用/禁用FIX可信性验证（默认true）
- fixCredibilityK — 倍数阈值k（默认5.0）
- fixCredibilityMinSigma — σ最小值（默认0.001m，防止σ极小时误判）

**初始基线建立**：DeviceState.accumulateInitialBaseline() 收集前10个FIX解，计算均值和标准差作为初始基线。初始基线标准差参与σ_combined计算，确保基线不确定性被纳入考量。

---

### ✅ L1 位移阈值门控 —— FIX/FLOAT 解位移超限直接拒绝

**实现方式**：在 L1 质量门禁中新增位移绝对值检查，FIX 和 FLOAT 解分别配置阈值

**核心价值**：
- FIX 解位移任一分量（N/E/U）绝对值超过 `maxDisplacementFix`（默认3.0m）直接拒绝
- FLOAT 解位移任一分量绝对值超过 `maxDisplacementFloat`（默认1.0m）直接拒绝
- 阈值设为0时禁用该检查
- 需要缓存中有 lastValid 值才启用（`state.isLastValidInitialized()`）

**判定逻辑**：
```
FLOAT解：|N| > 1.0 或 |E| > 1.0 或 |U| > 1.0 → 拒绝，用 lastValid 替换
FIX解：  |N| > 3.0 或 |E| > 3.0 或 |U| > 3.0 → 拒绝，用 lastValid 替换
```

**配置参数**：
- maxDisplacementFloat — FLOAT解最大位移阈值（默认1.0m）
- maxDisplacementFix — FIX解最大位移阈值（默认3.0m）

---

## 增强效果对比

| 能力 | 增强前 | 增强后 |
|------|--------|--------|
| 位移门控 | 无 | L1 FIX/FLOAT位移阈值门控 |
| FIX可信性 | 无 | L1 FIX解false fix检测（位移显著性验证） |
| 噪声处理 | 仅统计滤波 | 小波时频分析 |
| 漂移检测 | 无 | CUSUM累积和 |
| 空间校验 | 中位数方法 | PCA主成分分析 |
| 基线拟合 | 中位数 | LOESS回归 |
| 粗差修复 | 单点替换 | 分段智能修复 |
| 事后复核 | 无 | L8 异步变点检测 |
## 核心组件

### 清洗引擎接口

#### DisplacementCalculator — 主接口

| 方法 | 说明 |
|------|------|
| cleanSingle(DisplacementResult) | 单条清洗（无历史，L1→L3→L4→L5） |
| cleanWithHistory(DisplacementResult) | 带历史窗口的清洗（完整七层） |
| spatialCheck(List<SpatialGroupInput>) | 空间一致性校验（L6） |
| shutdown() | 关闭引擎，释放资源 |

#### DefaultDisplacementCalculator — 默认实现

串联全流程，初始化所有组件，管理生命周期。

### 清洗层组件

#### DisplacementCleaner — 七层递进过滤清洗器

| 方法 | 说明 |
|------|------|
| cleanWithHistory(result, state) | 完整七层清洗流程入口 |
| cleanSingle(result) | 无历史清洗流程 |
| layer1QualityGate(result) | L1质量门禁 |
| layer2JumpDetection(result, state) | L2跳变检测 + CUSUM漂移检测 |
| layer3OutlierDetection(result, state) | L3双模粗差检测 |
| layer4AnomalyReplacement(result, state, cleanResult) | L4分段替换 |
| layer5SlowBaseline(result, state) | L5 LOESS慢基线 |

#### WaveletDenoiser — L0小波去噪器

基于 Smile 库实现 Daubechies 4 小波软阈值去噪

| 方法 | 说明 |
|------|------|
| pushToBuffer(state, north, east, up) | 将数据推入环形缓冲区 |
| denoise(state) | 执行小波去噪 |

**技术参数**：
- 小波类型：Daubechies 4
- 阈值策略：软阈值
- 窗口大小：可配置（默认32）

#### CusumDetector — L2 CUSUM漂移检测器

基于 MAD 的累积和控制图

| 方法 | 说明 |
|------|------|
| detect(state, currentN, currentE, currentU, windowN, windowE, windowU) | 检测漂移 |

**检测原理**：
`
CUSUM+ = max(0, CUSUM+_prev + (x - median) - K×MAD)
CUSUM- = max(0, CUSUM-_prev - (x - median) - K×MAD)
触发条件：CUSUM+ > H×MAD 或 CUSUM- > H×MAD
`

#### PcaSpatialCheckService — L6 PCA空间校验服务

基于 Hipparchus PCA 计算公共模式残差，双阈值联合判决 + 邻居中位数替换

| 方法 | 说明 |
|------|------|
| spatialCheck(List<SpatialGroupInput>) | PCA 残差计算 + 方向/同向占比/双阈值联合判决 + 邻居中位数替换 |

**判决逻辑**：`方向相反 && 绝对残差超限 && 相对MAD超限 && 同向占比<阈值` 才判为空间异常

**修复模式**：`CAUTIOUS`（谨慎替换）/ `NO_REPAIR`（仅计算指标，透传L5）

#### DefaultSpatialCheckService — 空间校验默认实现（fallback）

基于中位数的空间一致性校验，设备数<3 时使用，同样应用方向相反 + 同向占比判决

### L7 综合仲裁组件（原有）

#### Layer7Arbitrator — 第七层综合仲裁器

| 方法 | 说明 |
|------|------|
| arbitrate(result, cleanResult, timeSeriesResidual, spatialResidual, solutionQuality, stepFlag, stationId) | 执行综合仲裁 |
| computeWcs(timeSeriesResidual, spatialResidual, solutionQuality, stepFlag) | 计算加权综合异常分（WCS） |
| isAvailable() | 服务是否可用 |
| shutdown() | 关闭仲裁器 |

#### Layer7ArbitrationService — 第七层仲裁接口

### L8 事后检验组件（旁支）

#### ChangePointScanner — 异步变点检测器

| 方法 | 说明 |
|------|------|
| start() | 启动异步扫描线程 |
| stop() | 停止扫描线程 |
| pettittTest(data) | 执行 Pettitt 检验 |

**技术特性**：
- 异步执行，不阻塞实时清洗
- 周期性扫描（默认30分钟）
- 支持自动应用修正或仅告警

### 配置接口

#### CleanConfig — 清洗配置（L0-L6）
#### Layer7Config — L7/L7s/L8配置
#### CacheConfig — 缓存配置
#### GnssConfig — GNSS解算配置

### 模型类

| 类名 | 说明 |
|------|------|
| DisplacementResult | 位移结果（含去噪后字段） |
| CleanResult | 清洗结果（含漂移怀疑、置信度、layerResults清洗轨迹、replacementSummary） |
| LayerResult | 单层清洗结果（层级、通过状态、替换值、替换方式、原因） |
| DeviceState | 设备状态（含小波缓冲、CUSUM累加器等） |
| SpatialGroupInput | 空间校验组输入 |
| SpatialCheckResult | 空间校验结果（含toLayerResult方法） |
| Layer7ArbitrationResult | 第七层仲裁结果（含toLayerResult方法） |
| ShadowFeatureVector | 影子评测10维特征向量 |
| ShadowEvaluationResult | 影子评测推理结果（含DeformType、RiskLevel、候选修正值） |
| DataCorrectionRecord | L8事后修正记录 |
| DataCorrectionItem | 逐历元修正值 |

### 持久化接口

#### PersistenceCallback — 持久化回调接口
#### HistoryDataProvider — 历史数据接口（主链路写，L8读）
#### CorrectionCallback — L8事后修正回调接口

## 配置参数

### CleanConfig — 清洗配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| algorithm | HAMPEL | 检测算法 |
| windowSize | 20 | 滑动窗口大小 |
| outlierThreshold | 3.0 | 异常值检测阈值 |
| // L0 小波去噪 | | |
| waveletEnabled | true | L0小波去噪开关 |
| waveletWindowSize | 32 | 小波窗口大小 |
| waveletThresholdScale | 0.7 | 软阈值缩放系数 |
| waveletResidualEnabled | true | 小波残差检测开关 |
| // L2 CUSUM | | |
| cusumEnabled | true | CUSUM漂移检测开关 |
| cusumK | 0.5 | CUSUM灵敏度系数 |
| cusumH | 5.0 | CUSUM报警阈值 |
| // L4 分段替换 | | |
| consecutiveOutlierThreshold | 3 | 连续粗差阈值 |
| maxInterpolationLength | 10 | 最大插值长度 |
| // L5 LOESS | | |
| loessSlowBaselineEnabled | true | LOESS慢基线开关 |
| loessBandwidth | 0.3 | LOESS带宽参数 |
| loessRecalculateInterval | 20 | LOESS重算间隔 |
| // L6 PCA | | |
| pcaEnabled | true | PCA空间校验开关 |
| pcaWindowSize | 20 | PCA窗口大小 |
| pcaVarianceThreshold | 0.6 | PCA方差阈值 |
| spatialRepairMode | CAUTIOUS | L6修复模式（CAUTIOUS/NO_REPAIR） |
| spatialSameDirectionThreshold | 0.5 | 同向占比阈值（≥此值不替换） |
| spatialAbsoluteResidualThreshold | 0.03 | 绝对残差阈值（m） |
| // 质量门控 | | |
| enableRatioCheck | true | Ratio检查开关 |
| ratioThreshold | 3.0 | Ratio阈值 |
| minRatio | 3.0 | 最小Ratio（FIX解） |
| minRatioFloat | 1.5 | 最小Ratio（FLOAT解，低于此值视为模糊度未收敛） |
| enableRmsCheck | true | RMS检查开关 |
| rmsThreshold | 0.05 | RMS阈值（m） |
| maxRms3d | 0.08 | 三维RMS阈值（m） |
| useRms3d | true | 是否使用三维RMS替代单分量RMS |
| enablePdopCheck | true | PDOP检查开关 |
| pdopThreshold | 6.0 | PDOP阈值 |
| minSatellites | 5 | 最小卫星数 |
| maxDisplacementFloat | 1.0 | FLOAT解最大位移阈值（m），N/E/U任一分量超限直接拒绝 |
| maxDisplacementFix | 3.0 | FIX解最大位移阈值（m），N/E/U任一分量超限直接拒绝 |
| fixCredibilityCheck | true | FIX解可信性验证开关（位移显著性判断） |
| fixCredibilityK | 5.0 | FIX可信性验证倍数阈值k，|delta| > k*σ_combined 判定为false fix |
| fixCredibilityMinSigma | 0.001 | FIX可信性验证σ最小值（m），防止σ极小时误判 |
| enableSpatialCheck | true | 空间校验开关 |

### Layer7Config — L7/L7s/L8配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| enabled | true | L7综合仲裁开关 |
| enableShadowBranch | false | L7s影子评测开关 |
| shadowConfidenceThreshold | 0.7 | 影子评测置信度阈值 |
| shadowMaxContinuousErr | 20 | 超长异常区间不生成修正值（历元数） |
| // L8 事后检验 | | |
| changePointDetectionEnabled | false | L8变点检测开关 |
| changePointScanIntervalMinutes | 30 | 扫描间隔（分钟） |
| changePointScanWindowSize | 60 | 扫描窗口大小 |
| changePointMinPoints | 30 | 最小检测点数 |
| changePointMinShift | 0.03 | 最小位移变化量（m） |
| changePointScanLookbackMinutes | 120 | 变点检测数据查询回溯时长（分钟） |
| changePointAutoApply | false | 是否自动应用修正 |
| changePointAlert | true | 是否触发告警 |

## 依赖说明

| 依赖 | 版本 | 用途 |
|------|------|------|
| hipparchus-core | 3.0 | LOESS插值、PCA分析 |
| hipparchus-stat | 3.0 | 统计计算 |
| smile-core | 4.2.0 | 小波变换、软阈值去噪 |
| slf4j-api | 2.0.9 | 日志框架 |

## 使用方法

### 1. 初始化清洗引擎

`java
// 创建配置
CleanConfig cleanConfig = new CleanConfig();
Layer7Config layer7Config = new Layer7Config();

// 可选：启用L8事后检验（异步变点检测）
layer7Config.changePointDetectionEnabled = true;
layer7Config.changePointScanIntervalMinutes = 30;

// 创建缓存和持久化回调
DeviceStateCache cache = new DeviceStateCache(cacheConfig, persistenceConfig, persistenceCallback);
HistoryDataProvider historyProvider = new H2HistoryDataProvider();

// 创建清洗引擎
DisplacementCalculator calculator = new DefaultDisplacementCalculator(
    cleanConfig, 
    layer7Config, 
    cache, 
    historyProvider
);
`

### 2. 清洗单条数据（无历史）

`java
// 创建位移结果
DisplacementResult result = new DisplacementResult();
result.setdNorth(0.01);
result.setdEast(0.005);
result.setdUp(0.003);
result.setSolutionStatus(SolutionStatus.FIX);
result.setRatio(5.0);
result.setRms(0.02);
result.setPdop(2.0);
result.setSatelliteCount(10);

// 执行清洗
CleanResult cleanResult = calculator.cleanSingle(result);

// 检查结果
if (cleanResult.isPassed()) {
    System.out.println("清洗通过");
} else {
    System.out.println("清洗失败: " + cleanResult.getReason());
}
`

### 3. 清洗带历史的数据

`java
// 创建位移结果（同上）
DisplacementResult result = new DisplacementResult();
// ... 设置字段

// 执行带历史的清洗
CleanResult cleanResult = calculator.cleanWithHistory(result);

// 获取详细结果
boolean driftSuspicion = cleanResult.isDriftSuspicion();      // L2漂移检测
String confidence = cleanResult.getOutlierConfidence();       // L3置信度
boolean segmented = cleanResult.isSegmentedReplacement();     // L4分段替换
`

### 4. 空间校验

`java
List<SpatialGroupInput> groupInputs = new ArrayList<>();
// 添加空间校验组输入...

List<SpatialCheckResult> results = calculator.spatialCheck(groupInputs);
`

### 5. 关闭引擎

`java
calculator.shutdown();
`

### 6. L8 事后检验配置

`java
Layer7Config layer7Config = new Layer7Config();
layer7Config.changePointDetectionEnabled = true;        // 启用变点检测
layer7Config.changePointScanIntervalMinutes = 30;       // 每30分钟扫描一次
layer7Config.changePointScanWindowSize = 60;           // 每次扫描60个历元
layer7Config.changePointMinShift = 0.03;               // 最小变化量3cm
layer7Config.changePointAutoApply = false;             // 不自动应用修正
layer7Config.changePointAlert = true;                  // 触发告警
`

## 项目结构

`
src/main/java/org/gnss/
├── DisplacementCalculator.java          # 清洗引擎接口
├── DefaultDisplacementCalculator.java   # 默认实现
├── cache/
│   └── DeviceStateCache.java            # 设备状态缓存
├── cleaning/
│   ├── DisplacementCleaner.java         # L1~L5七层清洗器
│   ├── WaveletDenoiser.java             # L0小波去噪器
│   ├── CusumDetector.java               # L2 CUSUM检测器
│   ├── PcaSpatialCheckService.java      # L6 PCA空间校验
│   ├── DefaultSpatialCheckService.java  # L6默认实现（fallback）
│   ├── Layer7Arbitrator.java            # L7综合仲裁器
│   ├── Layer7ArbitrationService.java    # L7仲裁接口
│   ├── ShadowEvaluationMode.java        # L7s影子评测模式
│   ├── ChangePointScanner.java          # L8事后检验（异步变点检测）
│   └── CorrectionCallback.java          # L8事后修正回调接口
├── shadow/
│   ├── ShadowEvaluationService.java     # L7s影子评测服务接口
│   ├── ShadowEvaluationClient.java      # L7s影子评测客户端
│   └── HttpShadowEvaluationService.java # L7s HTTP实现（连接Python AI服务）
├── config/
│   ├── CleanConfig.java                 # 清洗配置（L0~L6）
│   ├── Layer7Config.java                # L7/L7s/L8配置
│   ├── ShadowEvaluationConfig.java      # L7s影子评测配置
│   ├── SpatialRepairMode.java           # L6修复模式枚举
│   └── ...                              # 其他配置
├── model/
│   ├── DisplacementResult.java          # 位移结果
│   ├── CleanResult.java                 # 清洗结果（含layerResults轨迹）
│   ├── LayerResult.java                 # 单层清洗结果
│   ├── DeviceState.java                 # 设备状态
│   ├── ShadowFeatureVector.java         # L7s 10维特征向量
│   ├── ShadowEvaluationResult.java      # L7s推理结果
│   ├── DataCorrectionRecord.java        # L8修正记录
│   ├── DataCorrectionItem.java          # L8逐历元修正值
│   └── ...                              # 其他模型
└── persistence/
    ├── PersistenceCallback.java         # 持久化回调
    └── HistoryDataProvider.java         # 历史数据接口（主链路写，L8读）

src/test/java/org/gnss/
├── DisplacementCalculatorTest.java      # 单元测试
├── CleaningPipelineTest.java            # 流程测试
└── BugFixTest.java                      # 修复验证
`

## 测试说明

### 测试覆盖

| 测试类 | 覆盖内容 |
|--------|----------|
| DisplacementCalculatorTest | L1-L6各层功能、多设备隔离、持久化 |
| CleaningPipelineTest | 完整流程、Hampel算法、空间校验 |
| BugFixTest | 修复验证 |

### 运行测试

`ash
# 运行全部测试
mvn test

# 运行特定测试
mvn test -Dtest=CleaningPipelineTest#testHampelAlgorithmPipeline
`

## 许可证

MIT License
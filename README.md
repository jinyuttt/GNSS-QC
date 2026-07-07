# GNSS-QC - GNSS变形监测数据质量控制引擎

## 概述

GNSS-QC 是一个用于 GNSS 变形监测数据质量控制的七层递进式清洗引擎，旨在提高数据可靠性和准确性。该引擎采用分层递进架构，从基础质量控制到高级异常检测，逐步提升数据质量。

## 架构概览

七层递进式质量控制架构：

| 层级 | 名称 | 功能 | 状态 |
|------|------|------|------|
| **L0** | 小波去噪 | 使用 Daubechies 4 小波 + 软阈值去噪预处理 | 新增 |
| **L1** | 质量门禁 | 基于解类型、Ratio、RMS、PDOP、卫星数的质量筛选 | 保持 |
| **L2** | 跳变检测 | Hampel 滤波器检测突变 + CUSUM 累积和检测缓慢漂移 | 增强 |
| **L3** | 统计粗差 | Hampel/IQR/3σ 基础检测 + 小波残差双模检测 | 增强 |
| **L4** | 值替换 | 单点中位数替换 + 连续粗差分段替换 + 无效数据段标记 | 升级 |
| **L5** | 基线记忆 | LOESS 局部加权回归计算慢基线（替换原中位数方法） | 替换 |
| **L6** | 空间校验 | PCA 主成分分析提取公共模式误差（替换原中位数方法） | 替换 |
| **L7** | 异步变点检测 | Pettitt 检验异步扫描历史数据，检测结构突变点 | 新增 |

### 分层设计原则

1. **递进式过滤**：每一层基于前一层的输出进行处理，逐步提升数据质量
2. **可配置性**：每层功能均可独立启用/禁用，参数可配置
3. **状态管理**：通过 DeviceState 维护设备级别的历史状态
4. **异步处理**：L7 采用异步设计，不影响实时清洗流程

## 核心组件

### DisplacementCalculator — 清洗引擎接口

| 方法 | 说明 |
|------|------|
| cleanSingle(DisplacementResult) | 单条清洗（无历史，L1→L3→L4→L5） |
| cleanWithHistory(DisplacementResult) | 带历史窗口的清洗（完整七层） |
| spatialCheck(List<SpatialGroupInput>) | 空间一致性校验（L6） |
| shutdown() | 关闭引擎，释放资源 |

### DisplacementCleaner — 七层递进过滤清洗器

| 方法 | 说明 |
|------|------|
| cleanWithHistory(result, state) | 完整七层清洗流程入口 |
| cleanSingle(result) | 无历史清洗流程 |
| layer1QualityGate(result) | L1质量门禁：解类型、Ratio、RMS、PDOP、卫星数检查 |
| layer2JumpDetection(result, state) | L2跳变检测：Hampel突变检测 + CUSUM漂移检测 |
| layer3OutlierDetection(result, state) | L3双模粗差检测：Hampel/IQR/3σ + 小波残差 |
| layer4AnomalyReplacement(result, state, cleanResult) | L4分段替换：单点/连续/无效段处理 |
| layer5SlowBaseline(result, state) | L5 LOESS慢基线计算 |

### WaveletDenoiser — L0小波去噪器

基于 Smile 库实现 Daubechies 4 小波软阈值去噪

| 方法 | 说明 |
|------|------|
| pushToBuffer(state, north, east, up) | 将数据推入环形缓冲区 |
| denoise(state) | 执行小波去噪，返回 DenoisedResult |

**技术参数**：
- 小波类型：Daubechies 4
- 阈值策略：软阈值
- 窗口大小：可配置（默认32）

### CusumDetector — L2 CUSUM漂移检测器

基于 MAD（中位数绝对偏差）的累积和控制图，检测三维分量的缓慢漂移

| 方法 | 说明 |
|------|------|
| detect(state, currentN, currentE, currentU, windowN, windowE, windowU) | 检测并更新CUSUM累加器 |

**检测原理**：
`
CUSUM+ = max(0, CUSUM+_prev + (x - median) - K×MAD)
CUSUM- = max(0, CUSUM-_prev - (x - median) - K×MAD)
触发条件：CUSUM+ > H×MAD 或 CUSUM- > H×MAD
`

### PcaSpatialCheckService — L6 PCA空间校验服务

基于 Hipparchus PCA 提取空间公共模式误差，替换原中位数方法

| 方法 | 说明 |
|------|------|
| spatialCheck(List<SpatialGroupInput>) | PCA公共模式误差提取与修正 |

**处理流程**：
1. 收集同组所有测站的位移数据
2. 使用 PCA 分解提取主成分（公共模式）
3. 从各测站数据中减去公共模式分量
4. 当卫星数不足时，fallback 到原中位数方法

### ChangePointScanner — L7异步变点检测器

基于 Pettitt 检验的异步变点扫描服务

| 方法 | 说明 |
|------|------|
| start() | 启动异步扫描线程 |
| stop() | 停止扫描线程 |
| pettittTest(data) | 执行 Pettitt 检验检测变点 |

**技术特性**：
- 异步执行，不阻塞实时清洗
- 周期性扫描（默认30分钟）
- 支持自动应用修正或仅告警

### Layer7Arbitrator — 第七层综合仲裁器

| 方法 | 说明 |
|------|------|
| arbitrate(result, cleanResult, timeSeriesResidual, spatialResidual, solutionQuality, stepFlag, stationId) | 执行第七层综合仲裁 |
| computeWcs(timeSeriesResidual, spatialResidual, solutionQuality, stepFlag) | 计算加权综合异常分（WCS） |
| isAvailable() | 服务是否可用 |
| shutdown() | 关闭仲裁器 |

### SpatialCheckService — 第六层空间校验接口

| 方法 | 说明 |
|------|------|
| spatialCheck(List<SpatialGroupInput>) | 空间一致性校验 |

### DefaultSpatialCheckService — 空间校验默认实现

基于中位数的空间一致性校验，作为 PCA 方法的 fallback

## 配置参数

### CleanConfig — 清洗配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| algorithm | HAMPEL | 检测算法（HAMPEL/IQR/SIGMA） |
| windowSize | 20 | 滑动窗口大小 |
| outlierThreshold | 3.0 | 异常值检测阈值（×MAD或σ） |
| // L0 小波去噪 | | |
| waveletEnabled | true | L0小波去噪开关 |
| waveletWindowSize | 32 | 小波窗口大小 |
| waveletThresholdScale | 0.7 | 软阈值缩放系数 |
| waveletResidualEnabled | true | 小波残差检测开关 |
| // L2 CUSUM | | |
| cusumEnabled | true | L2 CUSUM漂移检测开关 |
| cusumK | 0.5 | CUSUM灵敏度系数（×MAD） |
| cusumH | 5.0 | CUSUM报警阈值（×MAD） |
| // L4 分段替换 | | |
| consecutiveOutlierThreshold | 3 | 连续粗差阈值 |
| maxInterpolationLength | 10 | 最大插值长度（超过标记为无效） |
| // L5 LOESS | | |
| loessSlowBaselineEnabled | true | L5 LOESS慢基线开关 |
| loessBandwidth | 0.3 | LOESS带宽参数 |
| loessRecalculateInterval | 20 | LOESS重算间隔（历元数） |
| // L6 PCA | | |
| pcaEnabled | true | L6 PCA空间校验开关 |
| pcaWindowSize | 20 | PCA窗口大小 |
| pcaVarianceThreshold | 0.6 | PCA方差阈值 |
| // 质量门控 | | |
| enableRatioCheck | true | Ratio检查开关 |
| ratioThreshold | 3.0 | Ratio阈值 |
| enableRmsCheck | true | RMS检查开关 |
| rmsThreshold | 0.05 | RMS阈值（m） |
| enablePdopCheck | true | PDOP检查开关 |
| pdopThreshold | 6.0 | PDOP阈值 |
| enableSatelliteCountCheck | true | 卫星数检查开关 |
| minSatellites | 5 | 最小卫星数 |
| enableSpatialCheck | true | 空间校验开关 |

### Layer7Config — L7变点检测配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| changePointDetectionEnabled | false | L7变点检测开关 |
| changePointScanIntervalMinutes | 30 | 扫描间隔（分钟） |
| changePointScanWindowSize | 60 | 扫描窗口大小（历元） |
| changePointMinPoints | 30 | 最小检测点数 |
| changePointMinShift | 0.03 | 最小位移变化量（m） |
| changePointAutoApply | false | 是否自动应用修正 |
| changePointAlert | true | 是否触发告警 |

## 依赖说明

| 依赖 | 版本 | 用途 |
|------|------|------|
| hipparchus-core | 3.0 | LOESS插值、PCA分析 |
| hipparchus-stat | 3.0 | 统计计算、MAD计算 |
| smile-core | 4.2.0 | 小波变换、软阈值去噪 |
| slf4j-api | 2.0.9 | 日志框架 |

## 项目结构

`
src/main/java/org/gnss/
├── DisplacementCalculator.java          # 清洗引擎接口
├── DefaultDisplacementCalculator.java   # 默认实现（串联全流程）
├── cache/
│   └── DeviceStateCache.java            # 设备状态LRU缓存
├── cleaning/
│   ├── DisplacementCleaner.java         # 七层递进过滤清洗器
│   ├── WaveletDenoiser.java             # L0小波去噪器
│   ├── CusumDetector.java               # L2 CUSUM漂移检测器
│   ├── PcaSpatialCheckService.java      # L6 PCA空间校验服务
│   ├── ChangePointScanner.java          # L7异步变点检测器
│   ├── SpatialCheckService.java         # 空间校验接口
│   ├── DefaultSpatialCheckService.java  # 空间校验默认实现
│   ├── Layer7Arbitrator.java            # 第七层综合仲裁器
│   └── Layer7ArbitrationService.java    # 第七层仲裁接口
├── config/
│   ├── Algorithm.java                   # 检测算法枚举（HAMPEL/IQR/SIGMA）
│   ├── CleanConfig.java                 # 清洗配置（含L0-L6增强参数）
│   ├── Layer7Config.java                # L7变点检测配置
│   ├── CacheConfig.java                 # 缓存配置
│   ├── DiagnosisConfig.java             # 诊断配置
│   ├── PersistenceConfig.java           # 持久化配置
│   ├── GnssConfig.java                  # GNSS解算配置
│   └── EngineGlobalConfig.java          # 引擎全局配置
├── diagnosis/
│   └── DeviceDiagnostician.java         # 设备诊断器
├── model/
│   ├── DisplacementResult.java          # 位移结果实体（含去噪后字段）
│   ├── CleanResult.java                 # 清洗结果（含漂移怀疑、置信度）
│   ├── DeviceState.java                 # 设备状态（含小波缓冲、CUSUM累加器等）
│   ├── DeviceStateSnapshot.java         # 设备状态快照（持久化用）
│   ├── SpatialGroupInput.java           # 空间校验组输入
│   ├── SpatialCheckResult.java          # 空间校验结果
│   └── Layer7ArbitrationResult.java     # 第七层仲裁结果
└── persistence/
    ├── PersistenceCallback.java         # 持久化回调接口
    └── HistoryDataProvider.java         # 历史数据接口（含L7查询方法）

src/test/java/org/gnss/
├── DisplacementCalculatorTest.java      # 单元测试
├── CleaningPipelineTest.java            # 流程测试
├── BugFixTest.java                      # 修复验证测试
├── H2PersistenceCallback.java           # H2持久化实现
└── H2HistoryDataProvider.java           # H2历史数据实现
`

## 测试说明

### 测试架构

`
DisplacementCalculatorTest
├── Layer1 质量门禁测试（INVALID/SINGLE/FIX/FLOAT拒绝与通过）
├── Layer2 跳变检测测试（突变检测、同向趋势快速通道）
├── Layer3 统计粗差测试（Hampel/IQR/3σ检测）
├── Layer4 值替换测试（粗差替换为中位数）
├── Layer5 基线记忆测试（阶跃检测与确认）
├── 多设备隔离测试
├── H2持久化回调测试（结果存储、状态恢复、异常事件、修正记录）
└── 统计与查询测试

CleaningPipelineTest
├── Pipeline 1: cleanSingle — 无历史 (L1→L3→L4→L5)
├── Pipeline 2: cleanWithHistory — 带历史 (L1→L2→L3→L4→L5→L6)
├── Pipeline 3: Layer7 综合仲裁独立测试
├── Pipeline 4: Hampel算法 — 去趋势残差检测
├── Pipeline 5: 影子评测客户端 — 独立解耦测试
└── Pipeline 6: Layer7开关控制

BugFixTest
├── BugFix1-7: 特征计算、质量分、默认值、RRCF阈值归一化等修复验证
├── BugFix8: 缓存超容量淘汰策略测试
├── BugFix9-11: FLOAT解3D-RMS质量门控、Ratio检查、RMS回退测试
└── Layer7Arbitrator shutdown超时测试
`

### 运行测试

`ash
# 运行全部测试
mvn test

# 运行特定测试
mvn test -Dtest=CleaningPipelineTest#testHampelAlgorithmPipeline
`

## 数据流程

`
原始位移数据 → L0小波去噪 → L1质量门禁 → L2跳变检测 → L3粗差检测
    ↓
L4值替换 → L5基线记忆 → L6空间校验 → L7异步变点检测（旁路）
    ↓
清洗结果输出
`

### 各层输出说明

| 层级 | 输出 | 说明 |
|------|------|------|
| L0 | denoisedNorth/East/Up | 去噪后位移值 |
| L1 | passed/failed | 质量门控结果 |
| L2 | driftSuspicion | 是否存在漂移嫌疑 |
| L3 | outlierConfidence | 粗差置信度（NORMAL/HIGH） |
| L4 | segmentedReplacement/invalidDataSegment | 替换类型标记 |
| L5 | slowBaseline | LOESS拟合的慢基线值 |
| L6 | spatialCorrected | 空间校正后的值 |
| L7 | changePointDetected | 变点检测结果（异步） |

## 许可证

MIT License
# GNSS-QC - GNSS变形监测数据质量控制引擎

## 概述

GNSS-QC 是一个用于 GNSS 变形监测数据质量控制的七层递进式清洗引擎，旨在提高数据可靠性和准确性。该引擎采用分层递进架构，从基础质量控制到高级异常检测，逐步提升数据质量。

## 架构概览

### 七层递进式质量控制架构

| 层级 | 名称 | 功能 | 状态 |
|------|------|------|------|
| **L0** | 小波去噪 | 使用 Daubechies 4 小波 + 软阈值去噪预处理 | 新增 |
| **L1** | 质量门禁 | 基于解类型、Ratio、RMS、PDOP、卫星数的质量筛选 | 保持 |
| **L2** | 跳变检测 | Hampel 滤波器检测突变 + CUSUM 累积和检测缓慢漂移 | 增强 |
| **L3** | 统计粗差 | Hampel/IQR/3σ 基础检测 + 小波残差双模检测 | 增强 |
| **L4** | 值替换 | 单点中位数替换 + 连续粗差分段替换 + 无效数据段标记 | 升级 |
| **L5** | 基线记忆 | LOESS 局部加权回归计算慢基线（替换原中位数方法） | 替换 |
| **L6** | 空间校验 | PCA 主成分分析提取公共模式误差（替换原中位数方法） | 替换 |
| **L7** | 综合仲裁 | 加权综合异常分（WCS）判决 + 影子评测 | 保持 |
| **L7b** | 异步变点检测 | Pettitt 检验异步扫描历史数据，检测结构突变点 | 新增（旁支） |

### 分层设计原则

1. **递进式过滤**：每一层基于前一层的输出进行处理，逐步提升数据质量
2. **可配置性**：每层功能均可独立启用/禁用，参数可配置
3. **状态管理**：通过 DeviceState 维护设备级别的历史状态
4. **旁路设计**：L7b 异步变点检测不影响实时清洗流程

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
L7 综合仲裁（WCS + 影子评测）
    ↓
清洗结果输出

┌─────────────────────────────────────────────────────────────┐
│  L7b 异步变点检测（旁支）                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  定期扫描历史数据 → Pettitt检验 → 检测变点 → 告警/记录  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
`

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

基于 Hipparchus PCA 提取空间公共模式误差

| 方法 | 说明 |
|------|------|
| spatialCheck(List<SpatialGroupInput>) | PCA公共模式误差提取与修正 |

#### DefaultSpatialCheckService — 空间校验默认实现（fallback）

基于中位数的空间一致性校验

### L7 综合仲裁组件（原有）

#### Layer7Arbitrator — 第七层综合仲裁器

| 方法 | 说明 |
|------|------|
| arbitrate(result, cleanResult, timeSeriesResidual, spatialResidual, solutionQuality, stepFlag, stationId) | 执行综合仲裁 |
| computeWcs(timeSeriesResidual, spatialResidual, solutionQuality, stepFlag) | 计算加权综合异常分（WCS） |
| isAvailable() | 服务是否可用 |
| shutdown() | 关闭仲裁器 |

#### Layer7ArbitrationService — 第七层仲裁接口

### L7b 异步变点检测组件（新增旁支）

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
#### Layer7Config — L7/L7b配置
#### CacheConfig — 缓存配置
#### GnssConfig — GNSS解算配置

### 模型类

| 类名 | 说明 |
|------|------|
| DisplacementResult | 位移结果（含去噪后字段） |
| CleanResult | 清洗结果（含漂移怀疑、置信度） |
| DeviceState | 设备状态（含小波缓冲、CUSUM累加器等） |
| SpatialGroupInput | 空间校验组输入 |
| SpatialCheckResult | 空间校验结果 |
| Layer7ArbitrationResult | 第七层仲裁结果 |

### 持久化接口

#### PersistenceCallback — 持久化回调接口
#### HistoryDataProvider — 历史数据接口（含L7b查询方法）

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
| // 质量门控 | | |
| enableRatioCheck | true | Ratio检查开关 |
| ratioThreshold | 3.0 | Ratio阈值 |
| enableRmsCheck | true | RMS检查开关 |
| rmsThreshold | 0.05 | RMS阈值（m） |
| enablePdopCheck | true | PDOP检查开关 |
| pdopThreshold | 6.0 | PDOP阈值 |
| minSatellites | 5 | 最小卫星数 |
| enableSpatialCheck | true | 空间校验开关 |

### Layer7Config — L7配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| enabled | true | L7综合仲裁开关 |
| enableShadowBranch | false | 影子评测开关 |
| // L7b 异步变点检测 | | |
| changePointDetectionEnabled | false | L7b变点检测开关 |
| changePointScanIntervalMinutes | 30 | 扫描间隔（分钟） |
| changePointScanWindowSize | 60 | 扫描窗口大小 |
| changePointMinPoints | 30 | 最小检测点数 |
| changePointMinShift | 0.03 | 最小位移变化量（m） |
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

// 可选：启用L7b异步变点检测
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

### 6. L7b 异步变点检测配置

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
│   ├── DisplacementCleaner.java         # 七层清洗器
│   ├── WaveletDenoiser.java             # L0小波去噪器
│   ├── CusumDetector.java               # L2 CUSUM检测器
│   ├── PcaSpatialCheckService.java      # L6 PCA空间校验
│   ├── DefaultSpatialCheckService.java  # L6默认实现
│   ├── ChangePointScanner.java          # L7b异步变点检测
│   ├── Layer7Arbitrator.java            # L7综合仲裁器
│   └── Layer7ArbitrationService.java    # L7仲裁接口
├── config/
│   ├── CleanConfig.java                 # 清洗配置
│   ├── Layer7Config.java                # L7配置
│   └── ...                              # 其他配置
├── model/
│   ├── DisplacementResult.java          # 位移结果
│   ├── CleanResult.java                 # 清洗结果
│   ├── DeviceState.java                 # 设备状态
│   └── ...                              # 其他模型
└── persistence/
    ├── PersistenceCallback.java         # 持久化回调
    └── HistoryDataProvider.java         # 历史数据接口

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
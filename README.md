# GNSS-QC - GNSS变形监测数据质量控制引擎

## 概述

GNSS-QC 是一个用于 GNSS 变形监测数据质量控制的七层递进式清洗引擎，旨在提高数据可靠性和准确性。

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

## 核心组件

### DisplacementCalculator — 清洗引擎接口

| 方法 | 说明 |
|------|------|
| cleanSingle(DisplacementResult) | 单条清洗（无历史） |
| cleanWithHistory(DisplacementResult) | 带历史窗口的清洗 |
| spatialCheck(List<SpatialGroupInput>) | 空间一致性校验 |
| shutdown() | 关闭引擎 |

### DisplacementCleaner — 七层递进过滤清洗器

| 方法 | 说明 |
|------|------|
| cleanWithHistory(result, state) | 完整七层清洗流程 |
| cleanSingle(result) | 无历史清洗（L1→L3→L4→L5） |
| layer1QualityGate(result) | L1质量门禁 |
| layer2JumpDetection(result, state) | L2跳变检测 + CUSUM漂移检测 |
| layer3OutlierDetection(result, state) | L3双模粗差检测 |
| layer4AnomalyReplacement(result, state, cleanResult) | L4分段替换 |
| layer5SlowBaseline(result, state) | L5 LOESS慢基线 |

### WaveletDenoiser — L0小波去噪器

| 方法 | 说明 |
|------|------|
| pushToBuffer(state, north, east, up) | 推入环形缓冲区 |
| denoise(state) | 执行小波去噪，返回 DenoisedResult |

### CusumDetector — L2 CUSUM漂移检测器

| 方法 | 说明 |
|------|------|
| detect(state, currentN, currentE, currentU, windowN, windowE, windowU) | 检测三维分量的缓慢漂移 |

### PcaSpatialCheckService — L6 PCA空间校验服务

| 方法 | 说明 |
|------|------|
| spatialCheck(List<SpatialGroupInput>) | PCA公共模式误差提取 |

### ChangePointScanner — L7异步变点检测器

| 方法 | 说明 |
|------|------|
| start() | 启动异步扫描 |
| stop() | 停止扫描 |
| pettittTest(data) | Pettitt检验检测变点 |

## 配置参数

### CleanConfig — 清洗配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| waveletEnabled | true | L0小波去噪开关 |
| waveletWindowSize | 32 | 小波窗口大小 |
| waveletThresholdScale | 0.7 | 软阈值缩放系数 |
| cusumEnabled | true | L2 CUSUM漂移检测开关 |
| cusumK | 0.5 | CUSUM灵敏度系数（×MAD） |
| cusumH | 5.0 | CUSUM报警阈值（×MAD） |
| loessSlowBaselineEnabled | true | L5 LOESS慢基线开关 |
| loessBandwidth | 0.3 | LOESS带宽参数 |
| loessRecalculateInterval | 20 | LOESS重算间隔（历元数） |
| pcaEnabled | true | L6 PCA空间校验开关 |
| pcaWindowSize | 20 | PCA窗口大小 |
| pcaVarianceThreshold | 0.6 | PCA方差阈值 |
| consecutiveOutlierThreshold | 3 | L4连续粗差阈值 |
| maxInterpolationLength | 10 | L4最大插值长度 |

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
| hipparchus-core | 3.0 | LOESS插值、PCA分析、统计工具 |
| hipparchus-stat | 3.0 | 统计计算 |
| smile-core | 4.2.0 | 小波变换、软阈值去噪 |

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
│   ├── DefaultSpatialCheckService.java  # L6空间校验默认实现（fallback）
│   └── Layer7Arbitrator.java            # 第七层综合仲裁器
├── config/
│   ├── Algorithm.java                   # 检测算法枚举
│   ├── CleanConfig.java                 # 清洗配置（含L0-L6增强参数）
│   ├── Layer7Config.java                # L7变点检测配置
│   └── ...                              # 其他配置类
├── model/
│   ├── DisplacementResult.java          # 位移结果（含去噪后字段）
│   ├── CleanResult.java                 # 清洗结果（含漂移怀疑、置信度）
│   ├── DeviceState.java                 # 设备状态（含小波缓冲、CUSUM累加器等）
│   └── ...                              # 其他模型类
└── persistence/
    ├── PersistenceCallback.java         # 持久化回调接口
    └── HistoryDataProvider.java         # 历史数据接口（含L7查询方法）
`

## 测试说明

### 测试覆盖

| 测试类 | 测试内容 |
|--------|----------|
| DisplacementCalculatorTest | 各层功能测试、多设备隔离、H2持久化 |
| CleaningPipelineTest | 完整流程测试、Hampel算法、空间校验 |
| BugFixTest | 修复验证测试 |

### 运行测试

`ash
mvn test
`

## 许可证

MIT License
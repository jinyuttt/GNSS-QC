# GNSS-QC — GNSS位移数据七层递进式质量控制系统

## 项目概述

GNSS-QC 是一个面向 GNSS 形变监测场景的数据清洗引擎，对单历元位移结果（NEU）执行**七层递进式质量控制**，逐层过滤异常数据，在压制噪声的同时保护真实形变信号不被误删。

### 核心设计原则

- **递进过滤**：每一层独立判断，未通过则标记异常并阻止进入下一层
- **只看过去**：纯历史驱动，不依赖未来数据，适合实时流式处理
- **不漏形变**：趋势快速通道 + 趋势保护双重防线，确保真实形变无条件放行
- **只压噪声**：只替换孤立异常点，连续趋势数据原样保留
- **接口解耦**：存储通过 PersistenceCallback / HistoryDataProvider 接口完全解耦，引擎不内置任何存储实现
- **可选层级**：L6空间校验、L7综合仲裁、影子评测支路均为可选，按需启用

---

## 架构总览

`
GNSS RTK 原始 NEU 解算结果
         │
         ▼
 ┌─────────────────────────────┐
 │ 第1层：质量门禁              │  解算质量前置拦截
 ├─────────────────────────────┤
 │ 第2层：跳变检测              │  相邻历元突变识别 + 同向趋势放行
 ├─────────────────────────────┤
 │ 第3层：统计粗差检测          │  【升级】去趋势残差 + Hampel Identifier
 ├─────────────────────────────┤
 │ 第4层：异常值替换            │  仅第3层触发，粗差替换 + 时序修正标记
 ├─────────────────────────────┤
 │ 第5层：基线记忆与阶跃校准    │  快慢双基线 + 阶跃三阶段检测
 └─────────────────────────────┘
         │  单测点时序清洗完成
         ▼
 ┌─────────────────────────────┐
 │ 第6层：空间一致性批量校验    │  【可选】同组同历元空间校验
 └─────────────────────────────┘
         │  第6层启用 → 取第6层输出
         │  第6层关闭 → 直接取第5层输出
         ▼
 ┌─────────────────────────────┐
 │ 第7层：可信度评级与后置质检  │  【可选】加权综合评分 + 可选修正
 └─────────────────────────────┘
         │
         ├─────────────────────── 主链路输出 → 入库 / 业务计算
         │
         ▼ （旁路异步，可选）
 ┌─────────────────────────────┐
 │ 影子评测服务                  │  RRCF+LSTM双模型，仅评测不替换
 │ (ShadowEvaluationService)    │  候选修正值对比，不影响主链路
 └─────────────────────────────┘
         │
         ▼
         影子表存储 / 效果评估 / 离线分析
`

---

## 两个核心接口

引擎提供两个入口，对应不同的使用场景：

| 接口 | 覆盖层级 | 适用场景 | 历史上下文 |
|------|---------|---------|-----------|
| cleanSingle(result) | L1→L3→L4→L5 | 冷启动、无历史数据 | 临时DeviceState，不持久化 |
| cleanWithHistory(result, deviceId) | L1→L2→L3→L4→L5→L6[可选]→L7[可选] | 正常运行、有历史 | DeviceStateCache，持久化 |

### cleanSingle — 无历史清洗

`
输入 → L1(质量门禁) → L3(统计粗差) → L4(值替换) → L5(基线记忆) → 输出
              │              │              │              │
              │ 跳过L2       │ 空窗口       │ 无粗差时     │ 空基线时
              │ (无历史参考)  │ 直接放行     │ 跳过替换     │ 直接放行
`

- 使用临时 DeviceState，不写入缓存
- 每次调用完全独立，无状态累积
- 适合系统冷启动或单次验证

### cleanWithHistory — 带历史完整清洗

`
输入 → L1 → L2 → L3 → L4 → L5 → L6[可选] → L7[可选] → 输出
       │    │    │    │    │     │            │
       │    │    │    │    │     │            └ WCS评分+三项判决+趋势保护
       │    │    │    │    │     └ 空间离群→替换为组中位数
       │    │    │    │    └ 阶跃观察→确认/回正
       │    │    │    └ 粗差→替换为窗口中位数
       │    │    └ Hampel去趋势残差检测
       │    └ 跳变标记(不阻断) + 同向趋势快速通道
       └ 质量门禁
`

- 使用 DeviceStateCache 管理设备状态
- 支持多设备隔离（按deviceId区分）
- L2标记异常但不阻断，数据继续进入后续层
- L6需要至少 spatialMinNeighbors 个邻居设备才执行
- L7需要 layer7Config.enabled = true 且提供 HistoryDataProvider

---

## 七层详细说明

### Layer 1 — 质量门禁

对解算结果的基本质量指标进行检查，不合格直接拒绝：

| 解算状态 | 检查项 | 默认阈值 |
|---------|--------|---------|
| INVALID / SINGLE | 直接拒绝 | — |
| FLOAT | 卫星数 ≥ minSatellite | 6 |
| FLOAT | PDOP ≤ maxPdopFloat | 6.0 |
| FLOAT | RMS ≤ maxRmsFloat | 0.15m |
| FLOAT | RMS3D ≤ maxRms3dFloat | 0.25m（可选） |
| FLOAT | Ratio ≥ minRatioFloat | 1.0（可选） |
| FIX | 卫星数 ≥ minSatellite | 6 |
| FIX | PDOP ≤ maxPdop | 4.0 |
| FIX | RMS ≤ maxRms | 0.05m |
| FIX | Ratio ≥ minRatio | 3.0 |

**新增特性**：
- 支持三维RMS（RMS3D）质量门控，对FLOAT解更严格
- 支持FLOAT解Ratio检查，防止低质量浮动解通过
- RMS3D为0时自动回退到单分量RMS

### Layer 2 — 跳变检测

相邻历元突变识别，标记但不阻断：

- 水平跳变 > maxStepHorizontal（默认0.05m）→ 标记异常
- 高程跳变 > maxStepVertical（默认0.08m）→ 标记异常
- **同向趋势快速通道**：连续 trendQuickReleaseCount（默认3）个历元同向跳变，且时间窗口 ≤ trendQuickReleaseMaxSeconds（默认10秒）→ 无条件放行（保护真实形变）
- 输出中间变量：**跳变标记**（jumpFlag），供L7/影子评测使用

### Layer 3 — 统计粗差检测

滑动窗口统计检测，支持三种算法：

| 算法 | 说明 | 适用场景 |
|------|------|---------|
| HAMPEL | 去趋势残差 + Hampel Identifier | 默认推荐，对趋势数据友好 |
| IQR | 四分位距法 | 稳定数据场景 |
| SIGMA | 3σ法则 | 简单快速场景 |

**HAMPEL算法流程**：
1. 窗口内数据线性拟合去趋势
2. 计算残差的中位数和MAD
3. 偏离度 > hampelK × MAD → 标记粗差
4. 高程方向使用更大的阈值系数 hampelKVertical

### Layer 4 — 异常值替换

仅Layer3触发时执行：

- 粗差替换为窗口中位数
- 标记时序修正类型（TIME_SERIES_MEDIAN）
- 输出中间变量：**时序残差**（F6），供L7/影子评测使用

### Layer 5 — 基线记忆与阶跃校准

快慢双基线 + 阶跃三阶段检测：

- **快速基线**：最近 longTermWindowSize（默认300）条数据的中位数
- **慢速基线**：最近 slowBaselineSize（默认1440）条数据的中位数
- 快慢基线差异 > stepDeviationThreshold（默认0.03m）→ 进入阶跃观察期
- 阶跃观察期：连续 stepObservationCount（默认60）个历元稳定在候选位置 → 确认阶跃
- 确认后更新基线参考；超时未确认则回正到旧基线
- 输出中间变量：**阶跃标记**（stepFlag），供L7/影子评测使用

### Layer 6 — 空间一致性批量校验【可选】

利用多设备空间一致性，检测当前设备是否为空间离群点。**纯Java实现，无Python依赖**：

- 通过独立接口 SpatialCheckService 暴露，实现类 DefaultSpatialCheckService
- 输入：整个组内所有设备的位移数据 List<SpatialGroupInput>（组内设备一起传入）
- 计算组中位数作为空间参考
- 当前设备与组中位数偏差 > spatialOutlierThreshold（默认0.03m）→ 判定为空间离群
- 离群时替换为组中位数，清除异常标记
- 需要至少 spatialMinNeighbors（默认2）个邻居设备才执行
- **默认关闭**，需手动 enableSpatialCheck = true
- 输出中间变量：**空间残差**（F7）、**同向邻居占比**（F8），供L7/影子评测使用

### Layer 7 — 可信度评级与后置质检【可选】

六层清洗之后的最终仲裁层，大屏数据显示前的最后一道关卡。

**启用条件**：layer7Config.enabled = true 且提供 HistoryDataProvider 实现

**核心算法：WCS加权综合异常分**

`
WCS = 0.35 × sigmoid(|时序残差|)
    + 0.35 × sigmoid(|空间残差| / 空间归一化阈值)
    + 0.20 × (1 - 解算质量归一化值)
    + 0.10 × 阶跃标记
`

**判决流程**：

`
WCS ≤ 0.85? ──YES──→ 直接放行
      │
      NO（可疑区）
      │
      ▼
查询历史（带50ms超时熔断）
      │
      ├─ 查询失败/超时 → 跳过判决，直接放行
      ├─ 历史不足3条   → 跳过判决，直接放行
      │
      ▼
趋势保护检查：过去20条连续同向? ──YES──→ 无条件放行
      │
      NO
      │
      ▼
三项判决：
  ① 历史稳定性：窗口内 max-min < 2cm
  ② 历史无趋势：线性拟合斜率 < 0.001
  ③ 当前偏离度：偏差 > 3 × MAD
      │
      ├─ 三项全满足 → 替换为历史低分点中位数
      └─ 任一不满足 → 原样输出
`

**L7未启用时的行为**：
- 仍会计算WCS分数并回调 saveHistory 写入历史数据（积累历史供未来启用）
- 不执行仲裁判决，第6层输出直接作为最终结果

**大屏表现**：

| 场景 | 大屏行为 |
|------|---------|
| 正常输出（isLayer7Corrected=false） | 实线绘制，颜色饱满 |
| 替换输出（isLayer7Corrected=true） | 绘制替换值，点呈半透明/灰色，标记"噪声压制" |
| 历史趋势中放行 | 实线绘制，颜色略微变淡，提示"形变中" |
| 数据不足跳过 | 正常显示，无可信度标记 |

---

## 影子评测服务【可选独立服务】

### 系统定位

旁路影子试验系统，不接管主业务数据流，核心用于**异常识别、数据修复效果研究、模型迭代评估**。基于 **RRCF + LSTM+Attention 双模型融合**架构，服务于山地峡谷 GNSS 监测场景，重点区分**多径伪形变**与**真实滑坡形变**。

| 维度 | 说明 |
|------|------|
| 形态 | 独立部署的微服务，与主清洗服务解耦，可有可无 |
| 角色 | 异常识别 + 候选修正值生成（仅评测，不替换线上数据）+ 模型迭代评估 |
| 数据流 | 主链路输出数据同步推送至影子服务，推理结果写入独立影子表 |
| 触发方式 | 实时旁路推理 / 离线人工触发回溯 |
| 关关影响 | **零影响**，主链路完全不受影响 |

### 核心目标

1. 同步接收主链路 L1~L7 清洗后全量数据，完成异常判别
2. 对伪形变/噪声数据生成**候选修正值**，仅做对比评测，**不替换线上正式数据**
3. 依托线上数据持续做模型增量迭代，新版本自动热加载，全程无人值守
4. 全量数据、模型结果、修正样本落地存储，支撑量化评估与人工研判

### 双环路设计

**环路1：同步实时推理链路（SLA ≤50ms）**

`
Java主服务 → HTTP批量推送清洗后数据
    → 原始数据全量落库存档
    → 特征标准化 + LSTM时序窗口维护
    → RRCF、LSTM+Attention 并行推理
    → 双模型联合判定：
        ├─ 伪形变/单点跳变/短时噪声 → 生成候选修正值
        ├─ 真实滑坡形变 → 不生成修正值，仅标记告警
        └─ 结论冲突/置信度不足 → 标记待人工复核
    → 融合结果 + 候选修正值 + 全量标签统一落库
    → 同步返回结果至Java主服务
`

**环路2：定时增量学习 & 模型自动更新（Python侧实现）**

`
定时任务（每10小时） → 拉取最近数据
    → 样本过滤（剔除REJECTED、异常残差）
    → 特征标准化参数增量更新
    → RRCF树增量插入 + LSTM微调（冻结主干，仅调浅层）
    → 质量校验（前后对比、冲突率监控）
    → 通过 → 新版本快照保存 + 热加载触发
    → 失败 → 自动回滚到上一稳定版本
`

### Python服务架构

`
ai-service/
├── server.py                    # Flask主入口，服务初始化
├── config.yaml                  # 服务配置（端口、模型参数、训练调度）
├── requirements.txt             # Python依赖
├── api/
│   ├── __init__.py
│   └── routes.py                # HTTP API路由（/api/v1/infer, /shadow, /model/version等）
├── models/
│   ├── __init__.py
│   ├── rrcf.py                  # RRCF异常检测模型（Robust Random Cut Forest）
│   ├── lstm_attention.py        # LSTM+Attention分类模型（正常/伪形变/真实形变）
│   ├── features.py              # 特征标准化器（10维特征归一化）
│   └── inference.py             # 双模型融合推理引擎 + 候选修正值生成
├── storage/
│   ├── __init__.py
│   ├── base.py                  # 存储抽象基类
│   ├── sqlite_store.py          # SQLite实现（默认）
│   └── factory.py               # 存储工厂（支持自定义扩展）
├── training/
│   ├── __init__.py
│   ├── model_manager.py         # 模型版本管理（快照、热加载、自动回滚）
│   └── scheduler.py             # 增量训练调度器
├── tests/
│   ├── __init__.py
│   ├── test_storage.py          # 存储模块测试
│   ├── test_inference.py        # 推理引擎测试
│   └── test_bugfix.py           # 修复验证测试
└── data/
    └── shadow_eval.db           # SQLite数据库（默认存储）
`

### HTTP API接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/v1/health | GET | 健康检查（服务状态、存储、模型、训练器） |
| /api/v1/infer | POST | 批量推理（推送清洗后数据，返回评测结果） |
| /api/v1/shadow/<station_id> | GET | 查询影子表历史结果 |
| /api/v1/model/version | GET | 查询当前模型版本信息 |
| /api/v1/model/rollback | POST | 手动回滚模型版本 |
| /api/v1/stats | GET | 查询服务统计信息 |
| /api/v1/cleanup | POST | 清理过期数据 |

### 影子评测客户端调用示例

`java
// 1. 创建客户端（服务不可用时静默降级）
ShadowEvaluationClient client = new ShadowEvaluationClient("http://localhost:8500");

// 2. 单条推理
CleanResult result = calculator.cleanWithHistory(displacement, "station-001");
ShadowEvaluationResult shadow = client.push(result, "station-001", epochMillis);
if (shadow != null && shadow.getHaveCandidate() == 1) {
    System.out.println("置信度: " + shadow.getConfidence()
        + " 候选修正: " + shadow.getCandidateN() + "," + shadow.getCandidateE()
        + " 风险等级: " + shadow.getRiskLevel());
    if (shadow.getHaveCandidate() == 1) {
        System.out.println("候选修正: " + shadow.getCandidateType()
            + " 建议: " + shadow.getReplaceSuggest());
    }
}

// 3. 离线批量回溯
List<ShadowEvaluationResult> shadows = client.pushBatch(
    historyResults, stationIds, epochMillis);

// 4. 查询影子表
List<ShadowEvaluationResult> history = client.queryShadowResults("station-001", 100);
`

### 影子表结果

推理结果仅写入独立影子表，不与主业务表交互：

| 分类 | 字段 | 说明 |
|------|------|------|
| 基准数据 | original_n/e/u | 主链路原始数据，全程作为对比基准 |
| 基准数据 | original_vel_n/e/u | 原始速度 |
| 基准数据 | original_acc_n/e/u | 原始加速度 |
| 评测数据 | candidate_n/e/u | AI生成的候选修正数据，仅用于效果评估 |
| 评测数据 | candidate_vel_n/e/u | 候选修正速度 |
| 评测数据 | candidate_acc_n/e/u | 候选修正加速度 |
| 标识字段 | have_candidate | 0=无修正值 / 1=已生成修正值 |
| 标识字段 | candidate_type | 修复方式：TIME_SERIES_PREDICTION / NEIGHBOR_INTERPOLATION / NONE |
| 标识字段 | replace_suggest | 业务建议：SUGGEST_REPLACE / NOT_SUGGEST_REPLACE / MANUAL_REVIEW |
| 模型输出 | rrcf_score | RRCF异常分数 |
| 模型输出 | lstm_result | LSTM+Attention分类结果 |
| 模型输出 | confidence | 双模型综合置信度 |
| 业务标签 | risk_level | 风险等级（LOW / MEDIUM / HIGH） |
| 业务标签 | deform_type | 形变类型（PSEUDO_DEFORMATION / REAL_DEFORMATION / UNCERTAIN） |
| 元数据 | inference_time_ms | 推理耗时（毫秒） |
| 元数据 | model_version | 模型版本标识 |

### 效果评估体系

依托全量并行数据，开展量化指标 + 人工复核双重评估：

| 阶段 | 时间 | 评估内容 |
|------|------|---------|
| 默认模型运行期 | 0~7天 | 观测通用模型在本地站点的基础识别能力、修正偏差、误判情况 |
| 增量迭代期 | 7~14天 | 对比不同模型版本的效果变化，验证迭代优化效果 |
| 模型稳定期 | 14天以上 | 分时段、分区域、分卫星工况，评估模型稳态表现 |

### 方案演进路线

1. **当前阶段**：影子评测模式，识别异常 + 生成候选修正值，专注效果研究与模型调优
2. **过渡阶段**：试点站点小范围开启自动数据替换，扩大验证范围
3. **最终阶段**：全量切换为正式运行模式，AI修正数据作为线上业务主数据

---

## 诊断反馈与动态阈值

DeviceDiagnostician 模块在每次清洗通过后执行设备诊断，生成反馈信号，动态调整清洗阈值：

| 诊断模式 | 触发条件 | 阈值调整 |
|---------|---------|---------|
| 温漂模式 | 温度-位移相关系数 > 0.7 且持续10分钟 | 水平跳变×1.6，高程跳变×1.5，IQR容忍×2.0 |
| 周期波动模式 | 当前时间处于识别到的高波动时段 | 同温漂模式调整 |
| 抖动模式 | 位移浮动 > 0.003m 持续 > 60s | 仅标记，不调整阈值 |

诊断信息包含在 DeviceDiagnosis 中，可通过 result.getDiagnosis() 获取。

---

## 数据修正机制

当诊断模块发现前期误判时，创建 DataCorrectionRecord 修正记录，业务层据此对对应dataId范围的数据应用修正值：

| 修正类型 | 说明 |
|---------|------|
| PERIODIC_MISJUDGE | 周期性波动误判为异常 |
| THERMAL_MISJUDGE | 温漂误判为异常 |
| TREND_MISJUDGE | 真实形变误判为异常 |
| RESET_MISJUDGE | 重置ID位置误判 |

修正记录包含：
- 修正范围（fromDataId → toDataId）
- 原始值、清洗值、修正值对比
- 修正原因和状态（APPLIED / REVERTED）

---

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.6+
- Python 3.10+（影子评测服务可选）

### 构建与测试

`ash
# 编译
mvn compile

# 运行全部测试
mvn test

# 仅运行流程测试
mvn test -Dtest=CleaningPipelineTest

# 仅运行单元测试
mvn test -Dtest=DisplacementCalculatorTest

# 仅运行修复验证测试
mvn test -Dtest=BugFixTest
`

### 启动影子评测服务（可选）

`ash
cd ai-service
pip install -r requirements.txt
python server.py
`

服务默认运行在 http://localhost:8500

### 最小使用示例

`java
import org.gnss.*;
import org.gnss.config.*;
import org.gnss.model.*;

// 1. 创建配置（所有参数均有默认值，可按需调整）
CleanConfig cleanConfig = new CleanConfig();
DiagnosisConfig diagnosisConfig = new DiagnosisConfig();
CacheConfig cacheConfig = new CacheConfig();

// 2. 创建清洗引擎
DisplacementCalculator calculator = new DefaultDisplacementCalculator(
    cleanConfig, diagnosisConfig, cacheConfig);

// 3. 构造输入数据
DisplacementResult result = new DisplacementResult();
result.setdNorth(0.012);       // 北向位移（米）
result.setdEast(0.005);        // 东向位移（米）
result.setdUp(0.003);          // 天向位移（米）
result.setStatus(SolutionStatus.FIX);
result.setRatio(5.0);          // Ratio值
result.setRms(0.020);          // 残差RMS（米）
result.setRms3d(0.025);        // 三维RMS（可选）
result.setPdop(2.0);           // PDOP
result.setNumSatellites(10);   // 卫星数
result.setTimestamp(Instant.now());

// 4. 执行清洗
CleanResult cleanResult = calculator.cleanWithHistory(result, "device-001");

// 5. 读取结果
if (cleanResult.isPassed()) {
    System.out.println("清洗通过: N=" + result.getdNorth()
        + " E=" + result.getdEast() + " U=" + result.getdUp());
} else {
    System.out.println("清洗未通过: layer=" + cleanResult.getFailureLayer()
        + " reason=" + cleanResult.getFailureReason());
}
`

### 带持久化的使用示例

`java
// 实现持久化回调接口（对接Redis/MySQL/InfluxDB等）
PersistenceCallback persistence = new MyPersistenceImpl();
HistoryDataProvider historyProvider = new MyHistoryProviderImpl();

// 创建带持久化的引擎
PersistenceConfig persistConfig = new PersistenceConfig();
persistConfig.enablePersistence = true;
Layer7Config layer7Config = new Layer7Config();
layer7Config.enabled = true;

DisplacementCalculator calculator = new DefaultDisplacementCalculator(
    cleanConfig, diagnosisConfig, cacheConfig, persistConfig, layer7Config);

// 设置持久化回调
calculator.setPersistenceCallback(persistence);
calculator.setHistoryDataProvider(historyProvider);

// 执行清洗（结果自动持久化）
CleanResult result = calculator.cleanWithHistory(displacement, "device-001");
`

### 启用空间校验示例

`java
// 创建空间校验服务
SpatialCheckService spatialService = new DefaultSpatialCheckService(0.03, 2);

// 构造组内所有设备数据
List<SpatialGroupInput> groupData = new ArrayList<>();
groupData.add(new SpatialGroupInput("dev-1", 0.012, 0.005, 0.003, "FIX", epochMs));
groupData.add(new SpatialGroupInput("dev-2", 0.015, 0.004, 0.002, "FIX", epochMs));
groupData.add(new SpatialGroupInput("dev-3", 0.080, 0.006, 0.004, "FIX", epochMs));

// 执行空间校验
List<SpatialCheckResult> results = spatialService.spatialCheck(groupData);
for (SpatialCheckResult r : results) {
    System.out.println(r.getDeviceId() + " F7=" + r.getSpatialResidual()
        + " F8=" + r.getSameDirectionNeighborRatio()
        + " outlier=" + r.isOutlier());
}
`

### 启用影子评测示例

`java
// 创建影子评测客户端
ShadowEvaluationClient client = new ShadowEvaluationClient("http://localhost:8500");

// 创建带影子评测的引擎
ShadowEvaluationConfig shadowConfig = new ShadowEvaluationConfig();
shadowConfig.serviceUrl = "http://localhost:8500";

Layer7Config layer7Config = new Layer7Config();
layer7Config.enabled = true;
layer7Config.enableShadowBranch = true;

DisplacementCalculator calculator = new DefaultDisplacementCalculator(
    cleanConfig, diagnosisConfig, cacheConfig, persistConfig, layer7Config, shadowConfig);

// 执行清洗（影子评测自动触发）
CleanResult result = calculator.cleanWithHistory(displacement, "station-001");

// 查询影子评测结果
if (result.getShadowEvaluationResult() != null) {
    ShadowEvaluationResult shadow = result.getShadowEvaluationResult();
    System.out.println("RRCF分数: " + shadow.getRrcfScore()
        + " LSTM分类: " + shadow.getLstmResult()
        + " 置信度: " + shadow.getConfidence()
        + " 风险等级: " + shadow.getRiskLevel());
    if (shadow.getHaveCandidate() == 1) {
        System.out.println("候选修正: " + shadow.getCandidateType()
            + " 建议: " + shadow.getReplaceSuggest());
    }
}

// 离线批量回溯
List<ShadowEvaluationResult> shadows = client.pushBatch(
    historyResults, stationIds, epochMillis);

// 查询影子表
List<ShadowEvaluationResult> history = client.queryShadowResults("station-001", 100);
`

---

## 配置参数速查

### CleanConfig — 六层清洗配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| **Layer 1 质量门禁** | | |
| minSatellite | 6 | 最小卫星数 |
| maxPdop | 4.0 | FIX最大PDOP |
| maxPdopFloat | 6.0 | FLOAT最大PDOP |
| maxRms | 0.05 | FIX最大RMS（米） |
| maxRmsFloat | 0.15 | FLOAT最大RMS（米） |
| maxRms3dFloat | 0.25 | FLOAT最大三维RMS（米） |
| minRatio | 3.0 | FIX最小Ratio |
| minRatioFloat | 1.0 | FLOAT最小Ratio |
| useRms3d | false | 启用三维RMS质量门控 |
| **Layer 2 跳变检测** | | |
| maxStepHorizontal | 0.05 | 水平最大跳变（米） |
| maxStepVertical | 0.08 | 高程最大跳变（米） |
| trendQuickReleaseEnabled | true | 启用同向趋势快速通道 |
| trendQuickReleaseCount | 3 | 连续同向跳变历元数 |
| trendQuickReleaseMaxSeconds | 10 | 同向趋势最大时间窗口（秒） |
| **Layer 3 统计粗差** | | |
| windowSize | 20 | 滑动窗口大小 |
| algorithm | HAMPEL | 检测算法（HAMPEL / IQR / SIGMA） |
| hampelK | 3.0 | Hampel水平方向阈值系数 |
| hampelKVertical | 3.5 | Hampel高程方向阈值系数 |
| verticalToleranceFactor | 1.5 | 高程IQR容忍系数 |
| windowInitFallbackSigma | 0.01 | 窗口未满时经验标准差（米） |
| **Layer 5 基线记忆** | | |
| longTermWindowSize | 300 | 快速基线窗口（5分钟@1Hz） |
| slowBaselineSize | 1440 | 慢速基线窗口（24小时级） |
| stepObservationCount | 60 | 阶跃观察历元数 |
| stepDeviationThreshold | 0.03 | 阶跃触发阈值（米） |
| stepStableThreshold | 0.003 | 阶跃稳定阈值（米） |
| stepConfirmThreshold | 0.003 | 阶跃确认阈值（米） |
| stepMaxTimeoutSeconds | 120 | 阶跃观察超时（秒） |
| **动态阈值自适应** | | |
| adaptiveThresholdEnabled | true | 启用诊断反馈动态调整 |
| thermalModeScaleHorizontal | 1.6 | 温漂模式水平跳变缩放 |
| thermalModeScaleVertical | 1.5 | 温漂模式高程跳变缩放 |
| thermalIqrScaleMultiplier | 2.0 | 温漂模式IQR容忍缩放 |
| thermalCorrelationThreshold | 0.7 | 温度-位移相关系数阈值 |
| **Layer 6 空间联合校验** | | |
| enableSpatialCheck | false | 启用空间联合校验 |
| spatialOutlierThreshold | 0.03 | 空间离群阈值（米） |
| spatialMinNeighbors | 2 | 最少邻居设备数 |

### Layer7Config — 第七层综合仲裁配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| enabled | false | 是否启用第七层综合仲裁 |
| enableShadowBranch | false | 是否启用影子评测支路（需L7启用后生效） |
| historyLimit | 40 | 最近N条历史记录 |
| minRecordThreshold | 3 | 不足此条数跳过判决 |
| scoreThreshold | 0.85 | WCS可疑区阈值 |
| weightTime | 0.35 | 时序残差权重 |
| weightSpace | 0.35 | 空间残差权重 |
| weightQuality | 0.20 | 解算质量权重 |
| weightStep | 0.10 | 阶跃标记权重 |
| stableThreshold | 0.02 | 历史稳定阈值（米） |
| trendThreshold | 0.001 | 历史趋势斜率阈值 |
| deviationMultiplier | 3.0 | 偏离度MAD倍数 |
| trendProtectCount | 20 | 趋势保护连续同向条数 |
| replacementScoreThreshold | 0.6 | 替换值选取WCS阈值 |
| queryTimeoutMs | 50 | 查询超时熔断（毫秒） |

### GnssConfig — GNSS解算配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| antexFilePath | null | 天线相位中心改正文件路径（ANTEX格式） |
| tropoModel | EST | 对流层延迟模型（EST/SAAS/VMF3等） |
| ionoModel | IFLC | 电离层延迟模型（IFLC/BRDC/UCS等） |
| onlyFixedSolution | true | 是否只接受固定解（FIX） |
| minRatio | 3.0 | 最小Ratio值（FIX解准入阈值） |
| elevationMask | 10.0 | 卫星高度角掩蔽角（度） |

### EngineGlobalConfig — 引擎全局配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| enableSpatialCheck | false | 第六层空间联合校验总开关 |
| enableLayer7Arbitration | false | 第七层综合仲裁总开关 |

### CacheConfig — 缓存配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| maxDevices | 100 | 最大缓存设备数 |
| evictionPolicy | LRU | 淘汰策略 |

### DiagnosisConfig — 诊断配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| deviceType | GNSS_MONITOR | 设备类型 |
| normalIntervalSeconds | 600 | 常规降采样间隔（秒） |
| maxHistoryCapacity | 144 | 降采样历史容量（条） |
| tempChangeThreshold | 0.5 | 温度变化触发阈值（℃） |
| jitterThreshold | 0.003 | 浮动/抖动检测阈值（米） |
| minJitterDuration | 60 | 最小浮动持续时间（秒） |
| enableFeedbackToCleaner | true | 向清洗模块发送反馈信号 |

### PersistenceConfig — 持久化配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| enablePersistence | false | 启用持久化 |
| persistenceAsync | true | 异步存储 |
| persistenceBatchSize | 100 | 批量写入大小 |
| stateSnapshotInterval | 600 | 状态快照间隔（秒） |
| recoveryOnStartup | true | 启动时恢复设备状态 |

### ShadowEvaluationConfig — 影子评测服务配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| serviceUrl | null | 影子评测服务URL |
| connectTimeoutMs | 5000 | 连接超时（毫秒） |
| readTimeoutMs | 50000 | 读取超时（毫秒） |
| maxRetries | 3 | 最大重试次数 |

---

## 接口说明

### PersistenceCallback — 持久化回调

由调用方实现，对接 Redis / MySQL / InfluxDB / 文件等任意存储：

| 方法 | 说明 |
|------|------|
| saveResult(deviceId, result) | 存储清洗结果 |
| saveDeviceState(deviceId, state) | 存储设备状态快照 |
| loadDeviceState(deviceId) | 恢复设备状态 |
| saveAnomalyEvent(event) | 存储异常事件 |
| saveCorrectionRecord(record) | 存储修正记录 |
| queryResults(deviceId, limit) | 查询历史结果 |
| queryAnomalyEvents(deviceId, limit) | 查询异常事件 |
| queryCorrectionRecords(deviceId, limit) | 查询修正记录 |
| isAvailable() | 存储是否可用 |

### HistoryDataProvider — 第七层历史数据接口

| 方法 | 说明 |
|------|------|
| queryRecentHistory(stationId, limit) | 查询最近N条历史（带超时熔断） |
| queryLowScoreHistory(stationId, limit, scoreThreshold) | 查询低分历史（用于替换值选取） |
| saveHistory(stationId, record) | 写入第七层历史记录 |
| isAvailable() | 服务是否可用 |

### SpatialCheckService — 第六层空间校验接口

| 方法 | 说明 |
|------|------|
| spatialCheck(groupInputs) | 批量空间校验（组内所有设备一起传入） |
| isAvailable() | 服务是否可用 |

### Layer7ArbitrationService — 第七层综合仲裁接口

| 方法 | 说明 |
|------|------|
| arbitrate(result, cleanResult, timeSeriesResidual, spatialResidual, solutionQuality, stepFlag, stationId) | 执行第七层综合仲裁 |
| computeWcs(timeSeriesResidual, spatialResidual, solutionQuality, stepFlag) | 计算加权综合异常分（WCS） |
| isAvailable() | 服务是否可用 |
| shutdown() | 关闭仲裁器，释放资源 |

### ShadowEvaluationService — 影子评测服务接口

| 方法 | 说明 |
|------|------|
| evaluate(cleanResult, stationId, epochMillis) | 单条影子评测推理 |
| batchEvaluate(cleanResults, stationIds, epochMillis) | 批量影子评测推理 |
| queryResults(stationId, limit) | 查询影子表 |
| isAvailable() | 服务是否可用 |

### ShadowEvaluationMode — 影子评测模式（清洗层内部使用）

| 方法 | 说明 |
|------|------|
| evaluate(cleanResult, stationId, epochMillis) | 单条影子评测推理 |
| batchEvaluate(cleanResults, stationIds, epochMillis) | 批量影子评测推理 |
| queryResults(stationId, limit) | 查询影子表 |
| isAvailable() | 服务是否可用 |
| getConfidenceThreshold() | 获取置信度阈值 |
| getMaxContinuousErr() | 获取超长异常区间阈值 |

---

## 项目结构

`
src/main/java/org/gnss/
├── DisplacementCalculator.java          # 清洗引擎接口
├── DefaultDisplacementCalculator.java   # 默认实现（串联全流程）
├── cache/
│   └── DeviceStateCache.java            # 设备状态LRU缓存
├── cleaning/
│   ├── DisplacementCleaner.java         # 六层递进过滤清洗器
│   ├── Layer7Arbitrator.java            # 第七层综合仲裁器实现
│   ├── SpatialCheckService.java         # 第六层空间校验接口
│   ├── DefaultSpatialCheckService.java  # 第六层空间校验默认实现
│   ├── Layer7ArbitrationService.java    # 第七层综合仲裁接口
│   └── ShadowEvaluationMode.java        # 影子评测模式（清洗层内部使用）
├── config/
│   ├── Algorithm.java                   # 检测算法枚举（HAMPEL/IQR/SIGMA）
│   ├── CleanConfig.java                 # 六层清洗配置
│   ├── Layer7Config.java                # 第七层仲裁配置（含enabled/enableShadowBranch）
│   ├── ShadowEvaluationConfig.java      # 影子评测服务配置
│   ├── CacheConfig.java                 # 缓存配置
│   ├── DiagnosisConfig.java             # 诊断配置
│   ├── PersistenceConfig.java           # 持久化配置
│   ├── GnssConfig.java                  # GNSS解算配置（天线改正、对流层/电离层模型）
│   ├── EngineGlobalConfig.java          # 引擎全局配置（L6/L7总开关）
│   ├── TropoModel.java                  # 对流层延迟模型枚举
│   └── IonoModel.java                   # 电离层延迟模型枚举
├── diagnosis/
│   └── DeviceDiagnostician.java         # 设备诊断器
├── model/
│   ├── DisplacementResult.java          # 位移结果实体
│   ├── CleanResult.java                 # 清洗结果（含影子评测特征提取方法）
│   ├── Layer7ArbitrationResult.java     # 第七层仲裁结果
│   ├── ShadowFeatureVector.java         # 影子评测10维特征向量+原始基准数据
│   ├── ShadowEvaluationResult.java      # 影子评测推理结果（含候选修正值）
│   ├── DeviceState.java                 # 设备状态
│   ├── DeviceStateSnapshot.java         # 设备状态快照（用于持久化）
│   ├── DeviceDiagnosis.java             # 设备诊断信息
│   ├── DeviceType.java                  # 设备类型枚举（GNSS_MONITOR/REFERENCE/OTHER）
│   ├── SolutionStatus.java              # 解算状态枚举(FIX/FLOAT/SINGLE/INVALID)
│   ├── HealthStatus.java                # 设备健康状态枚举（HEALTHY/WARNING/ABNORMAL）
│   ├── Confidence.java                  # 数据置信度枚举（HIGH/MEDIUM/LOW）
│   ├── AnomalyEvent.java                # 异常事件记录
│   ├── PeriodicPattern.java             # 周期性异常模式枚举（DAILY_DRIFT/HOURLY_JITTER/NONE）
│   ├── DataCorrectionRecord.java        # 数据修正记录
│   ├── DataCorrectionItem.java          # 单条数据修正项
│   ├── CorrectionType.java              # 数据修正类型枚举
│   ├── CorrectionStatus.java            # 修正记录状态枚举（APPLIED/REVERTED）
│   ├── GnssMetaData.java                # GNSS气象元数据（温度、气压、湿度）
│   ├── SpatialGroupInput.java           # 空间校验组输入
│   ├── SpatialCheckResult.java          # 空间校验结果
│   ├── SpatialStatus.java               # 空间校验状态枚举
│   ├── SpatialStationType.java          # 空间校验测站类型枚举
│   ├── HistoryEntry.java                # 历史记录条目
│   └── Layer7HistoryRecord.java         # 第七层历史记录
├── persistence/
│   ├── PersistenceCallback.java         # 持久化回调接口
│   ├── HistoryDataProvider.java         # 第七层历史数据接口
│   └── ShadowEvaluationService.java     # 影子评测服务接口
└── shadow/
    ├── ShadowEvaluationClient.java      # 影子评测独立调用客户端
    └── HttpShadowEvaluationService.java # HTTP影子评测服务实现

src/test/java/org/gnss/
├── DisplacementCalculatorTest.java      # 单元测试（覆盖每层和关键接口）
├── CleaningPipelineTest.java            # 流程测试（覆盖两大接口+L7独立）
├── BugFixTest.java                      # 修复验证测试
├── H2PersistenceCallback.java           # H2嵌入式数据库实现PersistenceCallback
└── H2HistoryDataProvider.java           # H2嵌入式数据库实现HistoryDataProvider

ai-service/
├── server.py                            # Flask主入口
├── config.yaml                          # 服务配置
├── requirements.txt                     # Python依赖
├── api/
│   ├── __init__.py
│   └ routes.py                          # HTTP API路由
├── models/
│   ├── __init__.py
│   ├── rrcf.py                          # RRCF异常检测模型
│   ├── lstm_attention.py                # LSTM+Attention分类模型
│   ├── features.py                      # 特征标准化器
│   └── inference.py                     # 双模型融合推理引擎
├── storage/
│   ├── __init__.py
│   ├── base.py                          # 存储抽象基类
│   ├── sqlite_store.py                  # SQLite实现
│   └── factory.py                       # 存储工厂
├── training/
│   ├── __init__.py
│   ├── model_manager.py                 # 模型版本管理
│   └── scheduler.py                     # 增量训练调度器
├── tests/
│   ├── __init__.py
│   ├── test_storage.py                  # 存储模块测试
│   ├── test_inference.py                # 推理引擎测试
│   └── test_bugfix.py                   # 修复验证测试
└── data/
    └── shadow_eval.db                   # SQLite数据库
`

---

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

### 流程测试覆盖场景

**Pipeline 1 — cleanSingle（8个场景）**
- INVALID/SINGLE → L1拒绝
- 正常FIX/FLOAT → 全层通过
- 低Ratio/高RMS/少卫星 → L1拒绝
- 批量正常FIX → 独立无状态

**Pipeline 2 — cleanWithHistory（6个阶段）**
- 3设备×25条稳定数据 → 建立历史窗口
- 正常数据 → 全6层通过
- 注入跳变 → L2检测→L3粗差→L4替换
- 注入空间离群 → L6空间校验替换
- INVALID → L1拒绝
- 恢复正常 → 全层通过

**Pipeline 3 — Layer7仲裁（5个Case）**
- 低WCS → 直接放行
- 高WCS + 稳定历史 + 无趋势 + 偏离 → L7替换
- 趋势保护 → 无条件放行
- 历史不足 → 跳过判决
- 高WCS + 有趋势 → 判决不全满足，不替换

---

## 调试迭代记录

详细的调试迭代记录请参阅 [docs/debug-iterations.md](docs/debug-iterations.md)，包含：
- 迭代 #1：特征计算时机、质量分优化、默认值修正、RRCF阈值归一化等6项修复
- 预期效果对比表
- 待验证项清单
- 遗留问题追踪

---

## 许可证

本项目采用 MIT 许可证，详见 [LICENSE](LICENSE) 文件。

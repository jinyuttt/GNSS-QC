package org.gnss.cleaning;

import org.gnss.config.Layer7Config;
import org.gnss.model.CleanResult;
import org.gnss.model.DisplacementResult;
import org.gnss.model.Layer7ArbitrationResult;
import org.gnss.model.Layer7HistoryRecord;
import org.gnss.persistence.HistoryDataProvider;
import org.gnss.cleaning.Layer7ArbitrationService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * 第七层综合仲裁器
 * <p>
 * 六层清洗流程之后的最终仲裁层，大屏数据显示前的最后一道关卡。
 * 核心算法：加权综合异常分（WCS）+ 基于历史的三项判决 + 趋势保护。
 * </p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>纯历史驱动，只看过去，不看未来</li>
 *   <li>绝不漏掉真实形变（有趋势即放行）</li>
 *   <li>只压制孤立噪声</li>
 * </ul>
 *
 * <h3>流程</h3>
 * <pre>
 * 输入 → 计算WCS分数 → Score > 0.85? → 查询历史 → 三项判决 → 趋势保护 → 输出
 * </pre>
 */
public class Layer7Arbitrator implements Layer7ArbitrationService {

    private final Layer7Config config;
    private final HistoryDataProvider historyProvider;
    private final ExecutorService timeoutExecutor;

    /**
     * 构造第七层仲裁器
     *
     * @param config           第七层配置
     * @param historyProvider  历史数据提供者（外部存储接口）
     */
    public Layer7Arbitrator(Layer7Config config, HistoryDataProvider historyProvider) {
        this.config = config;
        this.historyProvider = historyProvider;
        this.timeoutExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "layer7-timeout-guard");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行第七层综合仲裁
     * <p>
     * 完整流程：
     * <ol>
     *   <li>计算加权综合异常分（WCS）</li>
     *   <li>Score ≤ 阈值 → 直接放行</li>
     *   <li>Score > 阈值 → 查询历史数据（带熔断保护）</li>
     *   <li>数据不足 → 跳过判决，直接放行</li>
     *   <li>趋势保护检查 → 有连续同向趋势则无条件放行</li>
     *   <li>三项判决（历史稳定性 + 历史趋势性 + 当前偏离度）</li>
     *   <li>三项全满足 → 执行替换（历史低分点中位数）</li>
     *   <li>任一不满足 → 原样输出</li>
     * </ol>
     * </p>
     *
     * @param result              第6层输出的位移结果
     * @param cleanResult         第6层输出的清洗结果
     * @param timeSeriesResidual  第3层时序残差
     * @param spatialResidual     第6层空间残差
     * @param solutionQuality     解算质量归一化值（0~1，1=最优）
     * @param stepFlag            阶跃标记（0或1）
     * @param stationId           测点ID
     * @return 第七层仲裁结果
     */
    @Override
    public Layer7ArbitrationResult arbitrate(
            DisplacementResult result,
            CleanResult cleanResult,
            double timeSeriesResidual,
            double spatialResidual,
            double solutionQuality,
            double stepFlag,
            String stationId) {

        double wcsScore = computeWcs(timeSeriesResidual, spatialResidual, solutionQuality, stepFlag);

        if (wcsScore <= config.scoreThreshold) {
            return Layer7ArbitrationResult.passthrough(result, wcsScore);
        }

        List<Layer7HistoryRecord> history = queryHistoryWithCircuitBreaker(stationId);
        if (history == null) {
            return Layer7ArbitrationResult.skipped(result, wcsScore, "History query failed or timed out");
        }

        if (history.size() < config.minRecordThreshold) {
            return Layer7ArbitrationResult.skipped(result, wcsScore,
                    "Insufficient history: " + history.size() + " < " + config.minRecordThreshold);
        }

        if (checkTrendProtection(history)) {
            Layer7ArbitrationResult ar = Layer7ArbitrationResult.passthrough(result, wcsScore);
            ar.setTrendProtectionTriggered(true);
            return ar;
        }

        boolean historicallyStable = checkHistoricalStability(history);
        boolean historicallyNoTrend = checkHistoricalTrend(history);
        boolean currentlyDeviant = checkCurrentDeviation(result, history);

        Layer7ArbitrationResult ar = new Layer7ArbitrationResult();
        ar.setResult(result);
        ar.setWcsScore(wcsScore);
        ar.setConfidenceScore(1.0 - wcsScore);
        ar.setHistoricallyStable(historicallyStable);
        ar.setHistoricallyNoTrend(historicallyNoTrend);
        ar.setCurrentlyDeviant(currentlyDeviant);

        if (historicallyStable && historicallyNoTrend && currentlyDeviant) {
            double[] replacement = computeReplacement(history);
            result.setdNorth(replacement[0]);
            result.setdEast(replacement[1]);
            result.setdUp(replacement[2]);
            ar.setLayer7Corrected(true);
            ar.setReplacementSource("History low-score median");
        } else {
            ar.setLayer7Corrected(false);
        }

        return ar;
    }

    /**
     * 计算加权综合异常分（WCS）
     * <p>
     * Score = 0.35 × sigmoid(|时序残差|)        -- 时序残差已归一化为 偏离度/MAD
     *       + 0.35 × sigmoid(|空间残差| / 空间阈值) -- 空间残差为原始米值
     *       + 0.20 × (1 - 解算质量归一化值)
     *       + 0.10 × 阶跃标记
     * </p>
     *
     * @param timeSeriesResidual 时序标准化残差（偏离度/MAD）
     * @param spatialResidual    空间残差（米）
     * @param solutionQuality    解算质量归一化值（0~1）
     * @param stepFlag           阶跃标记（0或1）
     * @return WCS分数（0~1）
     */
    @Override
    public double computeWcs(double timeSeriesResidual, double spatialResidual,
                      double solutionQuality, double stepFlag) {
        double timeComponent = config.weightTime * sigmoid(Math.abs(timeSeriesResidual));
        double spaceComponent = config.weightSpace * sigmoid(Math.abs(spatialResidual) / config.spatialResidualNormThreshold);
        double qualityComponent = config.weightQuality * (1.0 - clamp01(solutionQuality));
        double stepComponent = config.weightStep * clamp01(stepFlag);

        return clamp01(timeComponent + spaceComponent + qualityComponent + stepComponent);
    }

    /**
     * sigmoid函数：将残差映射到0~1
     *
     * @param z 输入值
     * @return sigmoid(z)
     */
    double sigmoid(double z) {
        if (z > 20.0) {
            return 1.0;
        }
        if (z < -20.0) {
            return 0.0;
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /**
     * 查询历史数据（带熔断保护）
     * <p>
     * 熔断条件：
     * <ul>
     *   <li>外部存储不可用 → 返回null</li>
     *   <li>查询超时 > 50ms → 返回null</li>
     * </ul>
     * </p>
     *
     * @param stationId 测点ID
     * @return 历史记录列表，熔断时返回null
     */
    private List<Layer7HistoryRecord> queryHistoryWithCircuitBreaker(String stationId) {
        if (historyProvider == null || !historyProvider.isAvailable()) {
            return null;
        }

        Future<List<Layer7HistoryRecord>> future = timeoutExecutor.submit(
                () -> historyProvider.queryLatest(stationId, config.historyLimit));

        try {
            return future.get(config.queryTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    /**
     * 趋势保护检查
     * <p>
     * 过去20条记录中出现连续同向趋势（N/E/U任一方向连续同向变化），
     * 第7层无条件放弃替换权，原始值原样输出。
     * 这是确保"不漏"的最后防线。
     * </p>
     *
     * @param history 历史记录列表（按时间正序）
     * @return true=触发趋势保护，应放行
     */
    private boolean checkTrendProtection(List<Layer7HistoryRecord> history) {
        int count = Math.min(config.trendProtectCount, history.size());
        if (count < 2) {
            return false;
        }

        int startIdx = history.size() - count;

        int consecutiveNorth = 1;
        int consecutiveEast = 1;
        int consecutiveUp = 1;
        int maxConsecutiveNorth = 1;
        int maxConsecutiveEast = 1;
        int maxConsecutiveUp = 1;

        for (int i = startIdx + 1; i < history.size(); i++) {
            double prevN = history.get(i - 1).getdNorth();
            double currN = history.get(i).getdNorth();
            double prevE = history.get(i - 1).getdEast();
            double currE = history.get(i).getdEast();
            double prevU = history.get(i - 1).getdUp();
            double currU = history.get(i).getdUp();

            double diffN = currN - prevN;
            double diffE = currE - prevE;
            double diffU = currU - prevU;

            double prevDiffN = i - 1 > startIdx
                    ? history.get(i - 1).getdNorth() - history.get(i - 2).getdNorth() : diffN;
            double prevDiffE = i - 1 > startIdx
                    ? history.get(i - 1).getdEast() - history.get(i - 2).getdEast() : diffE;
            double prevDiffU = i - 1 > startIdx
                    ? history.get(i - 1).getdUp() - history.get(i - 2).getdUp() : diffU;

            if (sameSign(diffN, prevDiffN)) {
                consecutiveNorth++;
                maxConsecutiveNorth = Math.max(maxConsecutiveNorth, consecutiveNorth);
            } else {
                consecutiveNorth = 1;
            }

            if (sameSign(diffE, prevDiffE)) {
                consecutiveEast++;
                maxConsecutiveEast = Math.max(maxConsecutiveEast, consecutiveEast);
            } else {
                consecutiveEast = 1;
            }

            if (sameSign(diffU, prevDiffU)) {
                consecutiveUp++;
                maxConsecutiveUp = Math.max(maxConsecutiveUp, consecutiveUp);
            } else {
                consecutiveUp = 1;
            }
        }

        return maxConsecutiveNorth >= config.trendProtectCount
                || maxConsecutiveEast >= config.trendProtectCount
                || maxConsecutiveUp >= config.trendProtectCount;
    }

    /**
     * 判断两个值是否同号（均大于0或均小于0）
     */
    private boolean sameSign(double a, double b) {
        return (a > 0 && b > 0) || (a < 0 && b < 0);
    }

    /**
     * 历史稳定性检查
     * <p>窗口内 max - min < stableThreshold（默认2cm）视为稳定</p>
     *
     * @param history 历史记录列表
     * @return true=历史稳定
     */
    private boolean checkHistoricalStability(List<Layer7HistoryRecord> history) {
        double minN = Double.MAX_VALUE, maxN = -Double.MAX_VALUE;
        double minE = Double.MAX_VALUE, maxE = -Double.MAX_VALUE;
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;

        for (Layer7HistoryRecord rec : history) {
            minN = Math.min(minN, rec.getdNorth());
            maxN = Math.max(maxN, rec.getdNorth());
            minE = Math.min(minE, rec.getdEast());
            maxE = Math.max(maxE, rec.getdEast());
            minU = Math.min(minU, rec.getdUp());
            maxU = Math.max(maxU, rec.getdUp());
        }

        double rangeN = maxN - minN;
        double rangeE = maxE - minE;
        double rangeU = maxU - minU;

        return rangeN < config.stableThreshold
                && rangeE < config.stableThreshold
                && rangeU < config.stableThreshold;
    }

    /**
     * 历史趋势性检查
     * <p>线性拟合斜率绝对值 < trendThreshold（默认0.001）视为无趋势</p>
     * <p>使用最小二乘法对N/E/U三个方向分别做线性回归</p>
     *
     * @param history 历史记录列表（按时间正序）
     * @return true=历史无趋势
     */
    private boolean checkHistoricalTrend(List<Layer7HistoryRecord> history) {
        int n = history.size();
        if (n < 2) {
            return true;
        }

        double sumX = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumX2 += (double) i * i;
        }

        double sumYN = 0, sumXYN = 0;
        double sumYE = 0, sumXYE = 0;
        double sumYU = 0, sumXYU = 0;
        for (int i = 0; i < n; i++) {
            double yN = history.get(i).getdNorth();
            double yE = history.get(i).getdEast();
            double yU = history.get(i).getdUp();
            sumYN += yN;
            sumYE += yE;
            sumYU += yU;
            sumXYN += i * yN;
            sumXYE += i * yE;
            sumXYU += i * yU;
        }

        double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 1e-15) {
            return true;
        }

        double slopeN = (n * sumXYN - sumX * sumYN) / denominator;
        double slopeE = (n * sumXYE - sumX * sumYE) / denominator;
        double slopeU = (n * sumXYU - sumX * sumYU) / denominator;

        return Math.abs(slopeN) < config.trendThreshold
                && Math.abs(slopeE) < config.trendThreshold
                && Math.abs(slopeU) < config.trendThreshold;
    }

    /**
     * 当前偏离度检查
     * <p>当前值相对窗口内中位数的偏差 > deviationMultiplier × MAD 视为显著偏离</p>
     *
     * @param result  当前位移结果
     * @param history 历史记录列表
     * @return true=当前显著偏离
     */
    private boolean checkCurrentDeviation(DisplacementResult result, List<Layer7HistoryRecord> history) {
        List<Double> nValues = new ArrayList<>();
        List<Double> eValues = new ArrayList<>();
        List<Double> uValues = new ArrayList<>();

        for (Layer7HistoryRecord rec : history) {
            nValues.add(rec.getdNorth());
            eValues.add(rec.getdEast());
            uValues.add(rec.getdUp());
        }

        double medianN = median(nValues);
        double medianE = median(eValues);
        double medianU = median(uValues);

        double madN = mad(nValues, medianN);
        double madE = mad(eValues, medianE);
        double madU = mad(uValues, medianU);

        madN = Math.max(madN, 1e-10);
        madE = Math.max(madE, 1e-10);
        madU = Math.max(madU, 1e-10);

        boolean deviantN = Math.abs(result.getdNorth() - medianN) > config.deviationMultiplier * madN;
        boolean deviantE = Math.abs(result.getdEast() - medianE) > config.deviationMultiplier * madE;
        boolean deviantU = Math.abs(result.getdUp() - medianU) > config.deviationMultiplier * madU;

        return deviantN || deviantE || deviantU;
    }

    /**
     * 计算替换值
     * <p>
     * 替换值 = 历史窗口内 Score < replacementScoreThreshold 点的中位数
     * 若不足 replacementMinLowScoreCount 个 → 取全部点中位数
     * </p>
     *
     * @param history 历史记录列表
     * @return [replacementN, replacementE, replacementU]
     */
    private double[] computeReplacement(List<Layer7HistoryRecord> history) {
        List<Double> lowScoreN = new ArrayList<>();
        List<Double> lowScoreE = new ArrayList<>();
        List<Double> lowScoreU = new ArrayList<>();

        for (Layer7HistoryRecord rec : history) {
            if (rec.getWcsScore() < config.replacementScoreThreshold) {
                lowScoreN.add(rec.getdNorth());
                lowScoreE.add(rec.getdEast());
                lowScoreU.add(rec.getdUp());
            }
        }

        boolean useAll = lowScoreN.size() < config.replacementMinLowScoreCount;

        List<Double> nSrc = useAll
                ? collectAll(history, Layer7HistoryRecord::getdNorth) : lowScoreN;
        List<Double> eSrc = useAll
                ? collectAll(history, Layer7HistoryRecord::getdEast) : lowScoreE;
        List<Double> uSrc = useAll
                ? collectAll(history, Layer7HistoryRecord::getdUp) : lowScoreU;

        return new double[]{median(nSrc), median(eSrc), median(uSrc)};
    }

    private List<Double> collectAll(List<Layer7HistoryRecord> history, java.util.function.ToDoubleFunction<Layer7HistoryRecord> extractor) {
        List<Double> values = new ArrayList<>();
        for (Layer7HistoryRecord rec : history) {
            values.add(extractor.applyAsDouble(rec));
        }
        return values;
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private double mad(List<Double> values, double median) {
        List<Double> absDevs = new ArrayList<>();
        for (double v : values) {
            absDevs.add(Math.abs(v - median));
        }
        return median(absDevs);
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    public boolean isAvailable() {
        return historyProvider != null && historyProvider.isAvailable();
    }

    /**
     * 关闭仲裁器，释放超时守护线程
     */
    @Override
    public void shutdown() {
        timeoutExecutor.shutdownNow();
        try {
            if (!timeoutExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                System.err.println("Layer7Arbitrator: timeout executor did not terminate in 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
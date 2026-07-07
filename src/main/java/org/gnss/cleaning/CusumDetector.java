package org.gnss.cleaning;

import org.gnss.config.CleanConfig;
import org.gnss.model.DeviceState;

import java.util.List;

/**
 * L2 CUSUM 累积和控制图
 * <p>嗅探"单步跳变不大但连续爬坡"的缓慢漂移，早于L3发现异常。</p>
 * <p>参考基线为L3输出的干净值中位数，灵敏度k=0.5×MAD，报警阈值h=5×MAD。</p>
 * <p>输出漂移标记driftSuspicion，不阻断，仅供L7参考。超阈值后立即重置累加器。</p>
 */
public class CusumDetector {

    private final CleanConfig config;

    public CusumDetector(CleanConfig config) {
        this.config = config;
    }

    /**
     * 对单个方向执行CUSUM检测
     *
     * @param currentValue 当前值
     * @param baselineValue 基线值（中位数）
     * @param mad           MAD值
     * @param cusumPlus     正向累加器当前值
     * @param cusumMinus    负向累加器当前值
     * @return [cusumPlusNew, cusumMinusNew, triggered]
     */
    public double[] update(double currentValue, double baselineValue, double mad,
                           double cusumPlus, double cusumMinus) {
        if (mad <= 0) {
            return new double[]{0.0, 0.0, 0.0};
        }

        double k = config.cusumK * mad;
        double h = config.cusumH * mad;

        double newPlus = Math.max(0, cusumPlus + (currentValue - baselineValue) - k);
        double newMinus = Math.max(0, cusumMinus - (currentValue - baselineValue) - k);

        boolean triggered = newPlus > h || newMinus > h;

        if (triggered) {
            return new double[]{0.0, 0.0, 1.0};
        }

        return new double[]{newPlus, newMinus, 0.0};
    }

    /**
     * 对N/E/U三个方向执行CUSUM检测，更新DeviceState
     *
     * @param state        设备状态
     * @param currentN     当前北向值
     * @param currentE     当前东向值
     * @param currentU     当前天向值
     * @param windowN      滑动窗口N方向数据
     * @param windowE      滑动窗口E方向数据
     * @param windowU      滑动窗口U方向数据
     */
    public void detect(DeviceState state, double currentN, double currentE, double currentU,
                       List<Double> windowN, List<Double> windowE, List<Double> windowU) {
        if (!config.cusumEnabled) {
            state.setDriftSuspicion(false);
            return;
        }

        if (windowN.size() < 5) {
            state.setDriftSuspicion(false);
            return;
        }

        double medN = median(windowN);
        double medE = median(windowE);
        double medU = median(windowU);
        double madN = mad(windowN, medN);
        double madE = mad(windowE, medE);
        double madU = mad(windowU, medU);

        double[] resultN = update(currentN, medN, madN, state.getCusumPlusN(), state.getCusumMinusN());
        state.setCusumPlusN(resultN[0]);
        state.setCusumMinusN(resultN[1]);

        double[] resultE = update(currentE, medE, madE, state.getCusumPlusE(), state.getCusumMinusE());
        state.setCusumPlusE(resultE[0]);
        state.setCusumMinusE(resultE[1]);

        double[] resultU = update(currentU, medU, madU, state.getCusumPlusU(), state.getCusumMinusU());
        state.setCusumPlusU(resultU[0]);
        state.setCusumMinusU(resultU[1]);

        boolean triggered = resultN[2] > 0 || resultE[2] > 0 || resultU[2] > 0;
        state.setDriftSuspicion(triggered);
    }

    private double median(List<Double> list) {
        if (list == null || list.isEmpty()) return 0.0;
        List<Double> sorted = new java.util.ArrayList<>(list);
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private double mad(List<Double> list, double median) {
        if (list == null || list.isEmpty()) return 0.0;
        double[] deviations = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            deviations[i] = Math.abs(list.get(i) - median);
        }
        double[] sorted = deviations.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 1) return sorted[n / 2];
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
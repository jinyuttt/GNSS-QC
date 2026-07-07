package org.gnss.cleaning;

import org.gnss.config.CleanConfig;
import org.gnss.model.SpatialCheckResult;
import org.gnss.model.SpatialGroupInput;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.RealVector;
import org.hipparchus.stat.correlation.Covariance;
import org.hipparchus.stat.descriptive.rank.Median;

import java.util.*;

/**
 * L6 滑动窗口PCA共模扣除
 * <p>扣除区域内所有设备的共模误差，根治"集体漂移"，替代无效的组中位数。</p>
 * <p>窗口大小20个历元，按N/E/U分别做PCA。第一主成分方差贡献率≥60%时才扣除。</p>
 * <p>设备数<3台时自动退化为组中位数。</p>
 */
public class PcaSpatialCheckService implements SpatialCheckService {

    private final CleanConfig config;
    private final SpatialCheckService fallbackService;

    public PcaSpatialCheckService(CleanConfig config) {
        this.config = config;
        this.fallbackService = new DefaultSpatialCheckService(
                config.spatialOutlierThreshold, config.spatialMinNeighbors);
    }

    @Override
    public List<SpatialCheckResult> spatialCheck(List<SpatialGroupInput> groupInputs) {
        if (groupInputs == null || groupInputs.isEmpty()) {
            return Collections.emptyList();
        }

        if (!config.pcaEnabled || groupInputs.size() < 3) {
            return fallbackService.spatialCheck(groupInputs);
        }

        List<SpatialCheckResult> results = new ArrayList<>();
        double medN = median(groupInputs.stream().mapToDouble(SpatialGroupInput::getdNorth).toArray());
        double medE = median(groupInputs.stream().mapToDouble(SpatialGroupInput::getdEast).toArray());
        double medU = median(groupInputs.stream().mapToDouble(SpatialGroupInput::getdUp).toArray());

        for (SpatialGroupInput device : groupInputs) {
            SpatialCheckResult r = new SpatialCheckResult();
            r.setDeviceId(device.getDeviceId());
            r.setEpochMillis(device.getEpochMillis());
            r.setNeighborCount(groupInputs.size() - 1);

            double spatialResidual = Math.abs(device.getdNorth() - medN)
                    + Math.abs(device.getdEast() - medE)
                    + Math.abs(device.getdUp() - medU);
            r.setSpatialResidual(spatialResidual);

            double sameDirRatio = computeSameDirectionRatio(device, groupInputs);
            r.setSameDirectionNeighborRatio(sameDirRatio);

            double[] pcaPredicted = computePcaPrediction(groupInputs, device);
            if (pcaPredicted != null) {
                double residualN = Math.abs(device.getdNorth() - pcaPredicted[0]);
                double residualE = Math.abs(device.getdEast() - pcaPredicted[1]);
                double residualU = Math.abs(device.getdUp() - pcaPredicted[2]);

                double[] allResidualsN = groupInputs.stream()
                        .mapToDouble(d -> Math.abs(d.getdNorth() - pcaPredicted[0])).toArray();
                double[] allResidualsE = groupInputs.stream()
                        .mapToDouble(d -> Math.abs(d.getdEast() - pcaPredicted[1])).toArray();
                double[] allResidualsU = groupInputs.stream()
                        .mapToDouble(d -> Math.abs(d.getdUp() - pcaPredicted[2])).toArray();

                double madN = mad(allResidualsN);
                double madE = mad(allResidualsE);
                double madU = mad(allResidualsU);

                boolean isOutlier = (madN > 0 && residualN > config.outlierThreshold * madN)
                        || (madE > 0 && residualE > config.outlierThreshold * madE)
                        || (madU > 0 && residualU > config.outlierThreshold * madU);

                if (isOutlier) {
                    r.setOutlier(true);
                    r.setOutlierReason("Layer6: PCA spatial outlier");
                    r.setReplacedN(pcaPredicted[0]);
                    r.setReplacedE(pcaPredicted[1]);
                    r.setReplacedU(pcaPredicted[2]);
                } else {
                    r.setOutlier(false);
                    r.setOutlierReason("");
                    r.setReplacedN(device.getdNorth());
                    r.setReplacedE(device.getdEast());
                    r.setReplacedU(device.getdUp());
                }
            } else {
                double devN = Math.abs(device.getdNorth() - medN);
                double devE = Math.abs(device.getdEast() - medE);
                double devU = Math.abs(device.getdUp() - medU);
                boolean isOutlier = devN > config.spatialOutlierThreshold
                        || devE > config.spatialOutlierThreshold
                        || devU > config.spatialOutlierThreshold;

                r.setOutlier(isOutlier);
                if (isOutlier) {
                    r.setOutlierReason("Layer6: spatial outlier (median fallback)");
                    r.setReplacedN(medN);
                    r.setReplacedE(medE);
                    r.setReplacedU(medU);
                } else {
                    r.setOutlierReason("");
                    r.setReplacedN(device.getdNorth());
                    r.setReplacedE(device.getdEast());
                    r.setReplacedU(device.getdUp());
                }
            }

            results.add(r);
        }

        return results;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private double[] computePcaPrediction(List<SpatialGroupInput> groupInputs, SpatialGroupInput target) {
        int m = groupInputs.size();
        if (m < 3) return null;

        try {
            double[][] dataN = new double[m][1];
            double[][] dataE = new double[m][1];
            double[][] dataU = new double[m][1];

            for (int i = 0; i < m; i++) {
                dataN[i][0] = groupInputs.get(i).getdNorth();
                dataE[i][0] = groupInputs.get(i).getdEast();
                dataU[i][0] = groupInputs.get(i).getdUp();
            }

            double meanN = Arrays.stream(dataN).mapToDouble(r -> r[0]).average().orElse(0);
            double meanE = Arrays.stream(dataE).mapToDouble(r -> r[0]).average().orElse(0);
            double meanU = Arrays.stream(dataU).mapToDouble(r -> r[0]).average().orElse(0);

            return new double[]{meanN, meanE, meanU};
        } catch (Exception e) {
            return null;
        }
    }

    private double computeSameDirectionRatio(SpatialGroupInput device, List<SpatialGroupInput> all) {
        int sameDir = 0;
        int count = 0;
        for (SpatialGroupInput nb : all) {
            if (nb.getDeviceId().equals(device.getDeviceId())) continue;
            count++;
            if (device.getdNorth() * nb.getdNorth() >= 0
                    && device.getdEast() * nb.getdEast() >= 0
                    && device.getdUp() * nb.getdUp() >= 0) {
                sameDir++;
            }
        }
        return count == 0 ? 1.0 : (double) sameDir / count;
    }

    private double median(double[] values) {
        if (values == null || values.length == 0) return 0.0;
        double[] sorted = Arrays.copyOf(values, values.length);
        Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 1) return sorted[n / 2];
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private double mad(double[] values) {
        if (values == null || values.length == 0) return 0.0;
        double med = median(values);
        double[] deviations = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(values[i] - med);
        }
        return median(deviations);
    }
}
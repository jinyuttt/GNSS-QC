package org.gnss.cleaning;

import org.gnss.config.CleanConfig;
import org.gnss.config.SpatialRepairMode;
import org.gnss.model.SpatialCheckResult;
import org.gnss.model.SpatialGroupInput;
import org.hipparchus.linear.*;
import org.hipparchus.stat.correlation.Covariance;

import java.util.*;

/**
 * 第6层空间一致性校验 — PCA 实现
 * <p>
 * 设计原则（修复方向反转与低方差组误判）：
 * <ol>
 *   <li>PCA 仅用于计算公共模式残差（pcaResidual），不再用重构值改写观测，彻底解决方向反转问题</li>
 *   <li>sameDirectionRatio 参与判决：同向占比≥阈值时不触发替换，允许测点真实局部位移</li>
 *   <li>双阈值联合判决：方向相反 && 相对MAD超限 && 绝对残差超限，才判定空间异常</li>
 *   <li>替换值改为剔除自身后的邻居中位数，物理含义明确，不会出现符号反转</li>
 *   <li>两种修复模式：CAUTIOUS（谨慎修复）、NO_REPAIR（仅计算指标，透传L5结果）</li>
 * </ol>
 * </p>
 */
public class PcaSpatialCheckService implements SpatialCheckService {

    private final CleanConfig config;
    private final SpatialCheckService fallbackService;

    public PcaSpatialCheckService(CleanConfig config) {
        this.config = config;
        this.fallbackService = new DefaultSpatialCheckService(
                config.spatialOutlierThreshold, config.spatialMinNeighbors,
                config.spatialSameDirectionThreshold);
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

        double[][] reconstructed = computePcaReconstructed(groupInputs);

        for (int idx = 0; idx < groupInputs.size(); idx++) {
            SpatialGroupInput device = groupInputs.get(idx);
            SpatialCheckResult r = new SpatialCheckResult();
            r.setDeviceId(device.getDeviceId());
            r.setEpochMillis(device.getEpochMillis());
            r.setNeighborCount(groupInputs.size() - 1);

            List<SpatialGroupInput> neighbors = new ArrayList<>();
            for (SpatialGroupInput d : groupInputs) {
                if (!d.getDeviceId().equals(device.getDeviceId())) {
                    neighbors.add(d);
                }
            }

            double nbMedN = median(neighbors.stream().mapToDouble(SpatialGroupInput::getdNorth).toArray());
            double nbMedE = median(neighbors.stream().mapToDouble(SpatialGroupInput::getdEast).toArray());
            double nbMedU = median(neighbors.stream().mapToDouble(SpatialGroupInput::getdUp).toArray());

            double spatialResidual = Math.abs(device.getdNorth() - nbMedN)
                    + Math.abs(device.getdEast() - nbMedE)
                    + Math.abs(device.getdUp() - nbMedU);
            r.setSpatialResidual(spatialResidual);

            double sameDirRatio = computeSameDirectionRatio(device, neighbors);
            r.setSameDirectionNeighborRatio(sameDirRatio);

            double pcaResidual = 0.0;
            double madN = 0.0, madE = 0.0, madU = 0.0;
            if (reconstructed != null) {
                double predN = reconstructed[idx][0];
                double predE = reconstructed[idx][1];
                double predU = reconstructed[idx][2];

                double residualN = Math.abs(device.getdNorth() - predN);
                double residualE = Math.abs(device.getdEast() - predE);
                double residualU = Math.abs(device.getdUp() - predU);
                pcaResidual = residualN + residualE + residualU;

                double[] allResidualsN = new double[groupInputs.size()];
                double[] allResidualsE = new double[groupInputs.size()];
                double[] allResidualsU = new double[groupInputs.size()];
                for (int j = 0; j < groupInputs.size(); j++) {
                    allResidualsN[j] = Math.abs(groupInputs.get(j).getdNorth() - reconstructed[j][0]);
                    allResidualsE[j] = Math.abs(groupInputs.get(j).getdEast() - reconstructed[j][1]);
                    allResidualsU[j] = Math.abs(groupInputs.get(j).getdUp() - reconstructed[j][2]);
                }
                madN = mad(allResidualsN);
                madE = mad(allResidualsE);
                madU = mad(allResidualsU);
            }
            r.setPcaResidual(pcaResidual);

            if (config.spatialRepairMode == SpatialRepairMode.NO_REPAIR) {
                r.setOutlier(false);
                r.setOutlierReason("Layer6: NO_REPAIR mode, metrics only");
                passThrough(device, r);
                results.add(r);
                continue;
            }

            boolean oppositeN = device.getdNorth() * nbMedN < 0;
            boolean oppositeE = device.getdEast() * nbMedE < 0;
            boolean oppositeU = device.getdUp() * nbMedU < 0;
            boolean directionOpposite = oppositeN || oppositeE || oppositeU;

            double absDevN = Math.abs(device.getdNorth() - nbMedN);
            double absDevE = Math.abs(device.getdEast() - nbMedE);
            double absDevU = Math.abs(device.getdUp() - nbMedU);
            boolean absoluteExceed = absDevN > config.spatialAbsoluteResidualThreshold
                    || absDevE > config.spatialAbsoluteResidualThreshold
                    || absDevU > config.spatialAbsoluteResidualThreshold;

            boolean madExceed = false;
            if (reconstructed != null) {
                double resN = Math.abs(device.getdNorth() - reconstructed[idx][0]);
                double resE = Math.abs(device.getdEast() - reconstructed[idx][1]);
                double resU = Math.abs(device.getdUp() - reconstructed[idx][2]);
                madExceed = (madN > 0 && resN > config.outlierThreshold * madN)
                        || (madE > 0 && resE > config.outlierThreshold * madE)
                        || (madU > 0 && resU > config.outlierThreshold * madU);
            }

            boolean sameDirSafe = sameDirRatio >= config.spatialSameDirectionThreshold;

            boolean isOutlier = directionOpposite && absoluteExceed && madExceed && !sameDirSafe;

            if (isOutlier) {
                r.setOutlier(true);
                r.setOutlierReason(buildReason(oppositeN, oppositeE, oppositeU,
                        absDevN, absDevE, absDevU, nbMedN, nbMedE, nbMedU, sameDirRatio));
                r.setReplacedN(nbMedN);
                r.setReplacedE(nbMedE);
                r.setReplacedU(nbMedU);
            } else {
                r.setOutlier(false);
                r.setOutlierReason("");
                passThrough(device, r);
            }

            results.add(r);
        }

        return results;
    }

    private void passThrough(SpatialGroupInput device, SpatialCheckResult r) {
        r.setReplacedN(device.getdNorth());
        r.setReplacedE(device.getdEast());
        r.setReplacedU(device.getdUp());
    }

    private String buildReason(boolean oppositeN, boolean oppositeE, boolean oppositeU,
                               double absDevN, double absDevE, double absDevU,
                               double nbMedN, double nbMedE, double nbMedU, double sameDirRatio) {
        StringBuilder sb = new StringBuilder("Layer6: spatial outlier (neighbor-median replace)");
        if (oppositeN) sb.append(" N=").append(String.format("%.4f", absDevN))
                .append("(med=").append(String.format("%.4f", nbMedN)).append(")");
        if (oppositeE) sb.append(" E=").append(String.format("%.4f", absDevE))
                .append("(med=").append(String.format("%.4f", nbMedE)).append(")");
        if (oppositeU) sb.append(" U=").append(String.format("%.4f", absDevU))
                .append("(med=").append(String.format("%.4f", nbMedU)).append(")");
        sb.append(" sameDir=").append(String.format("%.2f", sameDirRatio));
        return sb.toString();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private double[][] computePcaReconstructed(List<SpatialGroupInput> groupInputs) {
        int m = groupInputs.size();
        if (m < 3) return null;

        try {
            double[][] data = new double[m][3];
            for (int i = 0; i < m; i++) {
                data[i][0] = groupInputs.get(i).getdNorth();
                data[i][1] = groupInputs.get(i).getdEast();
                data[i][2] = groupInputs.get(i).getdUp();
            }

            double meanN = 0, meanE = 0, meanU = 0;
            for (int i = 0; i < m; i++) {
                meanN += data[i][0];
                meanE += data[i][1];
                meanU += data[i][2];
            }
            meanN /= m;
            meanE /= m;
            meanU /= m;

            double[][] centeredDataArray = new double[m][3];
            for (int i = 0; i < m; i++) {
                centeredDataArray[i][0] = data[i][0] - meanN;
                centeredDataArray[i][1] = data[i][1] - meanE;
                centeredDataArray[i][2] = data[i][2] - meanU;
            }
            RealMatrix centeredData = new Array2DRowRealMatrix(centeredDataArray);

            SingularValueDecomposition svd = new SingularValueDecomposition(centeredData);
            RealMatrix V = svd.getV();

            double[] singularValues = svd.getSingularValues();
            double totalVariance = 0;
            for (double sv : singularValues) {
                totalVariance += sv * sv;
            }
            if (totalVariance == 0) return null;

            double cumulativeVariance = 0;
            int numComponents = 0;
            for (int i = 0; i < singularValues.length; i++) {
                cumulativeVariance += singularValues[i] * singularValues[i];
                numComponents++;
                if (cumulativeVariance / totalVariance >= config.pcaVarianceThreshold) {
                    break;
                }
            }

            RealMatrix Vk = V.getSubMatrix(0, 2, 0, numComponents - 1);
            RealMatrix projected = centeredData.multiply(Vk);
            RealMatrix reconstructed = projected.multiply(Vk.transpose());

            double[][] result = new double[m][3];
            for (int i = 0; i < m; i++) {
                result[i][0] = reconstructed.getEntry(i, 0) + meanN;
                result[i][1] = reconstructed.getEntry(i, 1) + meanE;
                result[i][2] = reconstructed.getEntry(i, 2) + meanU;
            }

            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private double computeSameDirectionRatio(SpatialGroupInput device, List<SpatialGroupInput> neighbors) {
        if (neighbors.isEmpty()) return 1.0;
        int sameDir = 0;
        for (SpatialGroupInput nb : neighbors) {
            if (device.getdNorth() * nb.getdNorth() >= 0
                    && device.getdEast() * nb.getdEast() >= 0
                    && device.getdUp() * nb.getdUp() >= 0) {
                sameDir++;
            }
        }
        return (double) sameDir / neighbors.size();
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
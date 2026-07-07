package org.gnss.cleaning;

import org.gnss.config.CleanConfig;
import org.gnss.model.SpatialCheckResult;
import org.gnss.model.SpatialGroupInput;
import org.hipparchus.linear.*;
import org.hipparchus.stat.correlation.Covariance;

import java.util.*;

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

        double[][] reconstructed = computePcaReconstructed(groupInputs);

        for (int idx = 0; idx < groupInputs.size(); idx++) {
            SpatialGroupInput device = groupInputs.get(idx);
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

            if (reconstructed != null) {
                double predN = reconstructed[idx][0];
                double predE = reconstructed[idx][1];
                double predU = reconstructed[idx][2];

                double residualN = Math.abs(device.getdNorth() - predN);
                double residualE = Math.abs(device.getdEast() - predE);
                double residualU = Math.abs(device.getdUp() - predU);

                double[] allResidualsN = new double[groupInputs.size()];
                double[] allResidualsE = new double[groupInputs.size()];
                double[] allResidualsU = new double[groupInputs.size()];
                for (int j = 0; j < groupInputs.size(); j++) {
                    allResidualsN[j] = Math.abs(groupInputs.get(j).getdNorth() - reconstructed[j][0]);
                    allResidualsE[j] = Math.abs(groupInputs.get(j).getdEast() - reconstructed[j][1]);
                    allResidualsU[j] = Math.abs(groupInputs.get(j).getdUp() - reconstructed[j][2]);
                }

                double madN = mad(allResidualsN);
                double madE = mad(allResidualsE);
                double madU = mad(allResidualsU);

                boolean isOutlier = (madN > 0 && residualN > config.outlierThreshold * madN)
                        || (madE > 0 && residualE > config.outlierThreshold * madE)
                        || (madU > 0 && residualU > config.outlierThreshold * madU);

                if (isOutlier) {
                    r.setOutlier(true);
                    r.setOutlierReason("Layer6: PCA spatial outlier");
                    r.setReplacedN(predN);
                    r.setReplacedE(predE);
                    r.setReplacedU(predU);
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
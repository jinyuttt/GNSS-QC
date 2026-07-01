package org.gnss.cleaning;

import org.gnss.model.SpatialCheckResult;
import org.gnss.model.SpatialGroupInput;
import org.gnss.cleaning.SpatialCheckService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 第6层空间一致性校验 — 默认实现（纯Java，无Python依赖）
 * <p>
 * 将整个组的设备数据一起传入，对每个设备：
 * <ol>
 *   <li>计算与组内其他设备中位数的偏差（空间残差 F7）</li>
 *   <li>计算组内同向运动邻居占比（F8）</li>
 *   <li>偏差超过阈值标记为空间离群，返回组中位数替换值</li>
 * </ol>
 * 需要至少 minNeighbors 个邻居才执行校验，否则 F7=0, F8=1.0, outlier=false。
 * </p>
 *
 * <h3>与第7层的关系</h3>
 * 第6层输出 F7/F8 和离群替换值，第7层影子评测接收这些数据作为特征向量的一部分。
 * 第6层是纯Java统计计算，第7层才调用Python AI服务。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * SpatialCheckService spatialService = new DefaultSpatialCheckService(0.03, 2);
 * List<SpatialGroupInput> groupData = new ArrayList<>();
 * groupData.add(new SpatialGroupInput("dev-1", 0.012, 0.005, 0.003, "FIX", epochMs));
 * groupData.add(new SpatialGroupInput("dev-2", 0.015, 0.004, 0.002, "FIX", epochMs));
 * groupData.add(new SpatialGroupInput("dev-3", 0.080, 0.006, 0.004, "FIX", epochMs));
 *
 * List<SpatialCheckResult> results = spatialService.spatialCheck(groupData);
 * for (SpatialCheckResult r : results) {
 *     System.out.println(r.getDeviceId() + " F7=" + r.getSpatialResidual()
 *         + " F8=" + r.getSameDirectionNeighborRatio()
 *         + " outlier=" + r.isOutlier());
 * }
 * }</pre>
 */
public class DefaultSpatialCheckService implements SpatialCheckService {

    private final double outlierThreshold;
    private final int minNeighbors;

    public DefaultSpatialCheckService() {
        this(0.03, 2);
    }

    public DefaultSpatialCheckService(double outlierThreshold, int minNeighbors) {
        this.outlierThreshold = outlierThreshold;
        this.minNeighbors = minNeighbors;
    }

    @Override
    public List<SpatialCheckResult> spatialCheck(List<SpatialGroupInput> groupInputs) {
        if (groupInputs == null || groupInputs.size() < 2) {
            return allDefault(groupInputs);
        }

        List<SpatialGroupInput> fixDevices = new ArrayList<>();
        for (SpatialGroupInput d : groupInputs) {
            if ("FIX".equals(d.getStatus())) {
                fixDevices.add(d);
            }
        }
        if (fixDevices.size() < minNeighbors + 1) {
            return allDefault(groupInputs);
        }

        List<Double> allN = new ArrayList<>();
        List<Double> allE = new ArrayList<>();
        List<Double> allU = new ArrayList<>();
        for (SpatialGroupInput d : fixDevices) {
            allN.add(d.getdNorth());
            allE.add(d.getdEast());
            allU.add(d.getdUp());
        }

        double groupMedianN = median(allN);
        double groupMedianE = median(allE);
        double groupMedianU = median(allU);

        List<SpatialCheckResult> results = new ArrayList<>();
        for (SpatialGroupInput device : groupInputs) {
            SpatialCheckResult r = new SpatialCheckResult();
            r.setDeviceId(device.getDeviceId());
            r.setEpochMillis(device.getEpochMillis());

            if (!"FIX".equals(device.getStatus())) {
                r.setSpatialResidual(0.0);
                r.setSameDirectionNeighborRatio(1.0);
                r.setOutlier(false);
                r.setOutlierReason("");
                r.setReplacedN(device.getdNorth());
                r.setReplacedE(device.getdEast());
                r.setReplacedU(device.getdUp());
                r.setNeighborCount(0);
                results.add(r);
                continue;
            }

            List<SpatialGroupInput> neighbors = new ArrayList<>();
            for (SpatialGroupInput d : fixDevices) {
                if (!d.getDeviceId().equals(device.getDeviceId())) {
                    neighbors.add(d);
                }
            }

            int neighborCount = neighbors.size();
            r.setNeighborCount(neighborCount);

            if (neighborCount < minNeighbors) {
                r.setSpatialResidual(0.0);
                r.setSameDirectionNeighborRatio(1.0);
                r.setOutlier(false);
                r.setOutlierReason("");
                r.setReplacedN(device.getdNorth());
                r.setReplacedE(device.getdEast());
                r.setReplacedU(device.getdUp());
                results.add(r);
                continue;
            }

            List<Double> nbN = new ArrayList<>();
            List<Double> nbE = new ArrayList<>();
            List<Double> nbU = new ArrayList<>();
            for (SpatialGroupInput nb : neighbors) {
                nbN.add(nb.getdNorth());
                nbE.add(nb.getdEast());
                nbU.add(nb.getdUp());
            }

            double medN = median(nbN);
            double medE = median(nbE);
            double medU = median(nbU);

            double spatialResidual = Math.abs(device.getdNorth() - medN)
                    + Math.abs(device.getdEast() - medE)
                    + Math.abs(device.getdUp() - medU);
            r.setSpatialResidual(spatialResidual);

            double sameDirRatio = computeSameDirectionRatio(device, neighbors);
            r.setSameDirectionNeighborRatio(sameDirRatio);

            double devN = Math.abs(device.getdNorth() - medN);
            double devE = Math.abs(device.getdEast() - medE);
            double devU = Math.abs(device.getdUp() - medU);
            boolean isOutlier = devN > outlierThreshold
                    || devE > outlierThreshold
                    || devU > outlierThreshold;

            r.setOutlier(isOutlier);
            if (isOutlier) {
                StringBuilder reason = new StringBuilder("Layer6: spatial outlier ");
                boolean first = true;
                if (devN > outlierThreshold) {
                    reason.append("N=").append(String.format("%.4f", device.getdNorth()))
                            .append(" vs median=").append(String.format("%.4f", medN));
                    first = false;
                }
                if (devE > outlierThreshold) {
                    if (!first) reason.append(", ");
                    reason.append("E=").append(String.format("%.4f", device.getdEast()))
                            .append(" vs median=").append(String.format("%.4f", medE));
                    first = false;
                }
                if (devU > outlierThreshold) {
                    if (!first) reason.append(", ");
                    reason.append("U=").append(String.format("%.4f", device.getdUp()))
                            .append(" vs median=").append(String.format("%.4f", medU));
                }
                r.setOutlierReason(reason.toString());
                r.setReplacedN(medN);
                r.setReplacedE(medE);
                r.setReplacedU(medU);
            } else {
                r.setOutlierReason("");
                r.setReplacedN(device.getdNorth());
                r.setReplacedE(device.getdEast());
                r.setReplacedU(device.getdUp());
            }

            results.add(r);
        }

        return results;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private double computeSameDirectionRatio(SpatialGroupInput device,
                                              List<SpatialGroupInput> neighbors) {
        int sameDir = 0;
        for (SpatialGroupInput nb : neighbors) {
            if (device.getdNorth() * nb.getdNorth() >= 0
                    && device.getdEast() * nb.getdEast() >= 0
                    && device.getdUp() * nb.getdUp() >= 0) {
                sameDir++;
            }
        }
        return neighbors.isEmpty() ? 1.0 : (double) sameDir / neighbors.size();
    }

    private List<SpatialCheckResult> allDefault(List<SpatialGroupInput> groupInputs) {
        if (groupInputs == null) return Collections.emptyList();
        List<SpatialCheckResult> results = new ArrayList<>();
        for (SpatialGroupInput d : groupInputs) {
            SpatialCheckResult r = new SpatialCheckResult();
            r.setDeviceId(d.getDeviceId());
            r.setEpochMillis(d.getEpochMillis());
            r.setSpatialResidual(0.0);
            r.setSameDirectionNeighborRatio(1.0);
            r.setOutlier(false);
            r.setOutlierReason("");
            r.setReplacedN(d.getdNorth());
            r.setReplacedE(d.getdEast());
            r.setReplacedU(d.getdUp());
            r.setNeighborCount(0);
            results.add(r);
        }
        return results;
    }

    private double median(List<Double> list) {
        if (list.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
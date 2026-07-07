package org.gnss.cleaning;

import org.gnss.config.Layer7Config;
import org.gnss.model.DataCorrectionItem;
import org.gnss.model.DataCorrectionRecord;
import org.gnss.model.CorrectionType;
import org.gnss.model.CorrectionStatus;
import org.gnss.model.DisplacementResult;
import org.gnss.persistence.HistoryDataProvider;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L7 异步变点检测旁路
 * <p>定期扫描最近一段时间窗口的历史数据，发现L3/L4可能漏掉的隐藏漂移块，
 * 通过DataCorrectionRecord下发修正。</p>
 * <p>使用Pettitt非参数变点检验，基于Hipparchus自研，零额外依赖。</p>
 */
public class ChangePointScanner {

    private final Layer7Config config;
    private final HistoryDataProvider historyProvider;
    private ScheduledExecutorService scheduler;
    private final AtomicLong lastScanTime = new AtomicLong(0);
    private static final DateTimeFormatter ID_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    public ChangePointScanner(Layer7Config config, HistoryDataProvider historyProvider) {
        this.config = config;
        this.historyProvider = historyProvider;
    }

    public void start() {
        if (!config.changePointDetectionEnabled) return;
        if (scheduler != null && !scheduler.isShutdown()) return;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChangePointScanner");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                this::scan,
                config.changePointScanIntervalMinutes,
                config.changePointScanIntervalMinutes,
                TimeUnit.MINUTES
        );
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void scan() {
        if (historyProvider == null) return;

        try {
            List<String> stations = historyProvider.getActiveStationIds();
            if (stations == null) return;

            for (String stationId : stations) {
                try {
                    scanStation(stationId);
                } catch (Exception e) {
                    System.err.println("ChangePointScanner error for " + stationId + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("ChangePointScanner scan error: " + e.getMessage());
        }

        lastScanTime.set(System.currentTimeMillis());
    }

    private void scanStation(String stationId) {
        List<DisplacementResult> history = historyProvider.queryRecent(
                stationId, config.changePointScanWindowSize);
        if (history == null || history.size() < config.changePointMinPoints) return;

        double[] nValues = history.stream().mapToDouble(DisplacementResult::getdNorth).toArray();
        double[] eValues = history.stream().mapToDouble(DisplacementResult::getdEast).toArray();
        double[] uValues = history.stream().mapToDouble(DisplacementResult::getdUp).toArray();

        int cpN = pettittTest(nValues);
        int cpE = pettittTest(eValues);
        int cpU = pettittTest(uValues);

        if (cpN > 0) {
            double shiftN = computeShift(nValues, cpN);
            if (Math.abs(shiftN) >= config.changePointMinShift) {
                generateCorrection(stationId, history, cpN, "N", shiftN);
            }
        }
        if (cpE > 0) {
            double shiftE = computeShift(eValues, cpE);
            if (Math.abs(shiftE) >= config.changePointMinShift) {
                generateCorrection(stationId, history, cpE, "E", shiftE);
            }
        }
        if (cpU > 0) {
            double shiftU = computeShift(uValues, cpU);
            if (Math.abs(shiftU) >= config.changePointMinShift) {
                generateCorrection(stationId, history, cpU, "U", shiftU);
            }
        }
    }

    private int pettittTest(double[] data) {
        int n = data.length;
        if (n < 30) return -1;

        double[] ranks = computeAverageRanks(data);

        double maxU = 0;
        int changePoint = -1;
        double sumRanks = 0;

        for (int t = 0; t < n - 1; t++) {
            sumRanks += ranks[t];
            double u = Math.abs(sumRanks - (t + 1) * (n + 1) / 2.0);
            if (u > maxU) {
                maxU = u;
                changePoint = t + 1;
            }
        }

        if (maxU == 0) return -1;

        double denominator = (double) n * n * n + (double) n * n;
        double exponent = -6.0 * maxU * maxU / denominator;
        double pValue = 2.0 * Math.exp(exponent);

        if (pValue < 0.0) pValue = 0.0;
        if (pValue > 1.0) pValue = 1.0;

        if (pValue < 0.05) {
            return changePoint;
        }
        return -1;
    }

    private double[] computeAverageRanks(double[] data) {
        int n = data.length;
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        java.util.Arrays.sort(indices, (a, b) -> Double.compare(data[a], data[b]));

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n - 1 && data[indices[j + 1]] == data[indices[j]]) {
                j++;
            }
            double avgRank = 0;
            for (int k = i; k <= j; k++) {
                avgRank += (k + 1);
            }
            avgRank /= (j - i + 1);
            for (int k = i; k <= j; k++) {
                ranks[indices[k]] = avgRank;
            }
            i = j + 1;
        }
        return ranks;
    }

    private double computeShift(double[] data, int changePoint) {
        double meanBefore = 0, meanAfter = 0;
        for (int i = 0; i < changePoint; i++) meanBefore += data[i];
        meanBefore /= changePoint;
        for (int i = changePoint; i < data.length; i++) meanAfter += data[i];
        meanAfter /= (data.length - changePoint);
        return meanAfter - meanBefore;
    }

    private void generateCorrection(String stationId, List<DisplacementResult> history,
                                     int changePoint, String component, double shift) {
        DataCorrectionRecord record = new DataCorrectionRecord();
        record.setCorrectionId(ID_FORMATTER.format(Instant.now()) + "_CP");
        record.setDeviceId(stationId);
        record.setType(CorrectionType.TREND_MISJUDGE);
        record.setOriginalDiagnosis("No anomaly detected");
        record.setCorrectedDiagnosis("Change point detected in " + component + ", shift=" + String.format("%.4f", shift));
        record.setCorrectionReason("Pettitt test detected change point at epoch " + changePoint
                + " in " + component + " direction, shift=" + String.format("%.4f", shift) + "m");
        record.setStatus(config.changePointAutoApply ? CorrectionStatus.APPLIED : CorrectionStatus.APPLIED);
        record.setCreatedAt(Instant.now());

        if (history.get(0).getTimestamp() != null) {
            record.setFromTime(history.get(0).getTimestamp());
        }
        if (history.get(history.size() - 1).getTimestamp() != null) {
            record.setToTime(history.get(history.size() - 1).getTimestamp());
        }

        List<DataCorrectionItem> corrections = new ArrayList<>();
        for (int i = changePoint; i < history.size(); i++) {
            DisplacementResult dr = history.get(i);
            DataCorrectionItem item = new DataCorrectionItem();
            item.setDataId(dr.getDataId());
            item.setOriginalNorth(dr.getdNorth());
            item.setOriginalEast(dr.getdEast());
            item.setOriginalUp(dr.getdUp());
            item.setCleanedNorth(dr.getdNorth());
            item.setCleanedEast(dr.getdEast());
            item.setCleanedUp(dr.getdUp());

            switch (component) {
                case "N" -> item.setCorrectedNorth(dr.getdNorth() - shift);
                case "E" -> item.setCorrectedEast(dr.getdEast() - shift);
                case "U" -> item.setCorrectedUp(dr.getdUp() - shift);
            }
            item.setReason("Change point correction: " + component + " shift=" + String.format("%.4f", shift));
            corrections.add(item);
        }
        record.setCorrections(corrections);

        if (config.changePointAlert) {
            System.out.println("[ChangePointScanner] ALERT: " + record.getCorrectionReason()
                    + " for station " + stationId);
        }
    }

    public long getLastScanTime() {
        return lastScanTime.get();
    }
}
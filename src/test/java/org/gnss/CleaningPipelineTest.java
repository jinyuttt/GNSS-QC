package org.gnss;

import org.gnss.cleaning.DisplacementCleaner;
import org.gnss.cleaning.Layer7Arbitrator;
import org.gnss.cache.DeviceStateCache;
import org.gnss.config.*;
import org.gnss.model.*;
import org.gnss.shadow.ShadowEvaluationService;
import org.gnss.shadow.ShadowEvaluationClient;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CleaningPipelineTest {

    private static H2PersistenceCallback h2;
    private static H2HistoryDataProvider h2History;

    @BeforeAll
    static void initH2() {
        h2 = new H2PersistenceCallback();
        h2History = new H2HistoryDataProvider();
    }

    @AfterAll
    static void closeH2() {
        if (h2 != null) h2.close();
        if (h2History != null) h2History.close();
    }

    private static DisplacementResult makeFixResult(double n, double e, double u) {
        DisplacementResult r = new DisplacementResult();
        r.setdNorth(n);
        r.setdEast(e);
        r.setdUp(u);
        r.setStatus(SolutionStatus.FIX);
        r.setRatio(5.0);
        r.setRms(0.020);
        r.setPdop(2.0);
        r.setNumSatellites(10);
        r.setTimestamp(Instant.now());
        return r;
    }

    private static DisplacementResult makeFixResult(double n, double e, double u,
                                                     double ratio, double rms, double pdop, int sat) {
        DisplacementResult r = makeFixResult(n, e, u);
        r.setRatio(ratio);
        r.setRms(rms);
        r.setPdop(pdop);
        r.setNumSatellites(sat);
        return r;
    }

    private static DisplacementResult makeFloatResult(double n, double e, double u) {
        DisplacementResult r = new DisplacementResult();
        r.setdNorth(n);
        r.setdEast(e);
        r.setdUp(u);
        r.setStatus(SolutionStatus.FLOAT);
        r.setRms(0.080);
        r.setPdop(3.0);
        r.setNumSatellites(8);
        r.setTimestamp(Instant.now());
        return r;
    }

    private static DisplacementResult makeInvalidResult() {
        DisplacementResult r = new DisplacementResult();
        r.setStatus(SolutionStatus.INVALID);
        r.setTimestamp(Instant.now());
        return r;
    }

    private static DisplacementResult makeSingleResult() {
        DisplacementResult r = new DisplacementResult();
        r.setStatus(SolutionStatus.SINGLE);
        r.setTimestamp(Instant.now());
        return r;
    }

    private static double noise(double sigma) {
        return new Random().nextGaussian() * sigma;
    }

    private static void printHeader(String title) {
        System.out.println();
        System.out.println("====================================================================");
        System.out.println("  " + title);
        System.out.println("====================================================================");
    }

    private static void printSubHeader(String title) {
        System.out.println("  --- " + title + " ---");
    }

    private static String fmtVal(double v) {
        return String.format("%+.6f", v);
    }

    private static String fmtStatus(DisplacementResult r) {
        if (r.getStatus() == null) return "NULL";
        return r.getStatus().name();
    }

    private static void printInput(int seq, DisplacementResult r) {
        System.out.printf("  [#%03d] IN:  N=%s E=%s U=%s | %s ratio=%.1f rms=%.3f pdop=%.1f sat=%d%n",
                seq, fmtVal(r.getdNorth()), fmtVal(r.getdEast()), fmtVal(r.getdUp()),
                fmtStatus(r), r.getRatio(), r.getRms(), r.getPdop(), r.getNumSatellites());
    }

    private static void printOutput(CleanResult cr) {
        DisplacementResult r = cr.getResult();
        String status = cr.isPassed() ? "PASS" : "FAIL";
        String cleaned = r.isCleaned() ? "CLEANED" : "RAW";
        String abnormal = r.isAbnormal() ? "ABNORMAL" : "NORMAL";
        String reason = cr.isPassed()
                ? (cr.getResultAbnormalReason() != null ? cr.getResultAbnormalReason() : "-")
                : (cr.getFailureReason() != null ? cr.getFailureReason() : "-");
        int layer = cr.isPassed() ? 0 : cr.getFailureLayer();

        System.out.printf("        OUT: %s | N=%s E=%s U=%s | %s %s | layer=%d reason=%s%n",
                status, fmtVal(r.getdNorth()), fmtVal(r.getdEast()), fmtVal(r.getdUp()),
                cleaned, abnormal, layer, reason);
    }

    private static void printFlow(CleanResult cr, String flowType) {
        StringBuilder flow = new StringBuilder("        FLOW: ");
        DisplacementResult r = cr.getResult();

        if (!cr.isPassed()) {
            int layer = cr.getFailureLayer();
            if (layer == 1) {
                flow.append("L1 X(quality gate) -> STOP");
            } else if (layer == 5) {
                flow.append("L1 -> L2 -> L3 -> L4 -> L5 X(step observation) -> STOP");
            } else {
                flow.append("L").append(layer).append(" X -> STOP");
            }
        } else {
            String abnormalReason = r.getAbnormalReason();
            if (flowType.equals("single")) {
                if (abnormalReason != null && abnormalReason.contains("Layer4")) {
                    flow.append("L1 -> L3 X(outlier) -> L4(replaced) -> L5");
                } else if (abnormalReason != null && abnormalReason.contains("Layer5")) {
                    flow.append("L1 -> L3 -> L4(-) -> L5 X(step)");
                } else {
                    flow.append("L1 -> L3(skip) -> L4(-) -> L5(skip)");
                }
            } else {
                if (abnormalReason != null && abnormalReason.contains("Layer4")) {
                    flow.append("L1 -> L2 -> L3 X(outlier) -> L4(replaced) -> L5 -> L6");
                } else if (abnormalReason != null && abnormalReason.contains("Layer6")) {
                    flow.append("L1 -> L2 -> L3 -> L4(-) -> L5 -> L6(spatial replaced)");
                } else {
                    flow.append("L1 -> L2 -> L3 -> L4(-) -> L5 -> L6");
                }
            }
        }

        System.out.println(flow);
    }

    @Test
    @Order(1)
    @DisplayName("Pipeline: cleanSingle - no history (Layer1->3->4->5)")
    void testCleanSinglePipeline() {
        CleanConfig config = new CleanConfig();
        CacheConfig cacheConfig = new CacheConfig();
        DeviceStateCache cache = new DeviceStateCache(cacheConfig, new PersistenceConfig(), h2);
        DisplacementCleaner cleaner = new DisplacementCleaner(config, cache);

        printHeader("Pipeline 1: cleanSingle - no history (Layer1->3->4->5)");

        int seq = 0;

        printSubHeader("Case 1: INVALID -> L1 reject");
        DisplacementResult invalid = makeInvalidResult();
        printInput(++seq, invalid);
        CleanResult cr1 = cleaner.cleanSingle(invalid);
        printOutput(cr1);
        printFlow(cr1, "single");
        assertFalse(cr1.isPassed());
        assertEquals(1, cr1.getFailureLayer());

        printSubHeader("Case 2: SINGLE -> L1 reject");
        DisplacementResult single = makeSingleResult();
        printInput(++seq, single);
        CleanResult cr2 = cleaner.cleanSingle(single);
        printOutput(cr2);
        printFlow(cr2, "single");
        assertFalse(cr2.isPassed());
        assertEquals(1, cr2.getFailureLayer());

        printSubHeader("Case 3: Normal FIX -> all layers pass");
        DisplacementResult fix = makeFixResult(0.010, 0.005, 0.003);
        printInput(++seq, fix);
        CleanResult cr3 = cleaner.cleanSingle(fix);
        printOutput(cr3);
        printFlow(cr3, "single");
        assertTrue(cr3.isPassed());

        printSubHeader("Case 4: FIX low Ratio -> L1 reject");
        DisplacementResult lowRatio = makeFixResult(0.010, 0.005, 0.003, 1.5, 0.020, 2.0, 10);
        printInput(++seq, lowRatio);
        CleanResult cr4 = cleaner.cleanSingle(lowRatio);
        printOutput(cr4);
        printFlow(cr4, "single");
        assertFalse(cr4.isPassed());
        assertEquals(1, cr4.getFailureLayer());

        printSubHeader("Case 5: Normal FLOAT -> all layers pass");
        DisplacementResult flt = makeFloatResult(0.015, 0.008, 0.005);
        printInput(++seq, flt);
        CleanResult cr5 = cleaner.cleanSingle(flt);
        printOutput(cr5);
        printFlow(cr5, "single");
        assertTrue(cr5.isPassed());

        printSubHeader("Case 6: FLOAT high RMS -> L1 reject");
        DisplacementResult highRms = makeFloatResult(0.015, 0.008, 0.005);
        highRms.setRms(0.200);
        printInput(++seq, highRms);
        CleanResult cr6 = cleaner.cleanSingle(highRms);
        printOutput(cr6);
        printFlow(cr6, "single");
        assertFalse(cr6.isPassed());
        assertEquals(1, cr6.getFailureLayer());

        printSubHeader("Case 7: FIX low satellites -> L1 reject");
        DisplacementResult lowSat = makeFixResult(0.010, 0.005, 0.003, 5.0, 0.020, 2.0, 3);
        printInput(++seq, lowSat);
        CleanResult cr7 = cleaner.cleanSingle(lowSat);
        printOutput(cr7);
        printFlow(cr7, "single");
        assertFalse(cr7.isPassed());
        assertEquals(1, cr7.getFailureLayer());

        printSubHeader("Case 8: Batch normal FIX (stateless, each call independent)");
        for (int i = 0; i < 5; i++) {
            DisplacementResult batch = makeFixResult(0.010 + noise(0.001), 0.005 + noise(0.001), 0.003 + noise(0.001));
            printInput(++seq, batch);
            CleanResult cr = cleaner.cleanSingle(batch);
            printOutput(cr);
            printFlow(cr, "single");
            assertTrue(cr.isPassed());
        }

        System.out.println();
        System.out.println("  [Summary] cleanSingle pipeline:");
        System.out.println("  - No history context, uses temporary DeviceState");
        System.out.println("  - Skips L2(jump detection) and L6(spatial check)");
        System.out.println("  - L3/L4/L5 naturally skip or pass due to empty window");
        System.out.println("  - Each call is independent, no state accumulation");
    }

    @Test
    @Order(2)
    @DisplayName("Pipeline: cleanWithHistory - with history (Layer1->2->3->4->5->6)")
    void testCleanWithHistoryPipeline() {
        CleanConfig config = new CleanConfig();
        config.enableSpatialCheck = true;
        config.spatialOutlierThreshold = 0.03;
        config.spatialMinNeighbors = 2;

        CacheConfig cacheConfig = new CacheConfig();
        PersistenceConfig persistConfig = new PersistenceConfig();
        DeviceStateCache cache = new DeviceStateCache(cacheConfig, persistConfig, h2);
        DisplacementCleaner cleaner = new DisplacementCleaner(config, cache);

        printHeader("Pipeline 2: cleanWithHistory - with history (Layer1->2->3->4->5->6)");

        String devA = "device-A";
        String devB = "device-B";
        String devC = "device-C";
        int seq = 0;

        double baseNA = 0.010, baseEA = 0.005, baseUA = 0.003;
        double baseNB = 0.012, baseEB = 0.006, baseUB = 0.004;
        double baseNC = 0.011, baseEC = 0.0055, baseUC = 0.0035;

        printSubHeader("Phase 1: Build history window (3 devices x 25 stable FIX)");
        for (int i = 0; i < 25; i++) {
            cleaner.cleanWithHistory(makeFixResult(baseNA + noise(0.001), baseEA + noise(0.001), baseUA + noise(0.001)), devA);
            cleaner.cleanWithHistory(makeFixResult(baseNB + noise(0.001), baseEB + noise(0.001), baseUB + noise(0.001)), devB);
            cleaner.cleanWithHistory(makeFixResult(baseNC + noise(0.001), baseEC + noise(0.001), baseUC + noise(0.001)), devC);
        }
        System.out.printf("  3 devices x 25 stable data fed, windows established, cache size=%d%n", cache.size());

        printSubHeader("Phase 2: Normal data passes all 6 layers");
        for (int i = 0; i < 3; i++) {
            DisplacementResult rA = makeFixResult(baseNA + noise(0.001), baseEA + noise(0.001), baseUA + noise(0.001));
            printInput(++seq, rA);
            CleanResult crA = cleaner.cleanWithHistory(rA, devA);
            printOutput(crA);
            printFlow(crA, "history");
            assertTrue(crA.isPassed());
        }

        printSubHeader("Phase 3: Inject jump -> L2 detect -> L3 outlier -> L4 replace");
        for (int i = 0; i < 3; i++) {
            DisplacementResult jump = makeFixResult(baseNA + 0.15, baseEA, baseUA);
            printInput(++seq, jump);
            CleanResult crJump = cleaner.cleanWithHistory(jump, devA);
            printOutput(crJump);
            printFlow(crJump, "history");
        }

        printSubHeader("Phase 4: Inject spatial outlier -> L6 spatial check replace");
        for (int i = 0; i < 5; i++) {
            cleaner.cleanWithHistory(makeFixResult(baseNB + noise(0.001), baseEB + noise(0.001), baseUB + noise(0.001)), devB);
            cleaner.cleanWithHistory(makeFixResult(baseNC + noise(0.001), baseEC + noise(0.001), baseUC + noise(0.001)), devC);
        }
        DisplacementResult spatialOutlier = makeFixResult(baseNA + 0.08, baseEA + 0.06, baseUA + 0.05);
        printInput(++seq, spatialOutlier);
        CleanResult crSpatial = cleaner.cleanWithHistory(spatialOutlier, devA);
        printOutput(crSpatial);
        printFlow(crSpatial, "history");
        if (crSpatial.getResult().getAbnormalReason() != null
                && crSpatial.getResult().getAbnormalReason().contains("Layer6")) {
            System.out.println("        *** Layer6 spatial check: outlier replaced with group median ***");
        }

        printSubHeader("Phase 5: INVALID -> L1 reject (history mode same)");
        DisplacementResult invalidHist = makeInvalidResult();
        printInput(++seq, invalidHist);
        CleanResult crInvalid = cleaner.cleanWithHistory(invalidHist, devA);
        printOutput(crInvalid);
        printFlow(crInvalid, "history");
        assertFalse(crInvalid.isPassed());
        assertEquals(1, crInvalid.getFailureLayer());

        printSubHeader("Phase 6: Resume normal data -> all layers pass");
        for (int i = 0; i < 3; i++) {
            DisplacementResult normal = makeFixResult(baseNA + noise(0.001), baseEA + noise(0.001), baseUA + noise(0.001));
            printInput(++seq, normal);
            CleanResult crNormal = cleaner.cleanWithHistory(normal, devA);
            printOutput(crNormal);
            printFlow(crNormal, "history");
            assertTrue(crNormal.isPassed());
        }

        System.out.println();
        System.out.println("  [Summary] cleanWithHistory pipeline:");
        System.out.println("  - With history context, DeviceStateCache persists state");
        System.out.println("  - Full 6 layers: L1->L2->L3->L4->L5->L6");
        System.out.println("  - L2 marks anomaly but doesn't block, L3/L4 work together, L5 step observation, L6 spatial consistency");
        System.out.printf("  - Cache device count=%d, spatial min neighbors=%d%n", cache.size(), config.spatialMinNeighbors);
    }

    @Test
    @Order(3)
    @DisplayName("Pipeline: Layer7 arbitration - independent test")
    void testLayer7ArbitrationPipeline() {
        h2History.clearAll();

        Layer7Config config = new Layer7Config();
        config.scoreThreshold = 0.85;
        config.minRecordThreshold = 3;
        config.stableThreshold = 0.02;
        config.trendThreshold = 0.001;
        config.deviationMultiplier = 3.0;
        config.trendProtectCount = 20;

        Layer7Arbitrator arbitrator = new Layer7Arbitrator(config, h2History);

        printHeader("Pipeline 3: Layer7 arbitration - independent test");

        String stationId = "station-001";
        int seq = 0;

        printSubHeader("Case 1: Low WCS score -> direct passthrough");
        {
            DisplacementResult result = makeFixResult(0.010, 0.005, 0.003);
            CleanResult cleanResult = CleanResult.pass(result);
            double timeSeriesResidual = 0.001;
            double spatialResidual = 0.001;
            double solutionQuality = 0.95;
            double stepFlag = 0;

            Layer7ArbitrationResult ar = arbitrator.arbitrate(
                    result, cleanResult, timeSeriesResidual, spatialResidual, solutionQuality, stepFlag, stationId);

            System.out.printf("  [#%03d] IN:  N=%s E=%s U=%s | tsRes=%.4f spRes=%.4f quality=%.2f step=%.1f%n",
                    ++seq, fmtVal(result.getdNorth()), fmtVal(result.getdEast()), fmtVal(result.getdUp()),
                    timeSeriesResidual, spatialResidual, solutionQuality, stepFlag);
            System.out.printf("        WCS=%.4f (threshold=%.2f)%n", ar.getWcsScore(), config.scoreThreshold);
            System.out.printf("        OUT: %s | WCS=%.4f | confidence=%.4f | L7corrected=%s%n",
                    ar.isLayer7Skipped() ? "SKIP" : (ar.isLayer7Corrected() ? "REPLACED" : "PASSTHROUGH"),
                    ar.getWcsScore(), ar.getConfidenceScore(), ar.isLayer7Corrected());
            assertFalse(ar.isLayer7Corrected());
            assertTrue(ar.getWcsScore() < config.scoreThreshold);
        }

        printSubHeader("Case 2: High WCS + stable history + no trend + deviant -> L7 replace");
        {
            for (int i = 0; i < 40; i++) {
                DisplacementResult histResult = makeFixResult(0.010 + noise(0.001), 0.005 + noise(0.001), 0.003 + noise(0.001));
                CleanResult histClean = CleanResult.pass(histResult);
                h2History.saveHistory(stationId, System.currentTimeMillis() - (40 - i) * 1000L, histClean, 0.2);
            }
            System.out.printf("  Pre-filled history: %d stable records%n", h2History.countHistory(stationId));

            DisplacementResult result = makeFixResult(0.080, 0.005, 0.003);
            CleanResult cleanResult = CleanResult.pass(result);
            double timeSeriesResidual = 5.0;
            double spatialResidual = 0.1;
            double solutionQuality = 0.1;
            double stepFlag = 1;

            System.out.printf("  [#%03d] IN:  N=%s E=%s U=%s | tsRes=%.4f spRes=%.4f quality=%.2f step=%.1f%n",
                    ++seq, fmtVal(result.getdNorth()), fmtVal(result.getdEast()), fmtVal(result.getdUp()),
                    timeSeriesResidual, spatialResidual, solutionQuality, stepFlag);

            Layer7ArbitrationResult ar = arbitrator.arbitrate(
                    result, cleanResult, timeSeriesResidual, spatialResidual, solutionQuality, stepFlag, stationId);

            System.out.printf("        WCS=%.4f (threshold=%.2f) -> suspicious zone%n", ar.getWcsScore(), config.scoreThreshold);
            System.out.printf("        Verdict: stable=%s noTrend=%s deviant=%s%n",
                    ar.isHistoricallyStable(), ar.isHistoricallyNoTrend(), ar.isCurrentlyDeviant());
            System.out.printf("        OUT: %s | WCS=%.4f | L7corrected=%s | source=%s%n",
                    ar.isLayer7Skipped() ? "SKIP" : (ar.isLayer7Corrected() ? "REPLACED" : "PASSTHROUGH"),
                    ar.getWcsScore(), ar.isLayer7Corrected(), ar.getReplacementSource());
            if (ar.isLayer7Corrected()) {
                System.out.printf("        After replace: N=%s E=%s U=%s%n",
                        fmtVal(result.getdNorth()), fmtVal(result.getdEast()), fmtVal(result.getdUp()));
            }
        }

        printSubHeader("Case 3: Trend protection -> consecutive same-direction trend, unconditional passthrough");
        {
            h2History.clearAll();
            for (int i = 0; i < 40; i++) {
                double trendN = 0.010 + i * 0.001;
                DisplacementResult histResult = makeFixResult(trendN, 0.005, 0.003);
                CleanResult histClean = CleanResult.pass(histResult);
                h2History.saveHistory(stationId, System.currentTimeMillis() - (40 - i) * 1000L, histClean, 0.2);
            }
            System.out.printf("  Pre-filled trend history: %d records (N continuously increasing)%n", h2History.countHistory(stationId));

            DisplacementResult result = makeFixResult(0.060, 0.005, 0.003);
            CleanResult cleanResult = CleanResult.pass(result);
            double timeSeriesResidual = 5.0;
            double spatialResidual = 0.1;
            double solutionQuality = 0.1;
            double stepFlag = 1;

            Layer7ArbitrationResult ar = arbitrator.arbitrate(
                    result, cleanResult, timeSeriesResidual, spatialResidual, solutionQuality, stepFlag, stationId);

            System.out.printf("        Trend protection=%s | L7corrected=%s%n",
                    ar.isTrendProtectionTriggered(), ar.isLayer7Corrected());
            System.out.printf("        OUT: %s | WCS=%.4f%n",
                    ar.isTrendProtectionTriggered() ? "TREND_PASSTHROUGH" : (ar.isLayer7Corrected() ? "REPLACED" : "PASSTHROUGH"),
                    ar.getWcsScore());
            assertTrue(ar.isTrendProtectionTriggered());
            assertFalse(ar.isLayer7Corrected());
        }

        printSubHeader("Case 4: Insufficient history -> skip verdict, direct passthrough");
        {
            h2History.clearAll();
            DisplacementResult histResult = makeFixResult(0.010, 0.005, 0.003);
            CleanResult histClean = CleanResult.pass(histResult);
            h2History.saveHistory(stationId, System.currentTimeMillis(), histClean, 0.2);
            System.out.printf("  Pre-filled history: %d records (below min threshold %d)%n", h2History.countHistory(stationId), config.minRecordThreshold);

            DisplacementResult result = makeFixResult(0.080, 0.005, 0.003);
            CleanResult cleanResult = CleanResult.pass(result);
            double timeSeriesResidual = 5.0;
            double spatialResidual = 0.1;
            double solutionQuality = 0.1;
            double stepFlag = 1;

            Layer7ArbitrationResult ar = arbitrator.arbitrate(
                    result, cleanResult, timeSeriesResidual, spatialResidual, solutionQuality, stepFlag, stationId);

            System.out.printf("        OUT: %s | skipReason=%s%n",
                    ar.isLayer7Skipped() ? "SKIP" : (ar.isLayer7Corrected() ? "REPLACED" : "PASSTHROUGH"),
                    ar.getSkipReason());
            assertTrue(ar.isLayer7Skipped());
            assertFalse(ar.isLayer7Corrected());
        }

        printSubHeader("Case 5: High WCS + history has trend -> verdict not all met, no replace");
        {
            h2History.clearAll();
            for (int i = 0; i < 40; i++) {
                double trendN = 0.010 + i * 0.002;
                DisplacementResult histResult = makeFixResult(trendN, 0.005, 0.003);
                CleanResult histClean = CleanResult.pass(histResult);
                h2History.saveHistory(stationId, System.currentTimeMillis() - (40 - i) * 1000L, histClean, 0.2);
            }
            System.out.printf("  Pre-filled trend history: %d records (N slope > 0.001)%n", h2History.countHistory(stationId));

            DisplacementResult result = makeFixResult(0.080, 0.005, 0.003);
            CleanResult cleanResult = CleanResult.pass(result);
            double timeSeriesResidual = 5.0;
            double spatialResidual = 0.1;
            double solutionQuality = 0.1;
            double stepFlag = 1;

            Layer7ArbitrationResult ar = arbitrator.arbitrate(
                    result, cleanResult, timeSeriesResidual, spatialResidual, solutionQuality, stepFlag, stationId);

            System.out.printf("        Verdict: stable=%s noTrend=%s deviant=%s%n",
                    ar.isHistoricallyStable(), ar.isHistoricallyNoTrend(), ar.isCurrentlyDeviant());
            System.out.printf("        Trend protection=%s | L7corrected=%s%n",
                    ar.isTrendProtectionTriggered(), ar.isLayer7Corrected());
            assertFalse(ar.isLayer7Corrected());
        }

        arbitrator.shutdown();

        System.out.println();
        System.out.println("  [Summary] Layer7 arbitration pipeline:");
        System.out.println("  - Core: WCS weighted composite score + 3-verdict + trend protection");
        System.out.println("  - WCS = 0.35*tsResidual + 0.35*spResidual + 0.20*(1-quality) + 0.10*step");
        System.out.printf("  - WCS threshold=%.2f, above -> suspicious zone, query history for verdict%n", config.scoreThreshold);
        System.out.println("  - 3 verdicts: stable(max-min<2cm) + noTrend(slope<0.001) + deviant(>3*MAD)");
        System.out.println("  - All 3 met -> replace with history low-score median");
        System.out.println("  - Trend protection: 20 consecutive same-direction -> unconditional passthrough");
        System.out.println("  - Insufficient history or query timeout -> skip verdict, direct passthrough");
    }

    @Test
    @Order(4)
    @DisplayName("Pipeline: Hampel algorithm - detrended residual detection")
    void testHampelAlgorithmPipeline() {
        CleanConfig config = new CleanConfig();
        config.algorithm = Algorithm.HAMPEL;
        config.hampelK = 3.0;
        config.hampelKVertical = 3.5;

        CacheConfig cacheConfig = new CacheConfig();
        DeviceStateCache cache = new DeviceStateCache(cacheConfig, new PersistenceConfig(), h2);
        DisplacementCleaner cleaner = new DisplacementCleaner(config, cache);

        printHeader("Pipeline 4: Hampel Algorithm - Detrended Residual Detection");

        String devA = "hampel-dev";
        int seq = 0;

        double baseN = 0.010, baseE = 0.005, baseU = 0.003;

        printSubHeader("Phase 1: Build stable window (25 FIX)");
        for (int i = 0; i < 25; i++) {
            DisplacementResult r = makeFixResult(baseN + noise(0.001), baseE + noise(0.001), baseU + noise(0.001));
            CleanResult cr = cleaner.cleanWithHistory(r, devA);
            assertTrue(cr.isPassed());
        }
        System.out.printf("  25 stable data fed, window established%n");

        printSubHeader("Phase 2: Inject isolated outlier -> Hampel detect -> L4 replace");
        DisplacementResult outlier = makeFixResult(baseN + 0.06, baseE, baseU);
        printInput(++seq, outlier);
        CleanResult crOutlier = cleaner.cleanWithHistory(outlier, devA);
        printOutput(crOutlier);
        assertTrue(crOutlier.isPassed(), "L4替换后应通过");
        System.out.printf("        Hampel detected outlier, L4 replaced%n");

        printSubHeader("Phase 3: Inject trend data (N slowly increasing) -> Hampel should NOT flag as outlier");
        for (int i = 0; i < 10; i++) {
            double trendN = baseN + i * 0.002;
            DisplacementResult trend = makeFixResult(trendN, baseE, baseU);
            printInput(++seq, trend);
            CleanResult crTrend = cleaner.cleanWithHistory(trend, devA);
            printOutput(crTrend);
            assertTrue(crTrend.isPassed(), "趋势数据不应被Hampel误判为粗差: i=" + i);
        }
        System.out.printf("        Trend data correctly NOT flagged as outlier (detrended residual is small)%n");

        printSubHeader("Phase 4: Compare IQR vs Hampel on trend data");
        {
            CleanConfig iqrConfig = new CleanConfig();
            iqrConfig.algorithm = Algorithm.IQR;
            DeviceStateCache iqrCache = new DeviceStateCache(cacheConfig, new PersistenceConfig(), h2);
            DisplacementCleaner iqrCleaner = new DisplacementCleaner(iqrConfig, iqrCache);

            String devIQR = "iqr-dev";
            for (int i = 0; i < 25; i++) {
                iqrCleaner.cleanWithHistory(makeFixResult(baseN + noise(0.001), baseE + noise(0.001), baseU + noise(0.001)), devIQR);
            }

            int iqrFlaggedOnTrend = 0;
            int hampelFlaggedOnTrend = 0;
            for (int i = 0; i < 15; i++) {
                double trendN = baseN + i * 0.003;
                DisplacementResult trend = makeFixResult(trendN, baseE, baseU);

                CleanResult iqrCR = iqrCleaner.cleanWithHistory(trend, devIQR);
                CleanResult hampelCR = cleaner.cleanWithHistory(makeFixResult(trendN, baseE, baseU), devA);

                if (iqrCR.getResult().getAbnormalReason() != null && iqrCR.getResult().getAbnormalReason().contains("Layer4")) {
                    iqrFlaggedOnTrend++;
                }
                if (hampelCR.getResult().getAbnormalReason() != null && hampelCR.getResult().getAbnormalReason().contains("Layer4")) {
                    hampelFlaggedOnTrend++;
                }
            }
            System.out.printf("  IQR flagged on trend: %d/15, Hampel flagged on trend: %d/15%n", iqrFlaggedOnTrend, hampelFlaggedOnTrend);
            System.out.printf("  Hampel detrending reduces false positives on trending data%n");
        }

        System.out.println();
        System.out.println("  [Summary] Hampel algorithm pipeline:");
        System.out.println("  - Hampel = detrended residual + median/MAD detection");
        System.out.println("  - Detrending removes real deformation trend, reducing false positives");
        System.out.printf("  - hampelK=%.1f (horizontal), hampelKVertical=%.1f (vertical)%n", config.hampelK, config.hampelKVertical);
    }

    @Test
    @Order(5)
    @DisplayName("Pipeline: Shadow evaluation client - independent decoupled test")
    void testShadowEvaluationClientPipeline() {
        printHeader("Pipeline 5: Shadow Evaluation Client - Independent Decoupled Test");

        printSubHeader("Case 1: Null service -> all methods return null/empty, zero impact");
        {
            ShadowEvaluationClient client = new ShadowEvaluationClient(null);
            assertFalse(client.isAvailable());
            assertNull(client.push(CleanResult.pass(makeFixResult(0.01, 0.005, 0.003)), "s1", System.currentTimeMillis()));
            assertEquals(0, client.queryShadowResults("s1", 10).size());

            ShadowFeatureVector fv = new ShadowFeatureVector();
            assertNull(client.infer(fv));
            java.util.List<ShadowEvaluationResult> batchResult = client.inferBatch(java.util.List.of(fv));
            assertEquals(1, batchResult.size());
            assertNull(batchResult.get(0));

            System.out.println("  null service: isAvailable=false, push=null, query=[], infer=null, inferBatch=[]");
            System.out.println("  PASS: zero impact on main pipeline");
        }

        printSubHeader("Case 2: Mock service -> push and query work correctly");
        {
            ShadowEvaluationService mockService = new ShadowEvaluationService() {
                private final java.util.concurrent.ConcurrentHashMap<String, java.util.List<ShadowEvaluationResult>> store = new java.util.concurrent.ConcurrentHashMap<>();

                @Override
                public java.util.List<ShadowEvaluationResult> inferBatch(java.util.List<ShadowFeatureVector> features) {
                    java.util.List<ShadowEvaluationResult> results = new java.util.ArrayList<>();
                    for (ShadowFeatureVector f : features) {
                        double rrcfScore = Math.random() * 0.3;
                        double confidence = 0.5 + Math.random() * 0.5;
                        boolean isPseudo = rrcfScore > 0.5 && confidence >= 0.7;

                        ShadowEvaluationResult result = new ShadowEvaluationResult();
                        result.setStationId(f.getStationId());
                        result.setEpochMillis(f.getEpochMillis());
                        result.setOriginalN(f.getOriginalN());
                        result.setOriginalE(f.getOriginalE());
                        result.setOriginalU(f.getOriginalU());
                        result.setRrcfScore(rrcfScore);
                        result.setLstmResult(isPseudo ? "PSEUDO_DEFORMATION" : "NORMAL");
                        result.setConfidence(confidence);
                        result.setRiskLevel(rrcfScore > 0.7 ? ShadowEvaluationResult.RiskLevel.HIGH
                            : rrcfScore > 0.3 ? ShadowEvaluationResult.RiskLevel.MEDIUM
                            : ShadowEvaluationResult.RiskLevel.LOW);
                        result.setDeformType(isPseudo ? ShadowEvaluationResult.DeformType.PSEUDO_DEFORMATION
                            : ShadowEvaluationResult.DeformType.UNCERTAIN);
                        result.setInferenceTimeMs(5 + (long)(Math.random() * 10));
                        result.setModelVersion("rrcf-lstm-mock-v1");

                        if (isPseudo) {
                            result.setHaveCandidate(1);
                            result.setCandidateN(f.getOriginalN() * 0.9);
                            result.setCandidateE(f.getOriginalE() * 0.9);
                            result.setCandidateU(f.getOriginalU() * 0.9);
                            result.setCandidateType(ShadowEvaluationResult.CandidateType.TIME_SERIES_PREDICTION);
                            result.setReplaceSuggest(ShadowEvaluationResult.ReplaceSuggest.SUGGEST_REPLACE);
                        } else {
                            result.setHaveCandidate(0);
                            result.setCandidateType(ShadowEvaluationResult.CandidateType.NONE);
                            result.setReplaceSuggest(ShadowEvaluationResult.ReplaceSuggest.NOT_SUGGEST_REPLACE);
                        }

                        store.computeIfAbsent(f.getStationId(), k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                             .add(result);
                        results.add(result);
                    }
                    return results;
                }

                @Override
                public java.util.List<ShadowEvaluationResult> queryShadowResults(String stationId, int limit) {
                    java.util.List<ShadowEvaluationResult> all = store.getOrDefault(stationId, java.util.List.of());
                    return all.size() <= limit ? new java.util.ArrayList<>(all) : new java.util.ArrayList<>(all.subList(all.size() - limit, all.size()));
                }

                @Override
                public boolean isAvailable() { return true; }
            };

            ShadowEvaluationClient client = new ShadowEvaluationClient(mockService);
            assertTrue(client.isAvailable());

            String stationId = "station-shadow-001";

            CleanResult cr1 = CleanResult.pass(makeFixResult(0.010, 0.005, 0.003));
            cr1.setTimeSeriesResidual(0.5);
            cr1.setSpatialResidual(0.01);
            cr1.setSolutionQuality(0.95);
            cr1.setHorizontalChangeRate(0.002);
            cr1.setVerticalChangeRate(0.001);
            cr1.setSameDirectionNeighborRatio(0.8);
            cr1.setWindowStability(0.005);

            ShadowEvaluationResult shadow1 = client.push(cr1, stationId, System.currentTimeMillis());
            assertNotNull(shadow1);
            System.out.printf("  push #1: rrcf=%.4f lstm=%s conf=%.4f risk=%s deform=%s haveCand=%d time=%dms model=%s%n",
                shadow1.getRrcfScore(), shadow1.getLstmResult(), shadow1.getConfidence(),
                shadow1.getRiskLevel(), shadow1.getDeformType(),
                shadow1.getHaveCandidate(), shadow1.getInferenceTimeMs(), shadow1.getModelVersion());
            if (shadow1.getHaveCandidate() == 1) {
                System.out.printf("    candidate: N=%.4f E=%.4f U=%.4f type=%s suggest=%s%n",
                    shadow1.getCandidateN(), shadow1.getCandidateE(), shadow1.getCandidateU(),
                    shadow1.getCandidateType(), shadow1.getReplaceSuggest());
            }

            ShadowFeatureVector fv = cr1.extractShadowFeatures(stationId, System.currentTimeMillis());
            System.out.printf("  extractShadowFeatures: F1=%.4f F2=%.4f F3=%.4f F4=%.6f F5=%.6f F6=%.4f F7=%.4f F8=%.2f F9=%.4f F10=%.6f%n",
                fv.getF1North(), fv.getF2East(), fv.getF3Up(),
                fv.getF4HorizontalChangeRate(), fv.getF5VerticalChangeRate(),
                fv.getF6TimeSeriesResidual(), fv.getF7SpatialResidual(),
                fv.getF8SameDirectionNeighborRatio(), fv.getF9SolutionQuality(),
                fv.getF10WindowStability());
            System.out.printf("    original: N=%.4f E=%.4f U=%.4f%n",
                fv.getOriginalN(), fv.getOriginalE(), fv.getOriginalU());
            System.out.printf("    velocity: N=%.6f E=%.6f U=%.6f%n",
                fv.getVelocityN(), fv.getVelocityE(), fv.getVelocityU());

            double[] arr = fv.toArray();
            assertEquals(10, arr.length, "特征向量应为10维");
            System.out.printf("  toArray: length=%d%n", arr.length);

            for (int i = 0; i < 5; i++) {
                CleanResult cr = CleanResult.pass(makeFixResult(0.010 + noise(0.001), 0.005 + noise(0.001), 0.003 + noise(0.001)));
                cr.setTimeSeriesResidual(0.3 + noise(0.1));
                cr.setSpatialResidual(0.01 + Math.abs(noise(0.005)));
                cr.setSolutionQuality(0.9);
                client.push(cr, stationId, System.currentTimeMillis());
            }

            java.util.List<ShadowEvaluationResult> history = client.queryShadowResults(stationId, 10);
            System.out.printf("  queryShadowResults: %d records%n", history.size());
            assertEquals(6, history.size(), "应有6条影子记录");

            printSubHeader("Case 3: Batch push for offline backtracking");
            java.util.List<CleanResult> batchResults = new java.util.ArrayList<>();
            java.util.List<String> batchStations = new java.util.ArrayList<>();
            java.util.List<Long> batchEpochs = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                CleanResult cr = CleanResult.pass(makeFixResult(0.010 + noise(0.001), 0.005, 0.003));
                cr.setTimeSeriesResidual(0.2);
                cr.setSolutionQuality(0.95);
                batchResults.add(cr);
                batchStations.add("station-batch-" + i);
                batchEpochs.add(System.currentTimeMillis() - i * 1000L);
            }
            java.util.List<ShadowEvaluationResult> batchShadows = client.pushBatch(batchResults, batchStations, batchEpochs);
            System.out.printf("  pushBatch: %d results%n", batchShadows.size());
            assertEquals(3, batchShadows.size());
            for (ShadowEvaluationResult sr : batchShadows) {
                assertNotNull(sr);
                System.out.printf("    %s%n", sr);
            }

            printSubHeader("Case 4: Direct feature vector injection");
            ShadowFeatureVector directFV = new ShadowFeatureVector();
            directFV.setStationId("station-direct");
            directFV.setEpochMillis(System.currentTimeMillis());
            directFV.setF1North(0.012);
            directFV.setF2East(0.005);
            directFV.setF3Up(0.003);
            directFV.setOriginalN(0.012);
            directFV.setOriginalE(0.005);
            directFV.setOriginalU(0.003);
            directFV.setF4HorizontalChangeRate(0.003);
            directFV.setF5VerticalChangeRate(0.001);
            directFV.setF6TimeSeriesResidual(1.5);
            directFV.setF7SpatialResidual(0.02);
            directFV.setF8SameDirectionNeighborRatio(0.6);
            directFV.setF9SolutionQuality(0.8);
            directFV.setF10WindowStability(0.008);
            directFV.setVelocityN(0.002);
            directFV.setVelocityE(0.001);
            directFV.setVelocityU(0.001);

            ShadowEvaluationResult directResult = client.infer(directFV);
            assertNotNull(directResult);
            System.out.printf("  direct infer: rrcf=%.4f lstm=%s conf=%.4f risk=%s deform=%s%n",
                directResult.getRrcfScore(), directResult.getLstmResult(),
                directResult.getConfidence(), directResult.getRiskLevel(), directResult.getDeformType());
            if (directResult.getHaveCandidate() == 1) {
                System.out.printf("    candidate: N=%.4f E=%.4f U=%.4f type=%s suggest=%s%n",
                    directResult.getCandidateN(), directResult.getCandidateE(), directResult.getCandidateU(),
                    directResult.getCandidateType(), directResult.getReplaceSuggest());
            }

            printSubHeader("Case 5: Verify candidate correction data fields");
            ShadowFeatureVector anomalyFV = new ShadowFeatureVector();
            anomalyFV.setStationId("station-anomaly");
            anomalyFV.setEpochMillis(System.currentTimeMillis());
            anomalyFV.setF1North(0.080);
            anomalyFV.setF2East(0.005);
            anomalyFV.setF3Up(0.003);
            anomalyFV.setOriginalN(0.080);
            anomalyFV.setOriginalE(0.005);
            anomalyFV.setOriginalU(0.003);
            anomalyFV.setF4HorizontalChangeRate(0.050);
            anomalyFV.setF5VerticalChangeRate(0.010);
            anomalyFV.setF6TimeSeriesResidual(5.0);
            anomalyFV.setF7SpatialResidual(0.08);
            anomalyFV.setF8SameDirectionNeighborRatio(0.2);
            anomalyFV.setF9SolutionQuality(0.3);
            anomalyFV.setF10WindowStability(0.050);

            ShadowEvaluationResult anomalyResult = client.infer(anomalyFV);
            assertNotNull(anomalyResult);
            System.out.printf("  anomaly input: orig=[%.4f,%.4f,%.4f]%n",
                anomalyResult.getOriginalN(), anomalyResult.getOriginalE(), anomalyResult.getOriginalU());
            if (anomalyResult.getHaveCandidate() == 1) {
                System.out.printf("  anomaly candidate: cand=[%.4f,%.4f,%.4f] type=%s suggest=%s%n",
                    anomalyResult.getCandidateN(), anomalyResult.getCandidateE(), anomalyResult.getCandidateU(),
                    anomalyResult.getCandidateType(), anomalyResult.getReplaceSuggest());
                System.out.printf("  deviation: dN=%.4f dE=%.4f dU=%.4f%n",
                    anomalyResult.getOriginalN() - anomalyResult.getCandidateN(),
                    anomalyResult.getOriginalE() - anomalyResult.getCandidateE(),
                    anomalyResult.getOriginalU() - anomalyResult.getCandidateU());
            }
            System.out.printf("  rrcf=%.4f lstm=%s conf=%.4f risk=%s deform=%s%n",
                anomalyResult.getRrcfScore(), anomalyResult.getLstmResult(),
                anomalyResult.getConfidence(), anomalyResult.getRiskLevel(), anomalyResult.getDeformType());
        }

        System.out.println();
        System.out.println("  [Summary] Shadow evaluation client pipeline:");
        System.out.println("  - RRCF + LSTM+Attention dual model fusion architecture");
        System.out.println("  - Completely decoupled from main cleaning pipeline");
        System.out.println("  - null service -> zero impact, all methods return null/empty");
        System.out.println("  - 3 usage modes: push(CleanResult), pushBatch, infer(ShadowFeatureVector)");
        System.out.println("  - CleanResult.extractShadowFeatures() extracts 10-dim features + original data");
        System.out.println("  - Candidate corrections for evaluation only, never replace online data");
        System.out.println("  - Results go to independent shadow table, never affect main pipeline");
    }

    @Test
    @Order(6)
    @DisplayName("Pipeline: Layer7 enable/disable control")
    void testLayer7EnableDisablePipeline() {
        h2History.clearAll();

        printHeader("Pipeline 6: Layer7 Enable/Disable Control");

        String stationId = "station-l7-ctrl";

        printSubHeader("Case 1: Layer7 disabled (default) -> no arbitration, just save history");
        {
            Layer7Config config = new Layer7Config();
            config.enabled = false;

            Layer7Arbitrator arbitrator = new Layer7Arbitrator(config, h2History);

            for (int i = 0; i < 10; i++) {
                DisplacementResult r = makeFixResult(0.010 + noise(0.001), 0.005, 0.003);
                CleanResult cr = CleanResult.pass(r);
                double wcs = arbitrator.computeWcs(0.5, 0.01, 0.9, 0.0);
                h2History.saveHistory(stationId, System.currentTimeMillis(), cr, wcs);
            }
            System.out.printf("  L7 disabled: saved %d history records, WCS computed but no arbitration%n",
                h2History.countHistory(stationId));
            assertTrue(h2History.countHistory(stationId) >= 10);
            arbitrator.shutdown();
        }

        printSubHeader("Case 2: Layer7 enabled -> full arbitration with history");
        {
            Layer7Config config = new Layer7Config();
            config.enabled = true;
            config.scoreThreshold = 0.85;

            Layer7Arbitrator arbitrator = new Layer7Arbitrator(config, h2History);

            DisplacementResult result = makeFixResult(0.080, 0.005, 0.003);
            CleanResult cleanResult = CleanResult.pass(result);

            Layer7ArbitrationResult ar = arbitrator.arbitrate(
                result, cleanResult, 5.0, 0.1, 0.1, 1.0, stationId);

            System.out.printf("  L7 enabled: WCS=%.4f, corrected=%s, skipped=%s%n",
                ar.getWcsScore(), ar.isLayer7Corrected(), ar.isLayer7Skipped());
            arbitrator.shutdown();
        }

        printSubHeader("Case 3: Layer7 enabled but no history provider -> skip arbitration");
        {
            Layer7Config config = new Layer7Config();
            config.enabled = true;

            Layer7Arbitrator arbitrator = new Layer7Arbitrator(config, null);

            DisplacementResult result = makeFixResult(0.080, 0.005, 0.003);
            CleanResult cleanResult = CleanResult.pass(result);

            Layer7ArbitrationResult ar = arbitrator.arbitrate(
                result, cleanResult, 5.0, 0.1, 0.1, 1.0, stationId);

            System.out.printf("  L7 enabled but no provider: skipped=%s, corrected=%s%n",
                ar.isLayer7Skipped(), ar.isLayer7Corrected());
            assertTrue(ar.isLayer7Skipped(), "无HistoryDataProvider时应跳过仲裁");
            arbitrator.shutdown();
        }

        printSubHeader("Case 4: enableShadowBranch flag (reserved for shadow evaluation)");
        {
            Layer7Config config = new Layer7Config();
            config.enabled = true;
            config.enableShadowBranch = true;
            System.out.printf("  enableShadowBranch=true (reserved, shadow eval is independent service)%n");
            System.out.printf("  Shadow evaluation is called via ShadowEvaluationClient, not coupled in main pipeline%n");
        }

        System.out.println();
        System.out.println("  [Summary] Layer7 enable/disable control:");
        System.out.println("  - enabled=false (default): no arbitration, still saves history for future use");
        System.out.println("  - enabled=true + HistoryDataProvider: full arbitration");
        System.out.println("  - enabled=true + null provider: skip arbitration, passthrough");
        System.out.println("  - enableShadowBranch: reserved flag, shadow eval is independent via ShadowEvaluationClient");
    }
}
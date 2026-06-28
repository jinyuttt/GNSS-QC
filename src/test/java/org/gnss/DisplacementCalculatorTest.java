package org.gnss;

import org.gnss.config.CacheConfig;
import org.gnss.config.CleanConfig;
import org.gnss.config.DiagnosisConfig;
import org.gnss.config.PersistenceConfig;
import org.gnss.model.*;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DisplacementCalculatorTest {

    private static H2PersistenceCallback h2;
    private DefaultDisplacementCalculator calculator;

    @BeforeAll
    static void initH2() {
        h2 = new H2PersistenceCallback();
    }

    @AfterAll
    static void closeH2() {
        if (h2 != null) {
            h2.close();
        }
    }

    @BeforeEach
    void setUp() {
        h2.clearAll();
        CleanConfig cleanConfig = new CleanConfig();
        DiagnosisConfig diagnosisConfig = new DiagnosisConfig();
        CacheConfig cacheConfig = new CacheConfig();
        PersistenceConfig persistenceConfig = new PersistenceConfig();
        persistenceConfig.enablePersistence = true;
        persistenceConfig.recoveryOnStartup = true;
        calculator = new DefaultDisplacementCalculator(
                cleanConfig, diagnosisConfig, cacheConfig, persistenceConfig, h2);
    }

    // ==================== 假数据生成工具 ====================

    private static final Random rng = new Random(42);

    private static DisplacementResult makeFixResult(double dNorth, double dEast, double dUp) {
        DisplacementResult r = new DisplacementResult();
        r.setdNorth(dNorth);
        r.setdEast(dEast);
        r.setdUp(dUp);
        r.setStatus(SolutionStatus.FIX);
        r.setRatio(5.0);
        r.setRms(0.02);
        r.setPdop(2.0);
        r.setNumSatellites(10);
        r.setTimestamp(Instant.now());
        return r;
    }

    private static DisplacementResult makeFloatResult(double dNorth, double dEast, double dUp) {
        DisplacementResult r = new DisplacementResult();
        r.setdNorth(dNorth);
        r.setdEast(dEast);
        r.setdUp(dUp);
        r.setStatus(SolutionStatus.FLOAT);
        r.setRatio(1.5);
        r.setRms(0.10);
        r.setPdop(5.0);
        r.setNumSatellites(6);
        r.setTimestamp(Instant.now());
        return r;
    }

    private static DisplacementResult makeInvalidResult() {
        DisplacementResult r = new DisplacementResult();
        r.setdNorth(0);
        r.setdEast(0);
        r.setdUp(0);
        r.setStatus(SolutionStatus.INVALID);
        r.setTimestamp(Instant.now());
        return r;
    }

    private static DisplacementResult makeSingleResult() {
        DisplacementResult r = new DisplacementResult();
        r.setdNorth(1.5);
        r.setdEast(2.0);
        r.setdUp(3.0);
        r.setStatus(SolutionStatus.SINGLE);
        r.setTimestamp(Instant.now());
        return r;
    }

    private static double noise(double sigma) {
        return rng.nextGaussian() * sigma;
    }

    // ==================== 格式化打印工具 ====================

    private static void printCleanResult(int seq, CleanResult cr) {
        DisplacementResult r = cr.getResult();
        String passMark = cr.isPassed() ? "PASS" : "FAIL";
        String cleanMark = r.isCleaned() ? "CLEANED" : "RAW";
        String abnMark = cr.isResultAbnormal() ? "ABNORMAL" : "NORMAL";

        System.out.printf("  [#%03d] %s | N=%+.6f E=%+.6f U=%+.6f | %s %s %s | ratio=%.1f rms=%.3f pdop=%.1f sat=%d",
                seq, passMark,
                r.getdNorth(), r.getdEast(), r.getdUp(),
                cleanMark, abnMark, r.getStatus(),
                r.getRatio(), r.getRms(), r.getPdop(), r.getNumSatellites());

        if (cr.isResultAbnormal() || cr.getResultAbnormalReason() != null) {
            System.out.printf(" | reason=%s", cr.getResultAbnormalReason());
        }
        if (!cr.isPassed()) {
            System.out.printf(" | layer=%d fail=%s", cr.getFailureLayer(), cr.getFailureReason());
        }
        if (r.getDiagnosis() != null) {
            DeviceDiagnosis d = r.getDiagnosis();
            System.out.printf(" | diag{health=%s thermal=%s periodic=%s jitter=%s}",
                    d.getHealth(),
                    d.isThermalModeActive() ? "ON" : "OFF",
                    d.isInPeriodicFluctuation() ? "ON" : "OFF",
                    d.isJittering() ? "ON" : "OFF");
        }
        System.out.println();
    }

    private static void printSeparator(String title) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  " + title);
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    private static void printH2Summary(String deviceId) {
        System.out.printf("  [H2] results=%d anomalies=%d snapshot=%s%n",
                h2.countResults(deviceId),
                h2.countAnomalies(deviceId),
                h2.hasSnapshot(deviceId) ? "YES" : "NO");
    }

    // ==================== 第一层：质量门禁 ====================

    @Test
    @Order(1)
    @DisplayName("Layer1: INVALID解应被拒绝")
    void testInvalidSolutionRejected() {
        printSeparator("Layer1: INVALID解应被拒绝");
        DisplacementResult result = makeInvalidResult();
        CleanResult cr = calculator.cleanSingle(result);
        printCleanResult(1, cr);
        assertFalse(cr.isPassed());
        assertEquals(1, cr.getFailureLayer());
    }

    @Test
    @Order(2)
    @DisplayName("Layer1: SINGLE解应被拒绝")
    void testSingleSolutionRejected() {
        printSeparator("Layer1: SINGLE解应被拒绝");
        DisplacementResult result = makeSingleResult();
        CleanResult cr = calculator.cleanSingle(result);
        printCleanResult(1, cr);
        assertFalse(cr.isPassed());
        assertEquals(1, cr.getFailureLayer());
    }

    @Test
    @Order(3)
    @DisplayName("Layer1: 正常FIX解应通过")
    void testFixSolutionPassed() {
        printSeparator("Layer1: 正常FIX解应通过");
        DisplacementResult result = makeFixResult(0.010, 0.005, 0.003);
        CleanResult cr = calculator.cleanSingle(result);
        printCleanResult(1, cr);
        assertTrue(cr.isPassed());
    }

    @Test
    @Order(4)
    @DisplayName("Layer1: FIX解卫星数不足应被拒绝")
    void testFixLowSatellitesRejected() {
        printSeparator("Layer1: FIX解卫星数不足应被拒绝");
        DisplacementResult result = makeFixResult(0.010, 0.005, 0.003);
        result.setNumSatellites(3);
        CleanResult cr = calculator.cleanSingle(result);
        printCleanResult(1, cr);
        assertFalse(cr.isPassed());
        assertEquals(1, cr.getFailureLayer());
    }

    @Test
    @Order(5)
    @DisplayName("Layer1: FIX解Ratio过低应被拒绝")
    void testFixLowRatioRejected() {
        printSeparator("Layer1: FIX解Ratio过低应被拒绝");
        DisplacementResult result = makeFixResult(0.010, 0.005, 0.003);
        result.setRatio(1.5);
        CleanResult cr = calculator.cleanSingle(result);
        printCleanResult(1, cr);
        assertFalse(cr.isPassed());
        assertEquals(1, cr.getFailureLayer());
    }

    @Test
    @Order(6)
    @DisplayName("Layer1: 正常FLOAT解应通过（宽松阈值）")
    void testFloatSolutionPassed() {
        printSeparator("Layer1: 正常FLOAT解应通过（宽松阈值）");
        DisplacementResult result = makeFloatResult(0.010, 0.005, 0.003);
        CleanResult cr = calculator.cleanSingle(result);
        printCleanResult(1, cr);
        assertTrue(cr.isPassed());
    }

    @Test
    @Order(7)
    @DisplayName("Layer1: FLOAT解RMS过高应被拒绝")
    void testFloatHighRmsRejected() {
        printSeparator("Layer1: FLOAT解RMS过高应被拒绝");
        DisplacementResult result = makeFloatResult(0.010, 0.005, 0.003);
        result.setRms(0.5);
        CleanResult cr = calculator.cleanSingle(result);
        printCleanResult(1, cr);
        assertFalse(cr.isPassed());
        assertEquals(1, cr.getFailureLayer());
    }

    // ==================== 第二层：跳变检测 ====================

    @Test
    @Order(10)
    @DisplayName("Layer2: 连续平稳FIX数据应全部通过")
    void testStableSequencePassed() {
        printSeparator("Layer2: 连续平稳FIX数据应全部通过 (25条)");
        String deviceId = "stable-dev";
        double baseN = 0.010, baseE = 0.005, baseU = 0.003;

        for (int i = 0; i < 25; i++) {
            DisplacementResult r = makeFixResult(
                    baseN + noise(0.001),
                    baseE + noise(0.001),
                    baseU + noise(0.001));
            CleanResult cr = calculator.cleanWithHistory(r, deviceId);
            printCleanResult(i + 1, cr);
            assertTrue(cr.isPassed(), "平稳数据第" + (i + 1) + "条应通过");
        }
        printH2Summary(deviceId);
    }

    @Test
    @Order(11)
    @DisplayName("Layer2: 大跳变被检测到，Layer4替换后最终通过")
    void testLargeJumpDetectedAndReplaced() {
        printSeparator("Layer2: 大跳变 → Layer4替换 (25条平稳 + 1条跳变)");
        String deviceId = "jump-dev";
        double baseN = 0.010, baseE = 0.005, baseU = 0.003;

        System.out.println("  --- 平稳基线建立 ---");
        for (int i = 0; i < 25; i++) {
            DisplacementResult r = makeFixResult(baseN, baseE, baseU);
            CleanResult cr = calculator.cleanWithHistory(r, deviceId);
            if (i >= 23) printCleanResult(i + 1, cr);
        }

        System.out.println("  --- 注入跳变 N:0.010→0.200 ---");
        DisplacementResult jump = makeFixResult(0.200, 0.005, 0.003);
        CleanResult cr = calculator.cleanWithHistory(jump, deviceId);
        printCleanResult(26, cr);

        assertTrue(cr.isPassed(), "Layer4替换后应最终通过");
        assertTrue(cr.getResult().isCleaned(), "应标记为已清洗");
        assertEquals("Layer4: replaced outlier", cr.getResult().getAbnormalReason(),
                "异常原因应为Layer4替换");
        printH2Summary(deviceId);
    }

    // ==================== 第三层：统计粗差检测 ====================

    @Test
    @Order(12)
    @DisplayName("Layer3: 窗口建立后粗差被Layer4替换，最终通过")
    void testStatisticalOutlierReplaced() {
        printSeparator("Layer3: 统计粗差 → Layer4替换 (25条平稳 + 1条粗差)");
        String deviceId = "outlier-dev";
        double baseN = 0.010, baseE = 0.005, baseU = 0.003;

        System.out.println("  --- 滑动窗口建立 ---");
        for (int i = 0; i < 25; i++) {
            DisplacementResult r = makeFixResult(
                    baseN + noise(0.002),
                    baseE + noise(0.002),
                    baseU + noise(0.002));
            CleanResult cr = calculator.cleanWithHistory(r, deviceId);
            if (i >= 23) printCleanResult(i + 1, cr);
        }

        System.out.println("  --- 注入粗差 N:0.010→0.060 ---");
        DisplacementResult outlier = makeFixResult(baseN + 0.05, baseE + 0.05, baseU + 0.05);
        CleanResult cr = calculator.cleanWithHistory(outlier, deviceId);
        printCleanResult(26, cr);

        assertTrue(cr.isPassed(), "Layer4替换后应通过");
        assertTrue(cr.getResult().isCleaned());
        printH2Summary(deviceId);
    }

    // ==================== H2持久化回调测试 ====================

    @Test
    @Order(20)
    @DisplayName("H2回调: 每条清洗结果应被持久化到H2")
    void testResultPersistedToH2() {
        printSeparator("H2回调: 清洗结果持久化 (10条FIX)");
        String deviceId = "persist-dev";

        for (int i = 0; i < 10; i++) {
            DisplacementResult r = makeFixResult(0.010 + noise(0.001), 0.005, 0.003);
            CleanResult cr = calculator.cleanWithHistory(r, deviceId);
            printCleanResult(i + 1, cr);
        }

        printH2Summary(deviceId);
        assertEquals(10, h2.countResults(deviceId), "H2中应有10条结果记录");
    }

    @Test
    @Order(21)
    @DisplayName("H2回调: Layer4替换修复后不产生异常事件（异常已修复）")
    void testAnomalyEventNotPersistedWhenReplaced() {
        printSeparator("H2回调: Layer4替换后不产生异常事件");
        String deviceId = "anomaly-persist-dev";
        double baseN = 0.010, baseE = 0.005, baseU = 0.003;

        for (int i = 0; i < 25; i++) {
            DisplacementResult r = makeFixResult(baseN, baseE, baseU);
            calculator.cleanWithHistory(r, deviceId);
        }

        System.out.println("  --- 注入5条跳变 ---");
        for (int i = 0; i < 5; i++) {
            DisplacementResult jump = makeFixResult(baseN + 0.15, baseE, baseU);
            CleanResult cr = calculator.cleanWithHistory(jump, deviceId);
            printCleanResult(26 + i, cr);
        }

        printH2Summary(deviceId);
        assertEquals(0, h2.countAnomalies(deviceId),
                "Layer4替换修复后abnormal=false，不应产生异常事件");
    }

    @Test
    @Order(22)
    @DisplayName("H2回调: 查询最近N条结果应返回正确数量")
    void testQueryRecentResults() {
        printSeparator("H2回调: 查询最近5条结果 (共20条)");
        String deviceId = "query-dev";

        for (int i = 0; i < 20; i++) {
            DisplacementResult r = makeFixResult(0.010 + i * 0.001, 0.005, 0.003);
            calculator.cleanWithHistory(r, deviceId);
        }

        List<DisplacementResult> recent = h2.queryRecentResults(deviceId, 5);
        System.out.println("  --- 最近5条 (时间倒序) ---");
        for (int i = 0; i < recent.size(); i++) {
            DisplacementResult r = recent.get(i);
            System.out.printf("  [%d] N=%+.6f E=%+.6f U=%+.6f status=%s%n",
                    i + 1, r.getdNorth(), r.getdEast(), r.getdUp(), r.getStatus());
        }
        assertEquals(5, recent.size(), "应返回5条最近结果");
    }

    @Test
    @Order(23)
    @DisplayName("H2回调: 时间范围查询应返回正确数据")
    void testQueryTimeRange() {
        printSeparator("H2回调: 时间范围查询");
        String deviceId = "range-dev";
        Instant start = Instant.now().minusSeconds(60);

        for (int i = 0; i < 10; i++) {
            DisplacementResult r = makeFixResult(0.010, 0.005, 0.003);
            calculator.cleanWithHistory(r, deviceId);
        }

        List<DisplacementResult> results = h2.queryTimeRange(
                deviceId, start, Instant.now().plusSeconds(60));
        System.out.printf("  时间范围查询返回 %d 条结果%n", results.size());
        assertEquals(10, results.size(), "时间范围内应有10条结果");
    }

    @Test
    @Order(24)
    @DisplayName("H2回调: 设备状态清除时应先保存快照到H2")
    void testClearDeviceStateSavesSnapshot() {
        printSeparator("H2回调: 清除设备状态 → 保存快照");
        String deviceId = "snapshot-dev";

        for (int i = 0; i < 10; i++) {
            DisplacementResult r = makeFixResult(0.010, 0.005, 0.003);
            calculator.cleanWithHistory(r, deviceId);
        }

        System.out.println("  清除前:");
        printH2Summary(deviceId);

        calculator.clearDeviceState(deviceId);

        System.out.println("  清除后:");
        printH2Summary(deviceId);
        assertTrue(h2.hasSnapshot(deviceId), "清除前应保存快照到H2");
    }

    @Test
    @Order(25)
    @DisplayName("H2回调: 设备状态恢复应从H2加载")
    void testDeviceStateRecoveryFromH2() {
        printSeparator("H2回调: 设备状态恢复 (15条 → 清除 → 恢复)");
        String deviceId = "recovery-dev";

        System.out.println("  --- 建立历史 ---");
        for (int i = 0; i < 15; i++) {
            DisplacementResult r = makeFixResult(0.010 + i * 0.001, 0.005, 0.003);
            CleanResult cr = calculator.cleanWithHistory(r, deviceId);
            if (i >= 13) printCleanResult(i + 1, cr);
        }

        calculator.clearDeviceState(deviceId);
        assertTrue(h2.hasSnapshot(deviceId));
        System.out.println("  清除设备状态，快照已保存到H2");

        calculator.clearAllDeviceState();
        assertEquals(0, calculator.getDeviceCount());
        System.out.println("  清除所有内存缓存");

        System.out.println("  --- 恢复后新数据 ---");
        DisplacementResult r = makeFixResult(0.025, 0.005, 0.003);
        CleanResult cr = calculator.cleanWithHistory(r, deviceId);
        printCleanResult(1, cr);
        assertTrue(cr.isPassed(), "恢复后应正常清洗");
    }

    @Test
    @Order(26)
    @DisplayName("H2回调: hasHistory应正确反映历史数据存在性")
    void testHasHistory() {
        printSeparator("H2回调: hasHistory检查");
        String deviceId = "history-dev";
        System.out.printf("  清洗前: hasHistory=%s%n", h2.hasHistory(deviceId));
        assertFalse(h2.hasHistory(deviceId), "无数据时应返回false");

        DisplacementResult r = makeFixResult(0.010, 0.005, 0.003);
        calculator.cleanWithHistory(r, deviceId);
        System.out.printf("  清洗后: hasHistory=%s%n", h2.hasHistory(deviceId));
        assertTrue(h2.hasHistory(deviceId), "有数据后应返回true");
    }

    @Test
    @Order(27)
    @DisplayName("H2回调: deleteDeviceHistory应清除所有历史")
    void testDeleteDeviceHistory() {
        printSeparator("H2回调: deleteDeviceHistory");
        String deviceId = "delete-dev";

        for (int i = 0; i < 5; i++) {
            DisplacementResult r = makeFixResult(0.010, 0.005, 0.003);
            calculator.cleanWithHistory(r, deviceId);
        }
        System.out.printf("  删除前: hasHistory=%s count=%d%n",
                h2.hasHistory(deviceId), h2.countResults(deviceId));
        assertTrue(h2.hasHistory(deviceId));

        h2.deleteDeviceHistory(deviceId);
        System.out.printf("  删除后: hasHistory=%s count=%d%n",
                h2.hasHistory(deviceId), h2.countResults(deviceId));
        assertFalse(h2.hasHistory(deviceId), "删除后应无历史");
    }

    @Test
    @Order(28)
    @DisplayName("H2回调: 异常事件直接存取应正确工作")
    void testAnomalyEventDirectPersistence() {
        printSeparator("H2回调: 异常事件直接存取");
        String deviceId = "anomaly-direct-dev";

        AnomalyEvent event = new AnomalyEvent();
        event.setDeviceId(deviceId);
        event.setEventId("EVT-001");
        event.setEventType("LAYER2_JUMP");
        event.setDescription("Layer2: jump N=0.1500 E=0.0000 U=0.0000");
        event.setDataId("DATA-025");
        event.setEventTime(Instant.now());
        event.setLayer(2);
        event.setJumpMagnitude(0.15);

        h2.saveAnomalyEvent(deviceId, event);
        System.out.printf("  写入异常事件: id=%s type=%s layer=%d jump=%.3fm%n",
                event.getEventId(), event.getEventType(), event.getLayer(), event.getJumpMagnitude());

        List<AnomalyEvent> anomalies = h2.queryRecentAnomalies(deviceId, 10);
        System.out.printf("  查询返回 %d 条异常事件%n", anomalies.size());
        for (AnomalyEvent a : anomalies) {
            System.out.printf("    id=%s type=%s layer=%d jump=%.3fm desc=%s%n",
                    a.getEventId(), a.getEventType(), a.getLayer(), a.getJumpMagnitude(), a.getDescription());
        }
        assertEquals(1, anomalies.size(), "应有1条异常事件");
        assertEquals("EVT-001", anomalies.get(0).getEventId());
        assertEquals(2, anomalies.get(0).getLayer());
        assertEquals(0.15, anomalies.get(0).getJumpMagnitude(), 0.001);
    }

    // ==================== 多设备隔离测试 ====================

    @Test
    @Order(30)
    @DisplayName("多设备状态应互相隔离，H2分别记录")
    void testMultiDeviceIsolation() {
        printSeparator("多设备隔离 (device-A + device-B)");
        String deviceA = "device-A";
        String deviceB = "device-B";

        System.out.println("  --- device-A (N=0.010) + device-B (N=0.050) ---");
        for (int i = 0; i < 10; i++) {
            DisplacementResult rA = makeFixResult(0.010, 0.005, 0.003);
            CleanResult crA = calculator.cleanWithHistory(rA, deviceA);

            DisplacementResult rB = makeFixResult(0.050, 0.020, 0.010);
            CleanResult crB = calculator.cleanWithHistory(rB, deviceB);

            if (i >= 8) {
                printCleanResult(i + 1, crA);
                System.out.printf("    ");
                printCleanResult(i + 1, crB);
            }
        }

        System.out.println("  --- H2统计 ---");
        System.out.printf("  device-A: results=%d%n", h2.countResults(deviceA));
        System.out.printf("  device-B: results=%d%n", h2.countResults(deviceB));

        assertEquals(2, calculator.getDeviceCount());
        assertEquals(10, h2.countResults(deviceA));
        assertEquals(10, h2.countResults(deviceB));

        calculator.clearDeviceState(deviceA);
        assertEquals(1, calculator.getDeviceCount());
        System.out.printf("  清除A后: deviceCount=%d%n", calculator.getDeviceCount());

        calculator.clearAllDeviceState();
        assertEquals(0, calculator.getDeviceCount());
        System.out.printf("  清除全部后: deviceCount=%d%n", calculator.getDeviceCount());
    }

    // ==================== 气象元数据测试 ====================

    @Test
    @Order(40)
    @DisplayName("带气象元数据的清洗应正常工作")
    void testCleanWithMeta() {
        printSeparator("气象元数据清洗 (温度25℃ 气压1013hPa 湿度60%)");
        String deviceId = "meta-dev";

        DisplacementResult result = makeFixResult(0.010, 0.005, 0.003);
        GnssMetaData meta = new GnssMetaData(25.0, 1013.0, 60.0);
        System.out.printf("  输入: N=0.010 E=0.005 U=0.003 | 温度=%.1f℃ 气压=%.1fhPa 湿度=%.1f%%%n",
                meta.getTemperature(), meta.getPressure(), meta.getHumidity());

        CleanResult cr = calculator.cleanWithHistoryAndMeta(result, deviceId, meta);
        printCleanResult(1, cr);
        assertTrue(cr.isPassed());
    }

    @Test
    @Order(41)
    @DisplayName("null气象元数据不应影响清洗")
    void testCleanWithNullMeta() {
        printSeparator("null气象元数据清洗");
        String deviceId = "null-meta-dev";

        DisplacementResult result = makeFixResult(0.010, 0.005, 0.003);
        CleanResult cr = calculator.cleanWithHistoryAndMeta(result, deviceId, null);
        printCleanResult(1, cr);
        assertTrue(cr.isPassed());
    }

    // ==================== 重置测试 ====================

    @Test
    @Order(50)
    @DisplayName("reset应清除所有设备状态")
    void testReset() {
        printSeparator("reset清除所有设备状态");
        String deviceId = "reset-dev";

        DisplacementResult r = makeFixResult(0.010, 0.005, 0.003);
        calculator.cleanWithHistory(r, deviceId);
        System.out.printf("  清洗后: deviceCount=%d%n", calculator.getDeviceCount());
        assertEquals(1, calculator.getDeviceCount());

        calculator.reset();
        System.out.printf("  reset后: deviceCount=%d%n", calculator.getDeviceCount());
        assertEquals(0, calculator.getDeviceCount());
    }

    // ==================== 批量假数据压力测试 ====================

    @Test
    @Order(60)
    @DisplayName("1000条平稳假数据应全部通过清洗，H2记录完整")
    void testBulkStableData() {
        printSeparator("批量压力测试: 1000条平稳FIX数据");
        String deviceId = "bulk-dev";
        double baseN = 0.010, baseE = 0.005, baseU = 0.003;
        int passed = 0;
        int total = 1000;

        for (int i = 0; i < total; i++) {
            DisplacementResult r = makeFixResult(
                    baseN + noise(0.002),
                    baseE + noise(0.002),
                    baseU + noise(0.002));
            CleanResult cr = calculator.cleanWithHistory(r, deviceId);
            if (cr.isPassed()) {
                passed++;
            }
            if (i < 5 || i >= total - 5) {
                printCleanResult(i + 1, cr);
            } else if (i == 5) {
                System.out.println("  ... (中间省略) ...");
            }
        }

        double passRate = (double) passed / total;
        System.out.printf("%n  === 统计 ===%n");
        System.out.printf("  总数: %d | 通过: %d | 失败: %d | 通过率: %.1f%%%n",
                total, passed, total - passed, passRate * 100);
        printH2Summary(deviceId);

        assertTrue(passRate > 0.90,
                "平稳数据通过率应>90%，实际: " + String.format("%.1f%%", passRate * 100));
        assertEquals(total, h2.countResults(deviceId), "H2中应有1000条记录");
    }

    @Test
    @Order(61)
    @DisplayName("注入异常数据后Layer4替换率应合理")
    void testAnomalyReplacementRate() {
        printSeparator("异常注入测试: 30条平稳 + 20条异常");
        String deviceId = "anomaly-rate-dev";
        double baseN = 0.010, baseE = 0.005, baseU = 0.003;

        System.out.println("  --- 平稳基线 (30条) ---");
        for (int i = 0; i < 30; i++) {
            DisplacementResult r = makeFixResult(
                    baseN + noise(0.002),
                    baseE + noise(0.002),
                    baseU + noise(0.002));
            calculator.cleanWithHistory(r, deviceId);
        }

        int replaced = 0;
        int injected = 20;

        System.out.println("  --- 注入异常 (20条 N+=0.10) ---");
        for (int i = 0; i < injected; i++) {
            DisplacementResult outlier = makeFixResult(
                    baseN + 0.10 + noise(0.01),
                    baseE + 0.10 + noise(0.01),
                    baseU + 0.10 + noise(0.01));
            CleanResult cr = calculator.cleanWithHistory(outlier, deviceId);
            printCleanResult(i + 1, cr);
            if (cr.isPassed() && cr.getResult().isCleaned()
                    && "Layer4: replaced outlier".equals(cr.getResult().getAbnormalReason())) {
                replaced++;
            }
        }

        double replacementRate = (double) replaced / injected;
        System.out.printf("%n  === 统计 ===%n");
        System.out.printf("  注入: %d | Layer4替换: %d | 替换率: %.1f%%%n",
                injected, replaced, replacementRate * 100);
        printH2Summary(deviceId);

        assertTrue(replacementRate > 0.3,
                "Layer4替换率应>30%，实际: " + String.format("%.1f%%", replacementRate * 100));
    }

    // ==================== H2回调: 修正记录测试 ====================

    @Test
    @Order(70)
    @DisplayName("H2回调: 修正记录的存取应完整")
    void testCorrectionRecordPersistence() {
        printSeparator("H2回调: 修正记录存取");
        String deviceId = "correction-dev";

        DataCorrectionRecord record = new DataCorrectionRecord();
        record.setCorrectionId("CORR-001");
        record.setDeviceId(deviceId);
        record.setFromDataId("DATA-001");
        record.setToDataId("DATA-010");
        record.setFromTime(Instant.now().minusSeconds(600));
        record.setToTime(Instant.now());
        record.setType(CorrectionType.TREND_MISJUDGE);
        record.setOriginalDiagnosis("异常跳变");
        record.setCorrectedDiagnosis("真实形变");
        record.setCorrectionReason("趋势快速通道确认");
        record.setStatus(CorrectionStatus.APPLIED);
        record.setCreatedAt(Instant.now());
        record.setAppliedAt(Instant.now());

        DataCorrectionItem item = new DataCorrectionItem();
        item.setDataId("DATA-005");
        item.setOriginalNorth(0.200);
        item.setOriginalEast(0.005);
        item.setOriginalUp(0.003);
        item.setCleanedNorth(0.010);
        item.setCleanedEast(0.005);
        item.setCleanedUp(0.003);
        item.setCorrectedNorth(0.015);
        item.setCorrectedEast(0.005);
        item.setCorrectedUp(0.003);
        item.setReason("Layer4替换后修正");
        record.getCorrections().add(item);

        h2.saveCorrectionRecord(deviceId, record);
        System.out.printf("  写入修正记录: id=%s type=%s%n", record.getCorrectionId(), record.getType());
        System.out.printf("    范围: %s → %s%n", record.getFromDataId(), record.getToDataId());
        System.out.printf("    诊断: %s → %s (%s)%n", record.getOriginalDiagnosis(),
                record.getCorrectedDiagnosis(), record.getCorrectionReason());
        System.out.printf("    修正项: DATA-005 N: 0.200→0.010→0.015%n");

        List<DataCorrectionRecord> records = h2.queryCorrectionRecords(deviceId);
        System.out.printf("  查询返回 %d 条修正记录%n", records.size());
        assertEquals(1, records.size(), "应有1条修正记录");
        assertEquals("CORR-001", records.get(0).getCorrectionId());
        assertEquals(CorrectionType.TREND_MISJUDGE, records.get(0).getType());
    }

    // ==================== H2回调: 诊断记录测试 ====================

    @Test
    @Order(71)
    @DisplayName("H2回调: 诊断记录的存取应完整")
    void testDiagnosisPersistence() {
        printSeparator("H2回调: 诊断记录存取");
        String deviceId = "diag-dev";

        DeviceDiagnosis diag = new DeviceDiagnosis();
        diag.setDeviceType(DeviceType.GNSS_MONITOR);
        diag.setHasPeriodicAnomaly(false);
        diag.setPeriodicPattern(PeriodicPattern.NONE);
        diag.setJittering(false);
        diag.setJitterRange(0);
        diag.setJitterDuration(0);
        diag.setHealth(HealthStatus.HEALTHY);
        diag.setThermalModeActive(false);
        diag.setThermalCorrelation(0);
        diag.setInPeriodicFluctuation(false);
        diag.setPeriodicStartMinute(-1);
        diag.setPeriodicEndMinute(-1);
        diag.setFeedbackTimestamp(System.currentTimeMillis());

        h2.saveDiagnosis(deviceId, diag);
        System.out.printf("  写入诊断: type=%s health=%s thermal=%s periodic=%s jitter=%s%n",
                diag.getDeviceType(), diag.getHealth(),
                diag.isThermalModeActive(), diag.isInPeriodicFluctuation(), diag.isJittering());

        DeviceDiagnosis loaded = h2.queryLatestDiagnosis(deviceId);
        System.out.printf("  读取诊断: type=%s health=%s%n", loaded.getDeviceType(), loaded.getHealth());
        assertNotNull(loaded, "应能加载诊断记录");
        assertEquals(DeviceType.GNSS_MONITOR, loaded.getDeviceType());
        assertEquals(HealthStatus.HEALTHY, loaded.getHealth());
    }

    // ==================== H2回调: queryCorrectedData测试 ====================

    @Test
    @Order(72)
    @DisplayName("H2回调: 按dataId查询修正数据应返回正确结果")
    void testQueryCorrectedData() {
        printSeparator("H2回调: 按dataId查询修正数据");
        String deviceId = "corrected-dev";

        DisplacementResult r = makeFixResult(0.010, 0.005, 0.003);
        CleanResult cr = calculator.cleanWithHistory(r, deviceId);
        printCleanResult(1, cr);

        String dataId = r.getDataId();
        assertNotNull(dataId, "应自动生成dataId");
        System.out.printf("  自动生成dataId: %s%n", dataId);

        DisplacementResult loaded = h2.queryCorrectedData(deviceId, dataId);
        assertNotNull(loaded, "应能按dataId查询到结果");
        System.out.printf("  H2查询结果: N=%+.6f E=%+.6f U=%+.6f status=%s cleaned=%s%n",
                loaded.getdNorth(), loaded.getdEast(), loaded.getdUp(),
                loaded.getStatus(), loaded.isCleaned());
        assertEquals(0.010, loaded.getdNorth(), 0.001);
    }
}
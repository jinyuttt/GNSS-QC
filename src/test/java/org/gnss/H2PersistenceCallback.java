package org.gnss;

import org.gnss.model.*;
import org.gnss.persistence.PersistenceCallback;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class H2PersistenceCallback implements PersistenceCallback, AutoCloseable {

    private final Connection conn;

    public H2PersistenceCallback() {
        try {
            conn = DriverManager.getConnection("jdbc:h2:mem:gnss_qc_test;DB_CLOSE_DELAY=-1");
            conn.setAutoCommit(true);
            createTables();
        } catch (SQLException e) {
            throw new RuntimeException("H2 init failed", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS displacement_result ("
                    + "device_id VARCHAR(128),"
                    + "data_id VARCHAR(128),"
                    + "epoch_millis BIGINT,"
                    + "d_north DOUBLE,"
                    + "d_east DOUBLE,"
                    + "d_up DOUBLE,"
                    + "status VARCHAR(32),"
                    + "ratio DOUBLE,"
                    + "rms DOUBLE,"
                    + "pdop DOUBLE,"
                    + "num_satellites INT,"
                    + "is_cleaned BOOLEAN,"
                    + "is_abnormal BOOLEAN,"
                    + "abnormal_reason VARCHAR(512),"
                    + "is_downgraded BOOLEAN)");
            st.execute("CREATE TABLE IF NOT EXISTS device_state_snapshot ("
                    + "device_id VARCHAR(128) PRIMARY KEY,"
                    + "snapshot_millis BIGINT,"
                    + "last_valid_north DOUBLE,"
                    + "last_valid_east DOUBLE,"
                    + "last_valid_up DOUBLE,"
                    + "fast_baseline_north DOUBLE,"
                    + "fast_baseline_east DOUBLE,"
                    + "fast_baseline_up DOUBLE,"
                    + "slow_baseline_north DOUBLE,"
                    + "slow_baseline_east DOUBLE,"
                    + "slow_baseline_up DOUBLE,"
                    + "last_temperature DOUBLE,"
                    + "dense_mode BOOLEAN,"
                    + "dense_counter INT,"
                    + "step_observation_count INT,"
                    + "step_candidate_north DOUBLE,"
                    + "step_candidate_east DOUBLE,"
                    + "step_candidate_up DOUBLE,"
                    + "step_start_time BIGINT,"
                    + "initial_baseline_established BOOLEAN,"
                    + "initial_baseline_north DOUBLE,"
                    + "initial_baseline_east DOUBLE,"
                    + "initial_baseline_up DOUBLE,"
                    + "initial_baseline_count INT)");
            st.execute("CREATE TABLE IF NOT EXISTS anomaly_event ("
                    + "device_id VARCHAR(128),"
                    + "event_id VARCHAR(128),"
                    + "event_type VARCHAR(64),"
                    + "description VARCHAR(1024),"
                    + "data_id VARCHAR(128),"
                    + "event_millis BIGINT,"
                    + "layer INT,"
                    + "jump_magnitude DOUBLE)");
            st.execute("CREATE TABLE IF NOT EXISTS device_diagnosis ("
                    + "device_id VARCHAR(128),"
                    + "device_type VARCHAR(64),"
                    + "has_periodic_anomaly BOOLEAN,"
                    + "periodic_pattern VARCHAR(64),"
                    + "is_jittering BOOLEAN,"
                    + "jitter_range DOUBLE,"
                    + "jitter_duration DOUBLE,"
                    + "health VARCHAR(32),"
                    + "thermal_mode_active BOOLEAN,"
                    + "thermal_correlation DOUBLE,"
                    + "in_periodic_fluctuation BOOLEAN,"
                    + "periodic_start_minute INT,"
                    + "periodic_end_minute INT,"
                    + "feedback_millis BIGINT)");
            st.execute("CREATE TABLE IF NOT EXISTS data_correction_record ("
                    + "correction_id VARCHAR(128),"
                    + "device_id VARCHAR(128),"
                    + "from_data_id VARCHAR(128),"
                    + "to_data_id VARCHAR(128),"
                    + "from_millis BIGINT,"
                    + "to_millis BIGINT,"
                    + "correction_type VARCHAR(64),"
                    + "original_diagnosis VARCHAR(512),"
                    + "corrected_diagnosis VARCHAR(512),"
                    + "correction_reason VARCHAR(1024),"
                    + "status VARCHAR(32),"
                    + "created_millis BIGINT,"
                    + "applied_millis BIGINT)");
            st.execute("CREATE TABLE IF NOT EXISTS data_correction_item ("
                    + "correction_id VARCHAR(128),"
                    + "data_id VARCHAR(128),"
                    + "original_north DOUBLE,"
                    + "original_east DOUBLE,"
                    + "original_up DOUBLE,"
                    + "cleaned_north DOUBLE,"
                    + "cleaned_east DOUBLE,"
                    + "cleaned_up DOUBLE,"
                    + "corrected_north DOUBLE,"
                    + "corrected_east DOUBLE,"
                    + "corrected_up DOUBLE,"
                    + "reason VARCHAR(512))");
        }
    }

    void clearAll() {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM displacement_result");
            st.execute("DELETE FROM device_state_snapshot");
            st.execute("DELETE FROM anomaly_event");
            st.execute("DELETE FROM device_diagnosis");
            st.execute("DELETE FROM data_correction_record");
            st.execute("DELETE FROM data_correction_item");
        } catch (SQLException ignored) {
        }
    }

    int countResults(String deviceId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM displacement_result WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    int countAnomalies(String deviceId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM anomaly_event WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    boolean hasSnapshot(String deviceId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM device_state_snapshot WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void saveResult(String deviceId, DisplacementResult result) {
        String sql = "INSERT INTO displacement_result "
                + "(device_id, data_id, epoch_millis, d_north, d_east, d_up, "
                + "status, ratio, rms, pdop, num_satellites, is_cleaned, is_abnormal, "
                + "abnormal_reason, is_downgraded) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, result.getDataId());
            ps.setLong(3, result.getTimestamp() != null ? result.getTimestamp().toEpochMilli() : 0);
            ps.setDouble(4, result.getdNorth());
            ps.setDouble(5, result.getdEast());
            ps.setDouble(6, result.getdUp());
            ps.setString(7, result.getStatus() != null ? result.getStatus().name() : "INVALID");
            ps.setDouble(8, result.getRatio());
            ps.setDouble(9, result.getRms());
            ps.setDouble(10, result.getPdop());
            ps.setInt(11, result.getNumSatellites());
            ps.setBoolean(12, result.isCleaned());
            ps.setBoolean(13, result.isAbnormal());
            ps.setString(14, result.getAbnormalReason());
            ps.setBoolean(15, result.isDowngraded());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("saveResult failed", e);
        }
    }

    @Override
    public List<DisplacementResult> queryRecentResults(String deviceId, int count) {
        String sql = "SELECT * FROM displacement_result WHERE device_id = ? ORDER BY epoch_millis DESC LIMIT ?";
        List<DisplacementResult> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setInt(2, count);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResult(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("queryRecentResults failed", e);
        }
        return results;
    }

    @Override
    public List<DisplacementResult> queryTimeRange(String deviceId, Instant start, Instant end) {
        String sql = "SELECT * FROM displacement_result "
                + "WHERE device_id = ? AND epoch_millis >= ? AND epoch_millis <= ? ORDER BY epoch_millis ASC";
        List<DisplacementResult> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setLong(2, start.toEpochMilli());
            ps.setLong(3, end.toEpochMilli());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapResult(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("queryTimeRange failed", e);
        }
        return results;
    }

    @Override
    public void saveDeviceState(String deviceId, DeviceStateSnapshot snapshot) {
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM device_state_snapshot WHERE device_id = ?")) {
            del.setString(1, deviceId);
            del.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("delete snapshot failed", e);
        }

        String sql = "INSERT INTO device_state_snapshot "
                + "(device_id, snapshot_millis, last_valid_north, last_valid_east, last_valid_up, "
                + "fast_baseline_north, fast_baseline_east, fast_baseline_up, "
                + "slow_baseline_north, slow_baseline_east, slow_baseline_up, "
                + "last_temperature, dense_mode, dense_counter, "
                + "step_observation_count, step_candidate_north, step_candidate_east, step_candidate_up, "
                + "step_start_time, initial_baseline_established, initial_baseline_north, "
                + "initial_baseline_east, initial_baseline_up, initial_baseline_count) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setLong(2, snapshot.getSnapshotTime() != null ? snapshot.getSnapshotTime().toEpochMilli() : 0);
            ps.setDouble(3, snapshot.getLastValidNorth());
            ps.setDouble(4, snapshot.getLastValidEast());
            ps.setDouble(5, snapshot.getLastValidUp());
            ps.setDouble(6, snapshot.getFastBaselineNorth());
            ps.setDouble(7, snapshot.getFastBaselineEast());
            ps.setDouble(8, snapshot.getFastBaselineUp());
            ps.setDouble(9, snapshot.getSlowBaselineNorth());
            ps.setDouble(10, snapshot.getSlowBaselineEast());
            ps.setDouble(11, snapshot.getSlowBaselineUp());
            ps.setDouble(12, snapshot.getLastTemperature());
            ps.setBoolean(13, snapshot.isDenseMode());
            ps.setInt(14, snapshot.getDenseCounter());
            ps.setInt(15, snapshot.getStepObservationCount());
            ps.setDouble(16, snapshot.getStepCandidateNorth());
            ps.setDouble(17, snapshot.getStepCandidateEast());
            ps.setDouble(18, snapshot.getStepCandidateUp());
            ps.setLong(19, snapshot.getStepStartTime());
            ps.setBoolean(20, snapshot.isInitialBaselineEstablished());
            ps.setDouble(21, snapshot.getInitialBaselineNorth());
            ps.setDouble(22, snapshot.getInitialBaselineEast());
            ps.setDouble(23, snapshot.getInitialBaselineUp());
            ps.setInt(24, snapshot.getInitialBaselineCount());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("saveDeviceState failed", e);
        }
    }

    @Override
    public DeviceStateSnapshot loadDeviceState(String deviceId) {
        String sql = "SELECT * FROM device_state_snapshot WHERE device_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    DeviceStateSnapshot s = new DeviceStateSnapshot();
                    s.setDeviceId(rs.getString("device_id"));
                    s.setSnapshotTime(Instant.ofEpochMilli(rs.getLong("snapshot_millis")));
                    s.setLastValidNorth(rs.getDouble("last_valid_north"));
                    s.setLastValidEast(rs.getDouble("last_valid_east"));
                    s.setLastValidUp(rs.getDouble("last_valid_up"));
                    s.setFastBaselineNorth(rs.getDouble("fast_baseline_north"));
                    s.setFastBaselineEast(rs.getDouble("fast_baseline_east"));
                    s.setFastBaselineUp(rs.getDouble("fast_baseline_up"));
                    s.setSlowBaselineNorth(rs.getDouble("slow_baseline_north"));
                    s.setSlowBaselineEast(rs.getDouble("slow_baseline_east"));
                    s.setSlowBaselineUp(rs.getDouble("slow_baseline_up"));
                    s.setLastTemperature(rs.getDouble("last_temperature"));
                    s.setDenseMode(rs.getBoolean("dense_mode"));
                    s.setDenseCounter(rs.getInt("dense_counter"));
                    s.setStepObservationCount(rs.getInt("step_observation_count"));
                    s.setStepCandidateNorth(rs.getDouble("step_candidate_north"));
                    s.setStepCandidateEast(rs.getDouble("step_candidate_east"));
                    s.setStepCandidateUp(rs.getDouble("step_candidate_up"));
                    s.setStepStartTime(rs.getLong("step_start_time"));
                    s.setInitialBaselineEstablished(rs.getBoolean("initial_baseline_established"));
                    s.setInitialBaselineNorth(rs.getDouble("initial_baseline_north"));
                    s.setInitialBaselineEast(rs.getDouble("initial_baseline_east"));
                    s.setInitialBaselineUp(rs.getDouble("initial_baseline_up"));
                    s.setInitialBaselineCount(rs.getInt("initial_baseline_count"));
                    return s;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("loadDeviceState failed", e);
        }
        return null;
    }

    @Override
    public boolean hasHistory(String deviceId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM displacement_result WHERE device_id = ?")) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void saveAnomalyEvent(String deviceId, AnomalyEvent event) {
        String sql = "INSERT INTO anomaly_event "
                + "(device_id, event_id, event_type, description, data_id, event_millis, layer, jump_magnitude) "
                + "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, event.getEventId());
            ps.setString(3, event.getEventType());
            ps.setString(4, event.getDescription());
            ps.setString(5, event.getDataId());
            ps.setLong(6, event.getEventTime() != null ? event.getEventTime().toEpochMilli() : 0);
            ps.setInt(7, event.getLayer());
            ps.setDouble(8, event.getJumpMagnitude());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("saveAnomalyEvent failed", e);
        }
    }

    @Override
    public List<AnomalyEvent> queryRecentAnomalies(String deviceId, int count) {
        String sql = "SELECT * FROM anomaly_event WHERE device_id = ? ORDER BY event_millis DESC LIMIT ?";
        List<AnomalyEvent> events = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setInt(2, count);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AnomalyEvent e = new AnomalyEvent();
                    e.setDeviceId(rs.getString("device_id"));
                    e.setEventId(rs.getString("event_id"));
                    e.setEventType(rs.getString("event_type"));
                    e.setDescription(rs.getString("description"));
                    e.setDataId(rs.getString("data_id"));
                    e.setEventTime(Instant.ofEpochMilli(rs.getLong("event_millis")));
                    e.setLayer(rs.getInt("layer"));
                    e.setJumpMagnitude(rs.getDouble("jump_magnitude"));
                    events.add(e);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException("queryRecentAnomalies failed", ex);
        }
        return events;
    }

    @Override
    public void saveDiagnosis(String deviceId, DeviceDiagnosis diagnosis) {
        String sql = "INSERT INTO device_diagnosis "
                + "(device_id, device_type, has_periodic_anomaly, periodic_pattern, is_jittering, "
                + "jitter_range, jitter_duration, health, thermal_mode_active, thermal_correlation, "
                + "in_periodic_fluctuation, periodic_start_minute, periodic_end_minute, feedback_millis) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, diagnosis.getDeviceType() != null ? diagnosis.getDeviceType().name() : "GNSS_MONITOR");
            ps.setBoolean(3, diagnosis.isHasPeriodicAnomaly());
            ps.setString(4, diagnosis.getPeriodicPattern() != null ? diagnosis.getPeriodicPattern().name() : "NONE");
            ps.setBoolean(5, diagnosis.isJittering());
            ps.setDouble(6, diagnosis.getJitterRange());
            ps.setDouble(7, diagnosis.getJitterDuration());
            ps.setString(8, diagnosis.getHealth() != null ? diagnosis.getHealth().name() : "HEALTHY");
            ps.setBoolean(9, diagnosis.isThermalModeActive());
            ps.setDouble(10, diagnosis.getThermalCorrelation());
            ps.setBoolean(11, diagnosis.isInPeriodicFluctuation());
            ps.setInt(12, diagnosis.getPeriodicStartMinute());
            ps.setInt(13, diagnosis.getPeriodicEndMinute());
            ps.setLong(14, diagnosis.getFeedbackTimestamp());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("saveDiagnosis failed", e);
        }
    }

    @Override
    public DeviceDiagnosis queryLatestDiagnosis(String deviceId) {
        String sql = "SELECT * FROM device_diagnosis WHERE device_id = ? ORDER BY feedback_millis DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    DeviceDiagnosis d = new DeviceDiagnosis();
                    d.setDeviceType(DeviceType.valueOf(rs.getString("device_type")));
                    d.setHasPeriodicAnomaly(rs.getBoolean("has_periodic_anomaly"));
                    d.setPeriodicPattern(PeriodicPattern.valueOf(rs.getString("periodic_pattern")));
                    d.setJittering(rs.getBoolean("is_jittering"));
                    d.setJitterRange(rs.getDouble("jitter_range"));
                    d.setJitterDuration(rs.getDouble("jitter_duration"));
                    d.setHealth(HealthStatus.valueOf(rs.getString("health")));
                    d.setThermalModeActive(rs.getBoolean("thermal_mode_active"));
                    d.setThermalCorrelation(rs.getDouble("thermal_correlation"));
                    d.setInPeriodicFluctuation(rs.getBoolean("in_periodic_fluctuation"));
                    d.setPeriodicStartMinute(rs.getInt("periodic_start_minute"));
                    d.setPeriodicEndMinute(rs.getInt("periodic_end_minute"));
                    d.setFeedbackTimestamp(rs.getLong("feedback_millis"));
                    return d;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("queryLatestDiagnosis failed", e);
        }
        return null;
    }

    @Override
    public void saveCorrectionRecord(String deviceId, DataCorrectionRecord record) {
        String recSql = "INSERT INTO data_correction_record "
                + "(correction_id, device_id, from_data_id, to_data_id, from_millis, to_millis, "
                + "correction_type, original_diagnosis, corrected_diagnosis, correction_reason, "
                + "status, created_millis, applied_millis) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(recSql)) {
            ps.setString(1, record.getCorrectionId());
            ps.setString(2, deviceId);
            ps.setString(3, record.getFromDataId());
            ps.setString(4, record.getToDataId());
            ps.setLong(5, record.getFromTime() != null ? record.getFromTime().toEpochMilli() : 0);
            ps.setLong(6, record.getToTime() != null ? record.getToTime().toEpochMilli() : 0);
            ps.setString(7, record.getType() != null ? record.getType().name() : "TREND_MISJUDGE");
            ps.setString(8, record.getOriginalDiagnosis());
            ps.setString(9, record.getCorrectedDiagnosis());
            ps.setString(10, record.getCorrectionReason());
            ps.setString(11, record.getStatus() != null ? record.getStatus().name() : "APPLIED");
            ps.setLong(12, record.getCreatedAt() != null ? record.getCreatedAt().toEpochMilli() : 0);
            ps.setLong(13, record.getAppliedAt() != null ? record.getAppliedAt().toEpochMilli() : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("saveCorrectionRecord failed", e);
        }

        String itemSql = "INSERT INTO data_correction_item "
                + "(correction_id, data_id, original_north, original_east, original_up, "
                + "cleaned_north, cleaned_east, cleaned_up, "
                + "corrected_north, corrected_east, corrected_up, reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(itemSql)) {
            for (DataCorrectionItem item : record.getCorrections()) {
                ps.setString(1, record.getCorrectionId());
                ps.setString(2, item.getDataId());
                ps.setDouble(3, item.getOriginalNorth());
                ps.setDouble(4, item.getOriginalEast());
                ps.setDouble(5, item.getOriginalUp());
                ps.setDouble(6, item.getCleanedNorth());
                ps.setDouble(7, item.getCleanedEast());
                ps.setDouble(8, item.getCleanedUp());
                ps.setDouble(9, item.getCorrectedNorth());
                ps.setDouble(10, item.getCorrectedEast());
                ps.setDouble(11, item.getCorrectedUp());
                ps.setString(12, item.getReason());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("saveCorrectionItem failed", e);
        }
    }

    @Override
    public List<DataCorrectionRecord> queryCorrectionRecords(String deviceId) {
        String sql = "SELECT * FROM data_correction_record WHERE device_id = ? ORDER BY created_millis DESC";
        List<DataCorrectionRecord> records = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DataCorrectionRecord r = new DataCorrectionRecord();
                    r.setCorrectionId(rs.getString("correction_id"));
                    r.setDeviceId(deviceId);
                    r.setFromDataId(rs.getString("from_data_id"));
                    r.setToDataId(rs.getString("to_data_id"));
                    r.setFromTime(Instant.ofEpochMilli(rs.getLong("from_millis")));
                    r.setToTime(Instant.ofEpochMilli(rs.getLong("to_millis")));
                    r.setType(CorrectionType.valueOf(rs.getString("correction_type")));
                    r.setOriginalDiagnosis(rs.getString("original_diagnosis"));
                    r.setCorrectedDiagnosis(rs.getString("corrected_diagnosis"));
                    r.setCorrectionReason(rs.getString("correction_reason"));
                    r.setStatus(CorrectionStatus.valueOf(rs.getString("status")));
                    r.setCreatedAt(Instant.ofEpochMilli(rs.getLong("created_millis")));
                    r.setAppliedAt(Instant.ofEpochMilli(rs.getLong("applied_millis")));
                    records.add(r);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("queryCorrectionRecords failed", e);
        }
        return records;
    }

    @Override
    public DisplacementResult queryCorrectedData(String deviceId, String dataId) {
        String sql = "SELECT * FROM displacement_result WHERE device_id = ? AND data_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, deviceId);
            ps.setString(2, dataId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapResult(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("queryCorrectedData failed", e);
        }
        return null;
    }

    @Override
    public void deleteDeviceHistory(String deviceId) {
        String[] tables = {"displacement_result", "device_state_snapshot", "anomaly_event", "device_diagnosis"};
        for (String table : tables) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + table + " WHERE device_id = ?")) {
                ps.setString(1, deviceId);
                ps.executeUpdate();
            } catch (SQLException ignored) {
            }
        }
    }

    private DisplacementResult mapResult(ResultSet rs) throws SQLException {
        DisplacementResult r = new DisplacementResult();
        r.setDataId(rs.getString("data_id"));
        r.setTimestamp(Instant.ofEpochMilli(rs.getLong("epoch_millis")));
        r.setdNorth(rs.getDouble("d_north"));
        r.setdEast(rs.getDouble("d_east"));
        r.setdUp(rs.getDouble("d_up"));
        String statusStr = rs.getString("status");
        if (statusStr != null) {
            r.setStatus(SolutionStatus.valueOf(statusStr));
        }
        r.setRatio(rs.getDouble("ratio"));
        r.setRms(rs.getDouble("rms"));
        r.setPdop(rs.getDouble("pdop"));
        r.setNumSatellites(rs.getInt("num_satellites"));
        r.setCleaned(rs.getBoolean("is_cleaned"));
        r.setAbnormal(rs.getBoolean("is_abnormal"));
        r.setAbnormalReason(rs.getString("abnormal_reason"));
        r.setDowngraded(rs.getBoolean("is_downgraded"));
        return r;
    }

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {
        }
    }
}
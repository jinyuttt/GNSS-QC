package org.gnss;

import org.gnss.model.CleanResult;
import org.gnss.model.Layer7HistoryRecord;
import org.gnss.persistence.HistoryDataProvider;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class H2HistoryDataProvider implements HistoryDataProvider, AutoCloseable {

    private final Connection conn;

    public H2HistoryDataProvider() {
        try {
            conn = DriverManager.getConnection("jdbc:h2:mem:gnss_qc_layer7;DB_CLOSE_DELAY=-1");
            conn.setAutoCommit(true);
            createTable();
        } catch (SQLException e) {
            throw new RuntimeException("H2 Layer7 init failed", e);
        }
    }

    private void createTable() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS layer7_history ("
                    + "station_id VARCHAR(128),"
                    + "epoch_millis BIGINT,"
                    + "d_north DOUBLE,"
                    + "d_east DOUBLE,"
                    + "d_up DOUBLE,"
                    + "time_series_residual DOUBLE,"
                    + "spatial_residual DOUBLE,"
                    + "solution_quality DOUBLE,"
                    + "step_flag DOUBLE,"
                    + "wcs_score DOUBLE,"
                    + "layer7_corrected BOOLEAN)");
        }
    }

    void clearAll() {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM layer7_history");
        } catch (SQLException ignored) {
        }
    }

    int countHistory(String stationId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM layer7_history WHERE station_id = ?")) {
            ps.setString(1, stationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public void saveHistory(String stationId, long timestamp, CleanResult result, double wcsScore) {
        String sql = "INSERT INTO layer7_history "
                + "(station_id, epoch_millis, d_north, d_east, d_up, "
                + "time_series_residual, spatial_residual, solution_quality, "
                + "step_flag, wcs_score, layer7_corrected) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stationId);
            ps.setLong(2, timestamp);
            ps.setDouble(3, result.getResult() != null ? result.getResult().getdNorth() : 0);
            ps.setDouble(4, result.getResult() != null ? result.getResult().getdEast() : 0);
            ps.setDouble(5, result.getResult() != null ? result.getResult().getdUp() : 0);
            ps.setDouble(6, result.getTimeSeriesResidual());
            ps.setDouble(7, result.getSpatialResidual());
            ps.setDouble(8, result.getSolutionQuality());
            ps.setDouble(9, result.getStepFlag());
            ps.setDouble(10, wcsScore);
            ps.setBoolean(11, result.isLayer7Corrected());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("saveHistory failed", e);
        }
    }

    @Override
    public List<Layer7HistoryRecord> queryLatest(String stationId, int limit) {
        String sql = "SELECT * FROM layer7_history WHERE station_id = ? ORDER BY epoch_millis ASC LIMIT ?";
        List<Layer7HistoryRecord> records = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stationId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Layer7HistoryRecord r = new Layer7HistoryRecord();
                    r.setStationId(rs.getString("station_id"));
                    r.setTimestamp(java.time.Instant.ofEpochMilli(rs.getLong("epoch_millis")));
                    r.setdNorth(rs.getDouble("d_north"));
                    r.setdEast(rs.getDouble("d_east"));
                    r.setdUp(rs.getDouble("d_up"));
                    r.setTimeSeriesResidual(rs.getDouble("time_series_residual"));
                    r.setSpatialResidual(rs.getDouble("spatial_residual"));
                    r.setSolutionQuality(rs.getDouble("solution_quality"));
                    r.setStepFlag(rs.getDouble("step_flag"));
                    r.setWcsScore(rs.getDouble("wcs_score"));
                    r.setLayer7Corrected(rs.getBoolean("layer7_corrected"));
                    records.add(r);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("queryLatest failed", e);
        }
        return records;
    }

    @Override
    public boolean isAvailable() {
        try {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            return false;
        }
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
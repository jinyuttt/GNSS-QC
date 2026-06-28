package org.gnss.shadow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.gnss.model.ShadowEvaluationResult;
import org.gnss.model.ShadowFeatureVector;
import org.gnss.persistence.ShadowEvaluationService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 第7层影子评测 HTTP 实现 — 连接 Python AI 服务
 */
public class HttpShadowEvaluationService implements ShadowEvaluationService {

    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private volatile long lastHealthCheck;
    private volatile boolean lastHealthResult;
    private static final long HEALTH_CACHE_MS = 5000;

    public HttpShadowEvaluationService(String baseUrl) {
        this(baseUrl, 3000, 5000);
    }

    public HttpShadowEvaluationService(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastHealthCheck < HEALTH_CACHE_MS) {
            return lastHealthResult;
        }
        lastHealthCheck = now;
        HttpURLConnection conn = null;
        try {
            conn = openConnection("/api/v1/health", "GET");
            if (conn.getResponseCode() != 200) {
                lastHealthResult = false;
                return false;
            }
            JSONObject resp = JSON.parseObject(readBody(conn));
            String status = resp.getString("status");
            lastHealthResult = "ok".equals(status) || "degraded".equals(status);
        } catch (Exception e) {
            lastHealthResult = false;
        } finally {
            disconnectQuietly(conn);
        }
        return lastHealthResult;
    }

    @Override
    public List<ShadowEvaluationResult> inferBatch(List<ShadowFeatureVector> features) {
        if (features == null || features.isEmpty()) {
            return Collections.emptyList();
        }
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            JSONArray arr = new JSONArray();
            for (ShadowFeatureVector fv : features) {
                arr.add(toJSON(fv));
            }
            body.put("features", arr);

            conn = openConnection("/api/v1/infer", "POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(JSON.toJSONBytes(body));
            }

            if (conn.getResponseCode() != 200) {
                return nullList(features.size());
            }

            JSONObject resp = JSON.parseObject(readBody(conn));
            JSONArray results = resp.getJSONArray("results");
            if (results == null || results.isEmpty()) {
                return nullList(features.size());
            }

            List<ShadowEvaluationResult> output = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                output.add(toResult(results.getJSONObject(i)));
            }
            return output;
        } catch (Exception e) {
            return nullList(features.size());
        } finally {
            disconnectQuietly(conn);
        }
    }

    @Override
    public List<ShadowEvaluationResult> queryShadowResults(String stationId, int limit) {
        HttpURLConnection conn = null;
        try {
            String path = "/api/v1/shadow/" + urlEncode(stationId) + "?limit=" + limit;
            conn = openConnection(path, "GET");
            if (conn.getResponseCode() != 200) {
                return Collections.emptyList();
            }
            JSONObject resp = JSON.parseObject(readBody(conn));
            JSONArray results = resp.getJSONArray("results");
            if (results == null) {
                return Collections.emptyList();
            }
            List<ShadowEvaluationResult> output = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                output.add(toResult(results.getJSONObject(i)));
            }
            return output;
        } catch (Exception e) {
            return Collections.emptyList();
        } finally {
            disconnectQuietly(conn);
        }
    }

    public JSONObject getModelVersion() {
        HttpURLConnection conn = null;
        try {
            conn = openConnection("/api/v1/model/version", "GET");
            if (conn.getResponseCode() == 200) {
                return JSON.parseObject(readBody(conn));
            }
        } catch (Exception e) {
        } finally {
            disconnectQuietly(conn);
        }
        return new JSONObject();
    }

    public JSONObject getStats() {
        HttpURLConnection conn = null;
        try {
            conn = openConnection("/api/v1/stats", "GET");
            if (conn.getResponseCode() == 200) {
                return JSON.parseObject(readBody(conn));
            }
        } catch (Exception e) {
        } finally {
            disconnectQuietly(conn);
        }
        return new JSONObject();
    }

    public boolean rollback(String version) {
        HttpURLConnection conn = null;
        try {
            String path = "/api/v1/model/rollback";
            conn = openConnection(path, "POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                if (version != null && !version.isEmpty()) {
                    JSONObject b = new JSONObject();
                    b.put("version", version);
                    os.write(JSON.toJSONBytes(b));
                } else {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                }
            }
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            disconnectQuietly(conn);
        }
    }

    private JSONObject toJSON(ShadowFeatureVector fv) {
        JSONObject j = new JSONObject();
        j.put("stationId", fv.getStationId());
        j.put("epochMillis", fv.getEpochMillis());
        j.put("f1North", fv.getF1North());
        j.put("f2East", fv.getF2East());
        j.put("f3Up", fv.getF3Up());
        j.put("f4HorizontalRate", fv.getF4HorizontalChangeRate());
        j.put("f5VerticalRate", fv.getF5VerticalChangeRate());
        j.put("f6TemporalResidual", fv.getF6TimeSeriesResidual());
        j.put("f7SpatialResidual", fv.getF7SpatialResidual());
        j.put("f8NeighborRatio", fv.getF8SameDirectionNeighborRatio());
        j.put("f9QualityScore", fv.getF9SolutionQuality());
        j.put("f10Stability", fv.getF10WindowStability());
        j.put("originalN", fv.getOriginalN());
        j.put("originalE", fv.getOriginalE());
        j.put("originalU", fv.getOriginalU());
        return j;
    }

    private ShadowEvaluationResult toResult(JSONObject r) {
        ShadowEvaluationResult result = new ShadowEvaluationResult();
        result.setStationId(getStr(r, "stationId"));
        result.setEpochMillis(r.getLongValue("epochMillis"));

        result.setOriginalN(r.getDoubleValue("originalN"));
        result.setOriginalE(r.getDoubleValue("originalE"));
        result.setOriginalU(r.getDoubleValue("originalU"));

        result.setCandidateN(r.getDoubleValue("candidateN"));
        result.setCandidateE(r.getDoubleValue("candidateE"));
        result.setCandidateU(r.getDoubleValue("candidateU"));

        result.setRrcfScore(r.getDoubleValue("rrcfScore"));
        result.setLstmResult(getStr(r, "lstmResult"));
        result.setConfidence(r.getDoubleValue("confidence"));

        result.setHaveCandidate(r.getIntValue("haveCandidate"));
        result.setCandidateType(parseCandidateType(getStr(r, "candidateType")));
        result.setReplaceSuggest(parseReplaceSuggest(getStr(r, "replaceSuggest")));
        result.setRiskLevel(parseRiskLevel(getStr(r, "riskLevel")));
        result.setDeformType(parseDeformType(getStr(r, "deformType")));

        result.setInferenceTimeMs(r.getLongValue("inferenceTimeMs"));
        result.setModelVersion(getStr(r, "modelVersion"));

        return result;
    }

    private String getStr(JSONObject r, String key) {
        Object v = r.get(key);
        return v != null ? v.toString() : "";
    }

    private HttpURLConnection openConnection(String path, String method) throws IOException {
        URI uri = URI.create(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private String readBody(HttpURLConnection conn) throws IOException {
        try (java.io.InputStream is = conn.getResponseCode() < 400
                ? conn.getInputStream()
                : conn.getErrorStream()) {
            if (is == null) return "";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void disconnectQuietly(HttpURLConnection conn) {
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private List<ShadowEvaluationResult> nullList(int size) {
        List<ShadowEvaluationResult> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add(null);
        }
        return list;
    }

    private ShadowEvaluationResult.CandidateType parseCandidateType(String s) {
        if (s == null) return ShadowEvaluationResult.CandidateType.NONE;
        try {
            return ShadowEvaluationResult.CandidateType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ShadowEvaluationResult.CandidateType.NONE;
        }
    }

    private ShadowEvaluationResult.ReplaceSuggest parseReplaceSuggest(String s) {
        if (s == null) return ShadowEvaluationResult.ReplaceSuggest.NOT_SUGGEST_REPLACE;
        try {
            return ShadowEvaluationResult.ReplaceSuggest.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ShadowEvaluationResult.ReplaceSuggest.NOT_SUGGEST_REPLACE;
        }
    }

    private ShadowEvaluationResult.RiskLevel parseRiskLevel(String s) {
        if (s == null) return ShadowEvaluationResult.RiskLevel.LOW;
        try {
            return ShadowEvaluationResult.RiskLevel.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ShadowEvaluationResult.RiskLevel.LOW;
        }
    }

    private ShadowEvaluationResult.DeformType parseDeformType(String s) {
        if (s == null) return ShadowEvaluationResult.DeformType.UNCERTAIN;
        try {
            return ShadowEvaluationResult.DeformType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return ShadowEvaluationResult.DeformType.UNCERTAIN;
        }
    }
}
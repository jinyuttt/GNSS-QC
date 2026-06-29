package org.gnss.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条数据修正项
 * <p>记录单条数据在修正前后的位移值，是 DataCorrectionRecord 的组成部分</p>
 */
public class DataCorrectionItem {

    /** 数据ID，默认：null */
    private String dataId;

    /** 原始北向位移（解算值，从未被修改），默认：0.0 */
    private double originalNorth;

    /** 原始东向位移（解算值，从未被修改），默认：0.0 */
    private double originalEast;

    /** 原始天向位移（解算值，从未被修改），默认：0.0 */
    private double originalUp;

    /** 清洗后北向位移（可能被替换过），默认：0.0 */
    private double cleanedNorth;

    /** 清洗后东向位移（可能被替换过），默认：0.0 */
    private double cleanedEast;

    /** 清洗后天向位移（可能被替换过），默认：0.0 */
    private double cleanedUp;

    /** 修正后北向位移（最终应该使用的值），默认：0.0 */
    private double correctedNorth;

    /** 修正后东向位移（最终应该使用的值），默认：0.0 */
    private double correctedEast;

    /** 修正后天向位移（最终应该使用的值），默认：0.0 */
    private double correctedUp;

    /** 修正原因，默认：null */
    private String reason;

    public DataCorrectionItem() {
    }

    public String getDataId() {
        return dataId;
    }

    public void setDataId(String dataId) {
        this.dataId = dataId;
    }

    public double getOriginalNorth() {
        return originalNorth;
    }

    public void setOriginalNorth(double originalNorth) {
        this.originalNorth = originalNorth;
    }

    public double getOriginalEast() {
        return originalEast;
    }

    public void setOriginalEast(double originalEast) {
        this.originalEast = originalEast;
    }

    public double getOriginalUp() {
        return originalUp;
    }

    public void setOriginalUp(double originalUp) {
        this.originalUp = originalUp;
    }

    public double getCleanedNorth() {
        return cleanedNorth;
    }

    public void setCleanedNorth(double cleanedNorth) {
        this.cleanedNorth = cleanedNorth;
    }

    public double getCleanedEast() {
        return cleanedEast;
    }

    public void setCleanedEast(double cleanedEast) {
        this.cleanedEast = cleanedEast;
    }

    public double getCleanedUp() {
        return cleanedUp;
    }

    public void setCleanedUp(double cleanedUp) {
        this.cleanedUp = cleanedUp;
    }

    public double getCorrectedNorth() {
        return correctedNorth;
    }

    public void setCorrectedNorth(double correctedNorth) {
        this.correctedNorth = correctedNorth;
    }

    public double getCorrectedEast() {
        return correctedEast;
    }

    public void setCorrectedEast(double correctedEast) {
        this.correctedEast = correctedEast;
    }

    public double getCorrectedUp() {
        return correctedUp;
    }

    public void setCorrectedUp(double correctedUp) {
        this.correctedUp = correctedUp;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
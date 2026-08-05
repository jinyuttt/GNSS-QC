package org.gnss.cleaning;

import org.gnss.model.DataCorrectionRecord;

/**
 * 事后修正回调接口
 * <p>ChangePointScanner检测到变点后，通过此回调将修正记录持久化到数据库</p>
 */
@FunctionalInterface
public interface CorrectionCallback {

    /**
     * 收到修正记录
     *
     * @param record 修正记录（包含设备ID、时间范围、逐历元修正值）
     */
    void onCorrection(DataCorrectionRecord record);
}
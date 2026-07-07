package org.gnss.cleaning;

import org.gnss.config.CleanConfig;
import org.gnss.model.DeviceState;
import smile.wavelet.WaveletShrinkage;

/**
 * L0 滑动窗口小波去噪
 * <p>在进入主流程前剥离高频噪声，输出"干净参考值"供L3比对。不修改原始值，不参与存储。</p>
 * <p>窗口 < 32 时跳过L0，L3退化为纯Hampel。</p>
 */
public class WaveletDenoiser {

    private final CleanConfig config;

    public WaveletDenoiser(CleanConfig config) {
        this.config = config;
    }

    /**
     * 初始化设备的小波环形缓冲区
     */
    public void initBuffer(DeviceState state) {
        int size = config.waveletWindowSize;
        if (state.getWaveletWindowSize() != size) {
            state.setWaveletBufferN(new double[size]);
            state.setWaveletBufferE(new double[size]);
            state.setWaveletBufferU(new double[size]);
            state.setWaveletBufferPos(0);
            state.setWaveletBufferFilled(0);
            state.setWaveletWindowSize(size);
        }
    }

    /**
     * 将当前历元值写入环形缓冲区
     */
    public void pushToBuffer(DeviceState state, double north, double east, double up) {
        initBuffer(state);
        int pos = state.getWaveletBufferPos();
        int size = state.getWaveletWindowSize();
        state.getWaveletBufferN()[pos] = north;
        state.getWaveletBufferE()[pos] = east;
        state.getWaveletBufferU()[pos] = up;
        state.setWaveletBufferPos((pos + 1) % size);
        int filled = state.getWaveletBufferFilled();
        if (filled < size) {
            state.setWaveletBufferFilled(filled + 1);
        }
    }

    /**
     * 执行小波去噪，返回去噪后的最新值
     * <p>窗口未满时返回null，表示降级</p>
     */
    public DenoisedResult denoise(DeviceState state) {
        int size = state.getWaveletWindowSize();
        int filled = state.getWaveletBufferFilled();
        if (filled < size) {
            state.setDenoisedNorth(null);
            state.setDenoisedEast(null);
            state.setDenoisedUp(null);
            return null;
        }

        try {
            double[] arrN = linearizeBuffer(state.getWaveletBufferN(), state.getWaveletBufferPos(), size);
            double[] arrE = linearizeBuffer(state.getWaveletBufferE(), state.getWaveletBufferPos(), size);
            double[] arrU = linearizeBuffer(state.getWaveletBufferU(), state.getWaveletBufferPos(), size);

            smile.wavelet.Wavelet wavelet = new smile.wavelet.DaubechiesWavelet(4);
            WaveletShrinkage.denoise(arrN, wavelet, true);
            WaveletShrinkage.denoise(arrE, wavelet, true);
            WaveletShrinkage.denoise(arrU, wavelet, true);

            double dn = arrN[arrN.length - 1];
            double de = arrE[arrE.length - 1];
            double du = arrU[arrU.length - 1];

            state.setDenoisedNorth(dn);
            state.setDenoisedEast(de);
            state.setDenoisedUp(du);

            return new DenoisedResult(dn, de, du);
        } catch (Exception e) {
            state.setDenoisedNorth(null);
            state.setDenoisedEast(null);
            state.setDenoisedUp(null);
            return null;
        }
    }

    /**
     * 将环形缓冲区线性化为时间顺序数组
     */
    private double[] linearizeBuffer(double[] buffer, int writePos, int size) {
        double[] result = new double[size];
        int start = writePos;
        for (int i = 0; i < size; i++) {
            result[i] = buffer[(start + i) % size];
        }
        return result;
    }

    public static class DenoisedResult {
        public final double north;
        public final double east;
        public final double up;

        public DenoisedResult(double north, double east, double up) {
            this.north = north;
            this.east = east;
            this.up = up;
        }
    }
}
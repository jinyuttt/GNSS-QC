package org.gnss.model;

import java.util.Arrays;

/**
 * HDBSCAN聚类计算结果
 * <p>
 * 包含每个数据点的聚类标签、成员概率和离群得分。
 * 标签为-1表示噪声点（离群点），>=0表示所属簇编号。
 * </p>
 */
public class HdbscanResult {

    private int[] labels;
    private double[] probabilities;
    private double[] outlierScores;
    private int numClusters;

    public HdbscanResult() {
    }

    public HdbscanResult(int[] labels, double[] probabilities,
                         double[] outlierScores, int numClusters) {
        this.labels = labels;
        this.probabilities = probabilities;
        this.outlierScores = outlierScores;
        this.numClusters = numClusters;
    }

    public int[] getLabels() {
        return labels;
    }

    public void setLabels(int[] labels) {
        this.labels = labels;
    }

    public double[] getProbabilities() {
        return probabilities;
    }

    public void setProbabilities(double[] probabilities) {
        this.probabilities = probabilities;
    }

    public double[] getOutlierScores() {
        return outlierScores;
    }

    public void setOutlierScores(double[] outlierScores) {
        this.outlierScores = outlierScores;
    }

    public int getNumClusters() {
        return numClusters;
    }

    public void setNumClusters(int numClusters) {
        this.numClusters = numClusters;
    }

    public boolean isNoise(int index) {
        return labels != null && index >= 0 && index < labels.length && labels[index] == -1;
    }

    public int getNoiseCount() {
        if (labels == null) return 0;
        int count = 0;
        for (int label : labels) {
            if (label == -1) count++;
        }
        return count;
    }

    @Override
    public String toString() {
        return "HdbscanResult{" +
                "numClusters=" + numClusters +
                ", noiseCount=" + getNoiseCount() +
                ", labels=" + (labels != null ? Arrays.toString(Arrays.copyOf(labels, Math.min(labels.length, 20))) : "null") +
                '}';
    }
}
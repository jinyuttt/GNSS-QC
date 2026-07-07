package org.gnss.cleaning;

import org.gnss.model.DisplacementResult;
import org.gnss.model.HdbscanResult;
import smile.math.distance.EuclideanDistance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * HDBSCAN聚类工具类（基于Smile算法实现）
 * <p>
 * HDBSCAN（Hierarchical Density-Based Spatial Clustering of Applications with Noise）
 * 是一种基于密度的层次聚类算法，能够自动发现不同密度的簇并识别噪声点。
 * </p>
 * <p>
 * 算法流程遵循Campello等人2013年论文及Python hdbscan参考实现：
 * <ol>
 *   <li>基于minPoints估计核心距离</li>
 *   <li>构建互达距离图（mutual reachability graph）</li>
 *   <li>计算最小生成树（MST）</li>
 *   <li>构建层次树（dendrogram）并基于minClusterSize进行稳定性选择</li>
 * </ol>
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * List<DisplacementResult> rtkData = ...;
 * HdbscanCalculator calculator = new HdbscanCalculator(5, 5);
 * HdbscanResult result = calculator.fit(rtkData);
 * for (int i = 0; i < rtkData.size(); i++) {
 *     System.out.println("点" + i + " 标签=" + result.getLabels()[i]
 *         + " 离群得分=" + result.getOutlierScores()[i]);
 * }
 * }</pre>
 */
public class HdbscanCalculator {

    private static final int OUTLIER = -1;

    private final int minPoints;
    private final int minClusterSize;

    public HdbscanCalculator(int minClusterSize) {
        this(minClusterSize, minClusterSize);
    }

    public HdbscanCalculator(int minClusterSize, int minPoints) {
        if (minClusterSize < 2) {
            throw new IllegalArgumentException("minClusterSize must be >= 2, current: " + minClusterSize);
        }
        if (minPoints < 1) {
            throw new IllegalArgumentException("minPoints must be >= 1, current: " + minPoints);
        }
        this.minClusterSize = minClusterSize;
        this.minPoints = minPoints;
    }

    public HdbscanResult fit(List<DisplacementResult> rtkResults) {
        if (rtkResults == null || rtkResults.isEmpty()) {
            return emptyResult(0);
        }
        double[][] data = extractFeatures(rtkResults);
        return fit(data);
    }

    public HdbscanResult fit(List<DisplacementResult> rtkResults, String featureDims) {
        if (rtkResults == null || rtkResults.isEmpty()) {
            return emptyResult(0);
        }
        double[][] data = extractFeatures(rtkResults, featureDims);
        return fit(data);
    }

    public HdbscanResult fit(double[][] data) {
        int n = data.length;
        if (n < minClusterSize) {
            return allNoiseResult(n);
        }

        double[][] dist = computeDistanceMatrix(data);
        double[] coreDist = computeCoreDistances(dist);
        double[][] mrd = computeMutualReachabilityDistance(dist, coreDist);
        Edge[] mst = buildMST(mrd, n);
        Dendrogram tree = buildDendrogram(mst, n);
        Selection sel = selectClusters(tree, minClusterSize);
        int[] labels = label(sel.selected, tree.nodes, n);
        double[] outlierScores = computeOutlierScores(labels, coreDist, tree, sel, n);
        double[] probabilities = computeProbabilities(labels, coreDist, tree, sel, n);

        int numClusters = 0;
        for (int label : labels) {
            if (label != OUTLIER && label + 1 > numClusters) {
                numClusters = label + 1;
            }
        }

        return new HdbscanResult(labels, probabilities, outlierScores, numClusters);
    }

    private double[][] extractFeatures(List<DisplacementResult> rtkResults) {
        return extractFeatures(rtkResults, "NEU");
    }

    private double[][] extractFeatures(List<DisplacementResult> rtkResults, String featureDims) {
        String dims = featureDims.toUpperCase();
        int n = rtkResults.size();
        double[][] data = new double[n][dims.length()];
        for (int i = 0; i < n; i++) {
            DisplacementResult r = rtkResults.get(i);
            for (int d = 0; d < dims.length(); d++) {
                switch (dims.charAt(d)) {
                    case 'N' -> data[i][d] = r.getdNorth();
                    case 'E' -> data[i][d] = r.getdEast();
                    case 'U' -> data[i][d] = r.getdUp();
                    case 'R' -> data[i][d] = r.getRatio();
                    case 'P' -> data[i][d] = r.getPdop();
                    default -> throw new IllegalArgumentException("Unknown feature dim: " + dims.charAt(d));
                }
            }
        }
        return data;
    }

    private double[][] computeDistanceMatrix(double[][] data) {
        int n = data.length;
        EuclideanDistance distFn = new EuclideanDistance();
        double[][] dist = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = distFn.d(data[i], data[j]);
                dist[i][j] = d;
                dist[j][i] = d;
            }
        }
        return dist;
    }

    private double[] computeCoreDistances(double[][] dist) {
        int n = dist.length;
        double[] coreDist = new double[n];
        int k = Math.min(minPoints, n);
        for (int i = 0; i < n; i++) {
            double[] row = new double[n];
            System.arraycopy(dist[i], 0, row, 0, n);
            Arrays.sort(row);
            coreDist[i] = row[k - 1];
        }
        return coreDist;
    }

    private double[][] computeMutualReachabilityDistance(double[][] dist, double[] coreDist) {
        int n = dist.length;
        double[][] mrd = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    mrd[i][j] = 0.0;
                } else {
                    mrd[i][j] = Math.max(Math.max(coreDist[i], coreDist[j]), dist[i][j]);
                }
            }
        }
        return mrd;
    }

    private Edge[] buildMST(double[][] mrd, int n) {
        Edge[] mst = new Edge[n - 1];
        boolean[] inTree = new boolean[n];
        double[] minDist = new double[n];
        int[] parent = new int[n];
        Arrays.fill(minDist, Double.MAX_VALUE);
        minDist[0] = 0.0;
        parent[0] = -1;

        for (int count = 0; count < n; count++) {
            int u = -1;
            for (int v = 0; v < n; v++) {
                if (!inTree[v] && (u == -1 || minDist[v] < minDist[u])) {
                    u = v;
                }
            }
            inTree[u] = true;
            if (parent[u] != -1) {
                mst[count - 1] = new Edge(parent[u], u, mrd[parent[u]][u]);
            }
            for (int v = 0; v < n; v++) {
                if (!inTree[v] && mrd[u][v] < minDist[v]) {
                    minDist[v] = mrd[u][v];
                    parent[v] = u;
                }
            }
        }
        return mst;
    }

    private Dendrogram buildDendrogram(Edge[] mst, int n) {
        Arrays.sort(mst);
        Node[] nodes = new Node[2 * n];
        for (int i = 0; i < n; i++) {
            nodes[i] = Node.leaf(i);
        }

        IntDisjointSet uf = new IntDisjointSet(n);
        int[] componentNode = new int[n];
        for (int i = 0; i < n; i++) {
            componentNode[i] = i;
        }

        int next = n;
        for (Edge edge : mst) {
            int ru = uf.find(edge.u);
            int rv = uf.find(edge.v);
            int leftNode = componentNode[ru];
            int rightNode = componentNode[rv];
            double lambda = edge.weight > 0 ? 1.0 / edge.weight : Double.POSITIVE_INFINITY;
            int size = nodes[leftNode].size + nodes[rightNode].size;
            Node internal = Node.internal(next, leftNode, rightNode, lambda, size);
            nodes[next] = internal;
            nodes[leftNode].parent = next;
            nodes[rightNode].parent = next;

            int root = uf.union(ru, rv);
            componentNode[root] = next;
            next++;
        }

        int root = next - 1;
        return new Dendrogram(nodes, root, n);
    }

    private Selection selectClusters(Dendrogram tree, int minClusterSize) {
        List<Integer> selected = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        select(tree.root, 0.0, tree, minClusterSize, selected, scores);
        double[] stability = new double[scores.size()];
        for (int i = 0; i < scores.size(); i++) {
            stability[i] = scores.get(i);
        }
        return new Selection(selected.stream().mapToInt(Integer::intValue).toArray(), stability);
    }

    private double select(int id, double parentLambda, Dendrogram tree, int minClusterSize,
                          List<Integer> selected, List<Double> selectedScores) {
        Node node = tree.nodes[id];
        if (node.size < minClusterSize) {
            return 0.0;
        }
        if (node.isLeaf()) {
            return 0.0;
        }

        double left = select(node.left, node.lambda, tree, minClusterSize, selected, selectedScores);
        double right = select(node.right, node.lambda, tree, minClusterSize, selected, selectedScores);
        double children = left + right;
        double own = (node.lambda - parentLambda) * node.size;

        if (own >= children) {
            removeDescendants(id, tree, selected, selectedScores);
            selected.add(id);
            selectedScores.add(own);
            return own;
        } else {
            return children;
        }
    }

    private void removeDescendants(int id, Dendrogram tree, List<Integer> selected, List<Double> selectedScores) {
        for (int i = selected.size() - 1; i >= 0; i--) {
            if (isDescendant(selected.get(i), id, tree)) {
                selected.remove(i);
                selectedScores.remove(i);
            }
        }
    }

    private boolean isDescendant(int nodeId, int ancestorId, Dendrogram tree) {
        int p = tree.nodes[nodeId].parent;
        while (p >= 0) {
            if (p == ancestorId) {
                return true;
            }
            p = tree.nodes[p].parent;
        }
        return false;
    }

    private int[] label(int[] selected, Node[] nodes, int n) {
        int[] group = new int[n];
        Arrays.fill(group, OUTLIER);
        for (int c = 0; c < selected.length; c++) {
            assign(selected[c], c, nodes, group);
        }
        return group;
    }

    private void assign(int nodeId, int label, Node[] nodes, int[] group) {
        Node node = nodes[nodeId];
        if (node.isLeaf()) {
            group[node.id] = label;
            return;
        }
        assign(node.left, label, nodes, group);
        assign(node.right, label, nodes, group);
    }

    private double[] computeOutlierScores(int[] labels, double[] coreDist,
                                          Dendrogram tree, Selection sel, int n) {
        double[] scores = new double[n];
        Arrays.fill(scores, 1.0);

        for (int c = 0; c < sel.selected.length; c++) {
            int nodeId = sel.selected[c];
            double clusterLambda = tree.nodes[nodeId].lambda;
            assignOutlierScore(nodeId, c, clusterLambda, tree, sel, labels, coreDist, scores);
        }
        return scores;
    }

    private void assignOutlierScore(int nodeId, int clusterIdx, double clusterLambda,
                                    Dendrogram tree, Selection sel,
                                    int[] labels, double[] coreDist, double[] scores) {
        Node node = tree.nodes[nodeId];
        if (node.isLeaf()) {
            double pointLambda = coreDist[node.id] > 0 ? 1.0 / coreDist[node.id] : Double.POSITIVE_INFINITY;
            double maxLambda = Math.max(clusterLambda, 0.0);
            if (maxLambda > 0) {
                scores[node.id] = 1.0 - (pointLambda / maxLambda);
            } else {
                scores[node.id] = 0.0;
            }
            scores[node.id] = Math.max(0.0, Math.min(1.0, scores[node.id]));
            return;
        }
        assignOutlierScore(node.left, clusterIdx, clusterLambda, tree, sel, labels, coreDist, scores);
        assignOutlierScore(node.right, clusterIdx, clusterLambda, tree, sel, labels, coreDist, scores);
    }

    private double[] computeProbabilities(int[] labels, double[] coreDist,
                                          Dendrogram tree, Selection sel, int n) {
        double[] probs = new double[n];
        Arrays.fill(probs, 0.0);

        for (int c = 0; c < sel.selected.length; c++) {
            int nodeId = sel.selected[c];
            double clusterLambda = tree.nodes[nodeId].lambda;
            assignProbability(nodeId, clusterLambda, tree, coreDist, probs);
        }
        return probs;
    }

    private void assignProbability(int nodeId, double clusterLambda,
                                   Dendrogram tree, double[] coreDist, double[] probs) {
        Node node = tree.nodes[nodeId];
        if (node.isLeaf()) {
            double pointLambda = coreDist[node.id] > 0 ? 1.0 / coreDist[node.id] : Double.POSITIVE_INFINITY;
            if (clusterLambda > 0) {
                probs[node.id] = Math.min(1.0, pointLambda / clusterLambda);
            } else {
                probs[node.id] = 1.0;
            }
            return;
        }
        assignProbability(node.left, clusterLambda, tree, coreDist, probs);
        assignProbability(node.right, clusterLambda, tree, coreDist, probs);
    }

    private HdbscanResult emptyResult(int n) {
        return new HdbscanResult(new int[n], new double[n], new double[n], 0);
    }

    private HdbscanResult allNoiseResult(int n) {
        int[] labels = new int[n];
        Arrays.fill(labels, OUTLIER);
        double[] probs = new double[n];
        double[] outlierScores = new double[n];
        Arrays.fill(outlierScores, 1.0);
        return new HdbscanResult(labels, probs, outlierScores, 0);
    }

    public int getMinClusterSize() {
        return minClusterSize;
    }

    public int getMinPoints() {
        return minPoints;
    }

    private record Edge(int u, int v, double weight) implements Comparable<Edge> {
        @Override
        public int compareTo(Edge o) {
            return Double.compare(weight, o.weight);
        }
    }

    private static final class Node {
        final int id;
        final int left;
        final int right;
        final int size;
        final double lambda;
        int parent = -1;

        private Node(int id, int left, int right, int size, double lambda) {
            this.id = id;
            this.left = left;
            this.right = right;
            this.size = size;
            this.lambda = lambda;
        }

        static Node leaf(int id) {
            return new Node(id, -1, -1, 1, Double.POSITIVE_INFINITY);
        }

        static Node internal(int id, int left, int right, double lambda, int size) {
            return new Node(id, left, right, size, lambda);
        }

        boolean isLeaf() {
            return left < 0;
        }
    }

    private record Dendrogram(Node[] nodes, int root, int n) {}

    private record Selection(int[] selected, double[] stability) {}

    private static final class IntDisjointSet {
        private final int[] parent;
        private final int[] rank;

        IntDisjointSet(int n) {
            parent = new int[n];
            rank = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
        }

        int find(int x) {
            if (parent[x] != x) {
                parent[x] = find(parent[x]);
            }
            return parent[x];
        }

        int union(int x, int y) {
            int rx = find(x);
            int ry = find(y);
            if (rx == ry) {
                return rx;
            }
            if (rank[rx] < rank[ry]) {
                parent[rx] = ry;
                return ry;
            } else if (rank[rx] > rank[ry]) {
                parent[ry] = rx;
                return rx;
            } else {
                parent[ry] = rx;
                rank[rx]++;
                return rx;
            }
        }
    }
}
import java.util.*;

/**
 * Represents a node in a weighted graph.
 * Each node tracks traversal state, environmental conditions,
 * and maintains a weighted adjacency list to its neighbors.
 *
 * <p>Passability is derived dynamically from environmental thresholds
 * rather than stored as a fixed flag. Use {@link #setPassable(boolean)}
 * to apply a manual override, or {@link #clearPassableOverride()} to
 * revert to threshold-based evaluation.
 */
public class Node {

    private final String id;
    private final int    floor;

    // Environmental conditions
    private float temperature;
    private float gasConcentration;

    // Passability: manual override takes precedence when set
    private Boolean passableOverride;            // null = use threshold logic
    private float   temperatureThreshold;        // impassable above this temperature
    private float   gasConcentrationThreshold;   // impassable above this gas concentration

    // Default thresholds
    public static final float DEFAULT_TEMPERATURE_THRESHOLD      = 60.0f;
    public static final float DEFAULT_GAS_CONCENTRATION_THRESHOLD = 0.5f;

    // Adjacency list: neighbouring node -> edge distance
    // package-private so PathCandidate helpers in the same package can read it directly
    final Map<Node, Float> neighbors = new HashMap<>();

    // Priority-queue entry used by all Dijkstra variants.
    // Carries the Node object directly — avoids identityHashCode collisions.
    static final class NE implements Comparable<NE> {
        final float dist;
        final Node  node;
        NE(float dist, Node node) { this.dist = dist; this.node = node; }
        @Override public int compareTo(NE o) { return Float.compare(this.dist, o.dist); }
    }

    // -------------------------
    //  Constructors
    // -------------------------

    /**
     * Full constructor with custom thresholds.
     */
    public Node(String id, int floor, float temperature, float gasConcentration,
                float temperatureThreshold, float gasConcentrationThreshold) {
        this.id = id;
        this.floor = floor;
        this.temperature = temperature;
        this.gasConcentration = gasConcentration;
        this.temperatureThreshold = temperatureThreshold;
        this.gasConcentrationThreshold = gasConcentrationThreshold;
        this.passableOverride = null;
    }

    /**
     * Convenience constructor using default environmental thresholds.
     */
    public Node(String id, int floor, float temperature, float gasConcentration) {
        this(id, floor, temperature, gasConcentration,
                DEFAULT_TEMPERATURE_THRESHOLD,
                DEFAULT_GAS_CONCENTRATION_THRESHOLD);
    }

    // -------------------------
    //  Connection methods
    // -------------------------

    /** Adds a directed edge from this node to the given neighbor. */
    public void addNeighbor(Node neighbor, float distance) {
        if (neighbor == null || distance < 0)
            throw new IllegalArgumentException("Invalid neighbor or distance");
        neighbors.put(neighbor, distance);
    }

    /** Adds an undirected (bidirectional) edge between this node and the given neighbor. */
    public void addBidirectionalNeighbor(Node neighbor, float distance) {
        addNeighbor(neighbor, distance);
        neighbor.addNeighbor(this, distance);
    }

    // -------------------------
    //  Passability
    // -------------------------

    /**
     * Returns whether this node is currently passable.
     * Manual override (if set) takes precedence over threshold evaluation.
     */
    public boolean isPassable() {
        if (passableOverride != null) return passableOverride;
        return temperature <= temperatureThreshold
                && gasConcentration <= gasConcentrationThreshold;
    }

    /**
     * Manually overrides passability, bypassing threshold evaluation.
     * Call {@link #clearPassableOverride()} to restore threshold-based behaviour.
     */
    public void setPassable(boolean passable) {
        this.passableOverride = passable;
    }

    /** Removes any manual override and restores threshold-based evaluation. */
    public void clearPassableOverride() {
        this.passableOverride = null;
    }

    /** Returns true if a manual passability override is currently active. */
    public boolean hasPassableOverride() {
        return passableOverride != null;
    }

    // -------------------------
    //  Core pathfinding methods
    // -------------------------

    /**
     * Finds the nearest reachable Exit from this node using Dijkstra's algorithm.
     * Only traverses passable nodes. An Exit is a valid destination only if passable.
     *
     * @return the nearest passable Exit, or empty if none is reachable
     */
    public Optional<Exit> findNearestExit() {
        PriorityQueue<NE>  pq   = new PriorityQueue<>();
        Map<Node, Float>   dist = new HashMap<>();
        dist.put(this, 0f);
        pq.offer(new NE(0f, this));

        while (!pq.isEmpty()) {
            NE cur = pq.poll();
            if (cur.dist > dist.getOrDefault(cur.node, Float.MAX_VALUE)) continue;

            if (cur.node instanceof Exit && cur.node.isPassable())
                return Optional.of((Exit) cur.node);

            for (Map.Entry<Node, Float> e : cur.node.neighbors.entrySet()) {
                Node  nx = e.getKey();
                if (!nx.isPassable()) continue;
                float nd = cur.dist + e.getValue();
                if (nd < dist.getOrDefault(nx, Float.MAX_VALUE)) {
                    dist.put(nx, nd);
                    pq.offer(new NE(nd, nx));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the shortest path to the given target using Dijkstra's algorithm.
     * Only traverses passable nodes; the target itself is always allowed as a terminal.
     *
     * @return ordered node list from this node to target, or empty if unreachable
     */
    public Optional<List<Node>> shortestPathTo(Node target) {
        PriorityQueue<NE>  pq   = new PriorityQueue<>();
        Map<Node, Float>   dist = new HashMap<>();
        Map<Node, Node>    prev = new HashMap<>();
        dist.put(this, 0f);
        pq.offer(new NE(0f, this));

        while (!pq.isEmpty()) {
            NE cur = pq.poll();
            if (cur.node == target) break;
            if (cur.dist > dist.getOrDefault(cur.node, Float.MAX_VALUE)) continue;

            for (Map.Entry<Node, Float> e : cur.node.neighbors.entrySet()) {
                Node  nx = e.getKey();
                if (!nx.isPassable() && nx != target) continue;
                float nd = cur.dist + e.getValue();
                if (nd < dist.getOrDefault(nx, Float.MAX_VALUE)) {
                    dist.put(nx, nd);
                    prev.put(nx, cur.node);
                    pq.offer(new NE(nd, nx));
                }
            }
        }

        if (!dist.containsKey(target)) return Optional.empty();
        LinkedList<Node> path = new LinkedList<>();
        for (Node at = target; at != null; at = prev.get(at)) path.addFirst(at);
        return Optional.of(path);
    }

    /**
     * Finds the K shortest simple (loop-free) paths to the given target
     * using Yen's K-Shortest Paths algorithm.
     *
     * <p>Time complexity: O(K * V * (E + V log V))
     *
     * @param target destination node
     * @param k      maximum number of paths to return (must be >= 1)
     * @return up to K PathCandidate objects in ascending distance order
     */
    public List<PathCandidate> findKShortestPaths(Node target, int k) {
        if (k < 1) throw new IllegalArgumentException("k must be at least 1");

        List<PathCandidate>          A = new ArrayList<>();
        PriorityQueue<PathCandidate> B = new PriorityQueue<>();

        shortestPathTo(target).ifPresent(p -> A.add(new PathCandidate(pathDistance(p), p)));
        if (A.isEmpty()) return A;

        for (int ki = 1; ki < k; ki++) {
            PathCandidate prevPath = A.get(ki - 1);

            for (int si = 0; si < prevPath.nodes.size() - 1; si++) {
                Node       spurNode = prevPath.nodes.get(si);
                // Defensive copy — never keep a subList view across iterations
                List<Node> rootPath = new ArrayList<>(prevPath.nodes.subList(0, si + 1));

                Set<String> removedEdges = new HashSet<>();
                for (PathCandidate confirmed : A)
                    if (confirmed.nodes.size() > si
                            && confirmed.nodes.subList(0, si + 1).equals(rootPath))
                        removedEdges.add(edgeKey(spurNode, confirmed.nodes.get(si + 1)));

                Set<Node> removedNodes = new HashSet<>(rootPath.subList(0, rootPath.size() - 1));

                Optional<List<Node>> spurOpt =
                        dijkstraWithExclusions(spurNode, target, removedEdges, removedNodes);
                if (!spurOpt.isPresent()) continue;

                List<Node> spurPath   = spurOpt.get();
                List<Node> totalNodes = new ArrayList<>(rootPath);
                totalNodes.addAll(spurPath.subList(1, spurPath.size()));

                PathCandidate candidate = new PathCandidate(pathDistance(totalNodes), totalNodes);
                boolean dup = false;
                for (PathCandidate q : B) if (q.nodes.equals(candidate.nodes)) { dup = true; break; }
                if (!dup) B.add(candidate);
            }

            if (B.isEmpty()) break;
            A.add(B.poll());
        }
        return A;
    }

    // -------------------------
    //  Private helpers
    // -------------------------

    /**
     * Dijkstra with specific edges and nodes temporarily excluded.
     * Used internally by {@link #findKShortestPaths}.
     */
    private Optional<List<Node>> dijkstraWithExclusions(
            Node source, Node target,
            Set<String> removedEdges, Set<Node> removedNodes) {

        PriorityQueue<NE> pq   = new PriorityQueue<>();
        Map<Node, Float>  dist = new HashMap<>();
        Map<Node, Node>   prev = new HashMap<>();
        dist.put(source, 0f);
        pq.offer(new NE(0f, source));

        while (!pq.isEmpty()) {
            NE cur = pq.poll();
            if (cur.node == target) break;
            if (cur.dist > dist.getOrDefault(cur.node, Float.MAX_VALUE)) continue;

            for (Map.Entry<Node, Float> e : cur.node.neighbors.entrySet()) {
                Node nx = e.getKey();
                if (removedNodes.contains(nx))               continue;
                if (removedEdges.contains(edgeKey(cur.node, nx))) continue;
                if (!nx.isPassable() && nx != target)        continue;

                float nd = cur.dist + e.getValue();
                if (nd < dist.getOrDefault(nx, Float.MAX_VALUE)) {
                    dist.put(nx, nd);
                    prev.put(nx, cur.node);
                    pq.offer(new NE(nd, nx));
                }
            }
        }

        if (!dist.containsKey(target)) return Optional.empty();
        LinkedList<Node> path = new LinkedList<>();
        for (Node at = target; at != null; at = prev.get(at)) path.addFirst(at);
        return Optional.of(path);
    }

    /** Sums edge weights along an ordered path. Returns MAX_VALUE if any edge is missing. */
    private float pathDistance(List<Node> path) {
        float total = 0f;
        for (int i = 0; i < path.size() - 1; i++) {
            Float d = path.get(i).neighbors.get(path.get(i + 1));
            if (d == null) return Float.MAX_VALUE;
            total += d;
        }
        return total;
    }

    /**
     * Stable directed-edge key using node IDs.
     * Using IDs instead of identityHashCode avoids hash collisions.
     */
    private String edgeKey(Node from, Node to) {
        return from.id + "->" + to.id;
    }

    // -------------------------
    //  Getters / Setters
    // -------------------------

    public String getId()    { return id; }
    public int    getFloor() { return floor; }

    public float getTemperature()              { return temperature; }
    public void  setTemperature(float v)       { this.temperature = v; }

    public float getGasConcentration()         { return gasConcentration; }
    public void  setGasConcentration(float v)  { this.gasConcentration = v; }

    public float getTemperatureThreshold()     { return temperatureThreshold; }
    public void  setTemperatureThreshold(float v) { this.temperatureThreshold = v; }

    public float getGasConcentrationThreshold()          { return gasConcentrationThreshold; }
    public void  setGasConcentrationThreshold(float v)   { this.gasConcentrationThreshold = v; }

    public Map<Node, Float> getNeighbors() { return Collections.unmodifiableMap(neighbors); }

    @Override
    public String toString() {
        String src = passableOverride != null ? "override" : "threshold";
        return String.format("Node{id='%s', floor=%d, passable=%b (%s), temp=%.1f/%.1f, gas=%.2f/%.2f}",
                id, floor, isPassable(), src,
                temperature, temperatureThreshold,
                gasConcentration, gasConcentrationThreshold);
    }
}

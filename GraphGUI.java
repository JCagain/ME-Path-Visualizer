import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

// ─────────────────────────────────────────────────────────────
//  PathCandidate
// ─────────────────────────────────────────────────────────────
class PathCandidate implements Comparable<PathCandidate> {
    final float totalDistance;
    final List<Node> nodes;

    PathCandidate(float totalDistance, List<Node> nodes) {
        this.totalDistance = totalDistance;
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    @Override public int compareTo(PathCandidate o) {
        return Float.compare(this.totalDistance, o.totalDistance);
    }

    @Override public String toString() {
        return String.format("%.1f  [ %s ]", totalDistance,
                nodes.stream().map(Node::getId).collect(Collectors.joining(" → ")));
    }
}

// ─────────────────────────────────────────────────────────────
//  Node
// ─────────────────────────────────────────────────────────────
class Node {
    private final String id;
    private float temperature;
    private float gasConcentration;
    private Boolean passableOverride;
    private float temperatureThreshold;
    private float gasConcentrationThreshold;
    // package-private so inner Dijkstra helpers can access directly
    final Map<Node, Float> neighbors = new HashMap<>();

    public static final float DEFAULT_TEMPERATURE_THRESHOLD       = 60.0f;
    public static final float DEFAULT_GAS_CONCENTRATION_THRESHOLD = 0.5f;

    // ── Priority-queue entry: carries the Node directly, no hashCode tricks ──
    private static final class NE implements Comparable<NE> {
        final float dist;
        final Node  node;
        NE(float dist, Node node) { this.dist = dist; this.node = node; }
        @Override public int compareTo(NE o) { return Float.compare(this.dist, o.dist); }
    }

    public Node(String id, float temperature, float gasConcentration,
                float tempThreshold, float gasThreshold) {
        this.id = id;
        this.temperature = temperature;
        this.gasConcentration = gasConcentration;
        this.temperatureThreshold = tempThreshold;
        this.gasConcentrationThreshold = gasThreshold;
    }

    public Node(String id, float temperature, float gasConcentration) {
        this(id, temperature, gasConcentration,
             DEFAULT_TEMPERATURE_THRESHOLD, DEFAULT_GAS_CONCENTRATION_THRESHOLD);
    }

    // ── Connection methods ────────────────────────────────────
    public void addNeighbor(Node n, float d) {
        if (n == null || d < 0) throw new IllegalArgumentException("Invalid neighbor or distance");
        neighbors.put(n, d);
    }

    public void addBidirectionalNeighbor(Node n, float d) {
        addNeighbor(n, d);
        n.addNeighbor(this, d);
    }

    // ── Passability ───────────────────────────────────────────
    public boolean isPassable() {
        if (passableOverride != null) return passableOverride;
        return temperature <= temperatureThreshold
            && gasConcentration <= gasConcentrationThreshold;
    }

    public void setPassable(boolean v)   { passableOverride = v; }
    public void clearPassableOverride()  { passableOverride = null; }
    public boolean hasPassableOverride() { return passableOverride != null; }

    // ── findNearestExit ───────────────────────────────────────
    public Optional<Exit> findNearestExit() {
        PriorityQueue<NE> pq = new PriorityQueue<>();
        Map<Node, Float> dist = new HashMap<>();
        dist.put(this, 0f);
        pq.offer(new NE(0f, this));

        while (!pq.isEmpty()) {
            NE cur = pq.poll();
            if (cur.dist > dist.getOrDefault(cur.node, Float.MAX_VALUE)) continue;
            if (cur.node instanceof Exit && cur.node.isPassable())
                return Optional.of((Exit) cur.node);
            for (Map.Entry<Node, Float> e : cur.node.neighbors.entrySet()) {
                Node nx = e.getKey();
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

    // ── shortestPathTo ────────────────────────────────────────
    public Optional<List<Node>> shortestPathTo(Node target) {
        PriorityQueue<NE> pq = new PriorityQueue<>();
        Map<Node, Float> dist = new HashMap<>();
        Map<Node, Node>  prev = new HashMap<>();
        dist.put(this, 0f);
        pq.offer(new NE(0f, this));

        while (!pq.isEmpty()) {
            NE cur = pq.poll();
            if (cur.node == target) break;
            if (cur.dist > dist.getOrDefault(cur.node, Float.MAX_VALUE)) continue;
            for (Map.Entry<Node, Float> e : cur.node.neighbors.entrySet()) {
                Node nx = e.getKey();
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

    // ── findKShortestPaths (Yen's algorithm) ──────────────────
    public List<PathCandidate> findKShortestPaths(Node target, int k) {
        if (k < 1) throw new IllegalArgumentException("k must be >= 1");

        List<PathCandidate>      A = new ArrayList<>();
        PriorityQueue<PathCandidate> B = new PriorityQueue<>();

        shortestPathTo(target).ifPresent(p -> A.add(new PathCandidate(pathDist(p), p)));
        if (A.isEmpty()) return A;

        for (int ki = 1; ki < k; ki++) {
            PathCandidate prevPath = A.get(ki - 1);

            for (int si = 0; si < prevPath.nodes.size() - 1; si++) {
                Node       spur = prevPath.nodes.get(si);
                List<Node> root = new ArrayList<>(prevPath.nodes.subList(0, si + 1));

                Set<String> rmEdges = new HashSet<>();
                for (PathCandidate c : A)
                    if (c.nodes.size() > si && c.nodes.subList(0, si + 1).equals(root))
                        rmEdges.add(ek(spur, c.nodes.get(si + 1)));

                Set<Node> rmNodes = new HashSet<>(root.subList(0, root.size() - 1));

                Optional<List<Node>> spurOpt = dijkstraEx(spur, target, rmEdges, rmNodes);
                if (!spurOpt.isPresent()) continue;

                List<Node> spurPath = spurOpt.get();
                List<Node> total    = new ArrayList<>(root);
                total.addAll(spurPath.subList(1, spurPath.size()));

                PathCandidate cand = new PathCandidate(pathDist(total), total);
                boolean dup = false;
                for (PathCandidate q : B) if (q.nodes.equals(cand.nodes)) { dup = true; break; }
                if (!dup) B.add(cand);
            }
            if (B.isEmpty()) break;
            A.add(B.poll());
        }
        return A;
    }

    // ── dijkstraEx: Dijkstra with edge/node exclusions ────────
    private Optional<List<Node>> dijkstraEx(Node src, Node tgt,
                                             Set<String> rmEdges, Set<Node> rmNodes) {
        PriorityQueue<NE>       pq   = new PriorityQueue<>();
        Map<Node, Float>        dist = new HashMap<>();
        Map<Node, Node>         prev = new HashMap<>();
        dist.put(src, 0f);
        pq.offer(new NE(0f, src));

        while (!pq.isEmpty()) {
            NE cur = pq.poll();
            if (cur.node == tgt) break;
            if (cur.dist > dist.getOrDefault(cur.node, Float.MAX_VALUE)) continue;
            for (Map.Entry<Node, Float> e : cur.node.neighbors.entrySet()) {
                Node nx = e.getKey();
                if (rmNodes.contains(nx))            continue;
                if (rmEdges.contains(ek(cur.node,nx))) continue;
                if (!nx.isPassable() && nx != tgt)   continue;
                float nd = cur.dist + e.getValue();
                if (nd < dist.getOrDefault(nx, Float.MAX_VALUE)) {
                    dist.put(nx, nd);
                    prev.put(nx, cur.node);
                    pq.offer(new NE(nd, nx));
                }
            }
        }
        if (!dist.containsKey(tgt)) return Optional.empty();
        LinkedList<Node> path = new LinkedList<>();
        for (Node at = tgt; at != null; at = prev.get(at)) path.addFirst(at);
        return Optional.of(path);
    }

    // ── Helpers ───────────────────────────────────────────────
    private float pathDist(List<Node> path) {
        float t = 0f;
        for (int i = 0; i < path.size() - 1; i++) {
            Float d = path.get(i).neighbors.get(path.get(i + 1));
            if (d == null) return Float.MAX_VALUE;
            t += d;
        }
        return t;
    }

    /** Directed edge key using node IDs (stable, no hashCode collision). */
    private String ek(Node a, Node b) { return a.id + "->" + b.id; }

    // ── Getters / Setters ─────────────────────────────────────
    public String getId()                    { return id; }
    public float  getTemperature()           { return temperature; }
    public void   setTemperature(float v)    { temperature = v; }
    public float  getGasConcentration()      { return gasConcentration; }
    public void   setGasConcentration(float v) { gasConcentration = v; }
    public float  getTemperatureThreshold()  { return temperatureThreshold; }
    public void   setTemperatureThreshold(float v) { temperatureThreshold = v; }
    public float  getGasConcentrationThreshold() { return gasConcentrationThreshold; }
    public void   setGasConcentrationThreshold(float v) { gasConcentrationThreshold = v; }
    public Map<Node, Float> getNeighbors()   { return Collections.unmodifiableMap(neighbors); }

    @Override public String toString() {
        return String.format("Node{id='%s', passable=%b, temp=%.1f, gas=%.2f}",
                id, isPassable(), temperature, gasConcentration);
    }
}

// ─────────────────────────────────────────────────────────────
//  Exit
// ─────────────────────────────────────────────────────────────
class Exit extends Node {
    private final String exitName;

    public Exit(String id, String exitName, boolean passable,
                float temperature, float gasConcentration) {
        super(id, temperature, gasConcentration);
        this.exitName = exitName;
        if (!passable) setPassable(false);
    }

    public String getExitName() { return exitName; }

    @Override public String toString() {
        return String.format("Exit{id='%s', name='%s', passable=%b, temp=%.1f, gas=%.2f}",
                getId(), exitName, isPassable(), getTemperature(), getGasConcentration());
    }
}

// ─────────────────────────────────────────────────────────────
//  GraphGUI
// ─────────────────────────────────────────────────────────────
public class GraphGUI extends JFrame {

    // ── Palette ───────────────────────────────────────────────
    private static final Color BG          = new Color(15, 17, 26);
    private static final Color PANEL_BG    = new Color(22, 25, 38);
    private static final Color BORDER_COL  = new Color(50, 55, 80);
    private static final Color NODE_BLUE   = new Color(60, 130, 255);
    private static final Color NODE_GREEN  = new Color(50, 210, 120);
    private static final Color NODE_RED    = new Color(240, 70, 70);
    private static final Color EDGE_COL    = new Color(70, 80, 110);
    private static final Color EDGE_WEIGHT = new Color(130, 140, 170);
    private static final Color TEXT_BRIGHT = new Color(220, 225, 245);
    private static final Color TEXT_DIM    = new Color(110, 120, 150);
    private static final Color ACCENT      = new Color(100, 160, 255);
    private static final Color SEL_RING    = new Color(255, 220, 80);

    private static final Color[] PATH_COLORS = {
        new Color(255, 200,  60),
        new Color(255, 120, 200),
        new Color(80,  220, 255),
    };

    private static final int NODE_R = 22;
    private static final int HIT_R  = NODE_R + 6;

    // ── State ─────────────────────────────────────────────────
    enum Mode { CHANGE_PASSABILITY, FIND_PATH }
    private Mode mode = Mode.CHANGE_PASSABILITY;

    private final List<Node>          nodeList   = new ArrayList<>();
    private final Map<Node, Point>    positions  = new LinkedHashMap<>();
    private final List<PathCandidate> foundPaths = new ArrayList<>();
    private Node   selectedNode = null;
    private String statusMsg    = "Click a node to toggle its passability.";

    private final GraphCanvas canvas = new GraphCanvas();
    private JTextArea  infoArea;
    private JLabel     statusLabel;

    // ─────────────────────────────────────────────────────────
    public GraphGUI() {
        super("Exit Path Visualizer");
        buildSampleGraph();
        buildUI();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 720);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Sample graph ──────────────────────────────────────────
    private void buildSampleGraph() {
        Node a  = new Node("A",  25f, 0.01f);
        Node b  = new Node("B",  30f, 0.05f);
        Node c  = new Node("C",  80f, 0.95f);   // impassable by threshold
        Node d  = new Node("D",  27f, 0.02f);
        Node f  = new Node("F",  22f, 0.03f);
        Exit e1 = new Exit("E1", "North Gate", true,  22f, 0.01f);
        Exit e2 = new Exit("E2", "South Gate", false, 75f, 0.80f); // blocked
        Exit e3 = new Exit("E3", "East Gate",  true,  20f, 0.01f);

        a.addBidirectionalNeighbor(b,  5f);
        a.addBidirectionalNeighbor(c,  3f);
        a.addBidirectionalNeighbor(f,  8f);
        b.addBidirectionalNeighbor(d,  4f);
        b.addBidirectionalNeighbor(e1, 6f);
        c.addBidirectionalNeighbor(d,  2f);
        d.addBidirectionalNeighbor(e2, 7f);
        d.addBidirectionalNeighbor(f,  3f);
        f.addBidirectionalNeighbor(e3, 5f);

        nodeList.addAll(Arrays.asList(a, b, c, d, f, e1, e2, e3));

        positions.put(a,  new Point(200, 300));
        positions.put(b,  new Point(370, 180));
        positions.put(c,  new Point(370, 420));
        positions.put(d,  new Point(540, 300));
        positions.put(f,  new Point(310, 490));
        positions.put(e1, new Point(560, 130));
        positions.put(e2, new Point(720, 300));
        positions.put(e3, new Point(500, 510));
    }

    // ── UI ────────────────────────────────────────────────────
    private void buildUI() {
        setBackground(BG);
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG);
        setContentPane(root);
        root.add(buildTopBar(),    BorderLayout.NORTH);
        root.add(canvas,           BorderLayout.CENTER);
        root.add(buildSidePanel(), BorderLayout.EAST);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 10));
        bar.setBackground(PANEL_BG);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COL));

        JLabel title = new JLabel("GRAPH VISUALIZER");
        title.setFont(new Font("Courier New", Font.BOLD, 15));
        title.setForeground(ACCENT);
        bar.add(title);

        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 28));
        sep.setForeground(BORDER_COL);
        bar.add(sep);

        JToggleButton btnPass = makeToggle("Change Passability", true);
        JToggleButton btnPath = makeToggle("Find Path",           false);
        ButtonGroup   bg      = new ButtonGroup();
        bg.add(btnPass); bg.add(btnPath);
        bar.add(btnPass); bar.add(btnPath);

        btnPass.addActionListener(e -> {
            mode = Mode.CHANGE_PASSABILITY;
            foundPaths.clear(); selectedNode = null;
            statusMsg = "Click a node to toggle its passability.";
            canvas.repaint(); updateInfoArea();
        });
        btnPath.addActionListener(e -> {
            mode = Mode.FIND_PATH;
            foundPaths.clear(); selectedNode = null;
            statusMsg = "Click a node to find the 3 shortest paths to all exits.";
            canvas.repaint(); updateInfoArea();
        });

        bar.add(Box.createHorizontalStrut(20));
        bar.add(legendDot(NODE_BLUE,  "Node"));
        bar.add(legendDot(NODE_GREEN, "Exit"));
        bar.add(legendDot(NODE_RED,   "Impassable"));
        return bar;
    }

    private JToggleButton makeToggle(String text, boolean selected) {
        JToggleButton b = new JToggleButton(text, selected);
        b.setFont(new Font("Courier New", Font.BOLD, 12));
        b.setForeground(selected ? BG : TEXT_DIM);
        b.setBackground(selected ? ACCENT : PANEL_BG);
        b.setBorder(new RoundedBorder(8, BORDER_COL));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(220, 30));
        b.addItemListener(ev -> {
            boolean sel = b.isSelected();
            b.setForeground(sel ? BG : TEXT_DIM);
            b.setBackground(sel ? ACCENT : PANEL_BG);
        });
        return b;
    }

    private JPanel legendDot(Color c, String label) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        JLabel dot = new JLabel("●");
        dot.setFont(new Font("Dialog", Font.PLAIN, 16));
        dot.setForeground(c);
        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Courier New", Font.PLAIN, 11));
        lbl.setForeground(TEXT_DIM);
        p.add(dot); p.add(lbl);
        return p;
    }

    private JPanel buildSidePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(PANEL_BG);
        panel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, BORDER_COL));
        panel.setPreferredSize(new Dimension(260, 0));

        JLabel heading = new JLabel("  DETAILS");
        heading.setFont(new Font("Courier New", Font.BOLD, 12));
        heading.setForeground(ACCENT);
        heading.setOpaque(true);
        heading.setBackground(PANEL_BG);
        heading.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COL));
        heading.setPreferredSize(new Dimension(0, 36));
        panel.add(heading, BorderLayout.NORTH);

        infoArea = new JTextArea("Select a node...");
        infoArea.setFont(new Font("Courier New", Font.PLAIN, 12));
        infoArea.setForeground(TEXT_BRIGHT);
        infoArea.setBackground(PANEL_BG);
        infoArea.setEditable(false);
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);
        infoArea.setBorder(new EmptyBorder(12, 12, 12, 12));

        JScrollPane scroll = new JScrollPane(infoArea);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(PANEL_BG);
        scroll.setBackground(PANEL_BG);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void updateInfoArea() {
        if (selectedNode == null) { infoArea.setText("No node selected."); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("ID:        ").append(selectedNode.getId()).append("\n");
        sb.append("Floor:        ").append(selectedNode.getFloor()).append("\n");
        sb.append("Type:      ").append(selectedNode instanceof Exit ? "Exit" : "Node").append("\n");
        if (selectedNode instanceof Exit)
            sb.append("Name:      ").append(((Exit) selectedNode).getExitName()).append("\n");
        sb.append("Passable:  ").append(selectedNode.isPassable()).append("\n");
        sb.append("Override:  ").append(selectedNode.hasPassableOverride()).append("\n");
        sb.append(String.format("Temp:      %.1f C%n", selectedNode.getTemperature()));
        sb.append(String.format("Gas:       %.2f%n",   selectedNode.getGasConcentration()));
        if (!foundPaths.isEmpty()) {
            sb.append("\n── K-Shortest Paths ──\n");
            for (int i = 0; i < foundPaths.size(); i++) {
                PathCandidate pc = foundPaths.get(i);
                sb.append(String.format("#%d  dist=%.1f%n", i + 1, pc.totalDistance));
                sb.append("  ").append(
                    pc.nodes.stream().map(Node::getId).collect(Collectors.joining(" -> "))
                ).append("\n");
            }
        }
        infoArea.setText(sb.toString());
        infoArea.setCaretPosition(0);
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        bar.setBackground(PANEL_BG);
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COL));
        statusLabel = new JLabel(statusMsg);
        statusLabel.setFont(new Font("Courier New", Font.PLAIN, 11));
        statusLabel.setForeground(TEXT_DIM);
        bar.add(statusLabel);
        return bar;
    }

    private void setStatus(String msg) {
        statusMsg = msg;
        statusLabel.setText(msg);
    }

    // ─────────────────────────────────────────────────────────
    //  Canvas
    // ─────────────────────────────────────────────────────────
    class GraphCanvas extends JPanel {
        GraphCanvas() {
            setBackground(BG);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { handleClick(e.getPoint()); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,     RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,   RenderingHints.VALUE_STROKE_PURE);
            drawEdges(g2);
            drawPathHighlights(g2);
            drawNodes(g2);
        }

        private void drawEdges(Graphics2D g2) {
            Set<String> drawn = new HashSet<>();
            for (Node n : nodeList) {
                Point p1 = positions.get(n);
                for (Map.Entry<Node, Float> e : n.getNeighbors().entrySet()) {
                    Node m = e.getKey();
                    String key = n.getId().compareTo(m.getId()) < 0
                                 ? n.getId() + "_" + m.getId()
                                 : m.getId() + "_" + n.getId();
                    if (!drawn.add(key)) continue;
                    Point p2 = positions.get(m);

                    g2.setColor(EDGE_COL);
                    g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(p1.x, p1.y, p2.x, p2.y);

                    int mx = (p1.x + p2.x) / 2, my = (p1.y + p2.y) / 2;
                    g2.setFont(new Font("Courier New", Font.PLAIN, 10));
                    String wt = String.valueOf((int) e.getValue().floatValue());
                    FontMetrics fm = g2.getFontMetrics();
                    int tw = fm.stringWidth(wt);
                    g2.setColor(new Color(22, 25, 38, 200));
                    g2.fillRoundRect(mx - tw / 2 - 3, my - 8, tw + 6, 14, 6, 6);
                    g2.setColor(EDGE_WEIGHT);
                    g2.drawString(wt, mx - tw / 2, my + 3);
                }
            }
        }

        private void drawPathHighlights(Graphics2D g2) {
            for (int pi = 0; pi < foundPaths.size(); pi++) {
                PathCandidate pc  = foundPaths.get(pi);
                Color         col = PATH_COLORS[pi % PATH_COLORS.length];
                float alpha = 1f - pi * 0.2f;
                Color lineCol = new Color(col.getRed(), col.getGreen(), col.getBlue(), (int)(255 * alpha));
                g2.setStroke(new BasicStroke(4f + (foundPaths.size() - pi),
                             BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(lineCol);
                for (int i = 0; i < pc.nodes.size() - 1; i++) {
                    Point pa = positions.get(pc.nodes.get(i));
                    Point pb = positions.get(pc.nodes.get(i + 1));
                    if (pa != null && pb != null) g2.drawLine(pa.x, pa.y, pb.x, pb.y);
                }
                // Badge near path midpoint
                int mid = pc.nodes.size() / 2;
                Point mp = positions.get(pc.nodes.get(mid));
                if (mp != null) {
                    String badge = "#" + (pi + 1) + "  " + String.format("%.0f", pc.totalDistance);
                    g2.setFont(new Font("Courier New", Font.BOLD, 11));
                    FontMetrics fm = g2.getFontMetrics();
                    int bw = fm.stringWidth(badge) + 10;
                    g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 200));
                    g2.fillRoundRect(mp.x + 8, mp.y - 18, bw, 18, 8, 8);
                    g2.setColor(BG);
                    g2.drawString(badge, mp.x + 13, mp.y - 4);
                }
            }
        }

        private void drawNodes(Graphics2D g2) {
            for (Node n : nodeList) {
                Point   p      = positions.get(n);
                boolean isExit = n instanceof Exit;
                boolean pass   = n.isPassable();
                Color   fill   = pass ? (isExit ? NODE_GREEN : NODE_BLUE) : NODE_RED;

                // Selection glow
                if (n == selectedNode) {
                    g2.setColor(new Color(SEL_RING.getRed(), SEL_RING.getGreen(), SEL_RING.getBlue(), 60));
                    g2.fillOval(p.x - NODE_R - 8, p.y - NODE_R - 8, (NODE_R + 8) * 2, (NODE_R + 8) * 2);
                    g2.setColor(SEL_RING);
                    g2.setStroke(new BasicStroke(2.5f));
                    g2.drawOval(p.x - NODE_R - 5, p.y - NODE_R - 5, (NODE_R + 5) * 2, (NODE_R + 5) * 2);
                }

                // Shadow
                g2.setColor(new Color(0, 0, 0, 80));
                g2.fillOval(p.x - NODE_R + 3, p.y - NODE_R + 4, NODE_R * 2, NODE_R * 2);

                // Radial gradient fill
                RadialGradientPaint grad = new RadialGradientPaint(
                    new Point2D.Float(p.x - NODE_R / 3f, p.y - NODE_R / 3f), NODE_R * 1.2f,
                    new float[]{0f, 1f},
                    new Color[]{fill.brighter(), fill.darker()});
                g2.setPaint(grad);
                g2.fillOval(p.x - NODE_R, p.y - NODE_R, NODE_R * 2, NODE_R * 2);

                // Stroke
                g2.setPaint(fill.brighter());
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(p.x - NODE_R, p.y - NODE_R, NODE_R * 2, NODE_R * 2);

                // Exit diamond marker
                if (isExit) {
                    int[] xs = {p.x, p.x + 7, p.x, p.x - 7};
                    int[] ys = {p.y - 7, p.y, p.y + 7, p.y};
                    g2.setColor(BG); g2.fillPolygon(xs, ys, 4);
                    g2.setColor(TEXT_BRIGHT); g2.setStroke(new BasicStroke(1f));
                    g2.drawPolygon(xs, ys, 4);
                }

                // ID label
                g2.setColor(TEXT_BRIGHT);
                g2.setFont(new Font("Courier New", Font.BOLD, 12));
                FontMetrics fm = g2.getFontMetrics();
                String lbl = n.getId();
                g2.drawString(lbl, p.x - fm.stringWidth(lbl) / 2, p.y + fm.getAscent() / 2 - 1);

                // Sub-label
                g2.setFont(new Font("Courier New", Font.PLAIN, 9));
                g2.setColor(TEXT_DIM);
                String sub = isExit ? ((Exit) n).getExitName()
                                    : String.format("%.0fC", n.getTemperature());
                FontMetrics fm2 = g2.getFontMetrics();
                g2.drawString(sub, p.x - fm2.stringWidth(sub) / 2, p.y + NODE_R + 13);

                // Impassable X overlay
                if (!pass) {
                    g2.setColor(new Color(255, 80, 80, 180));
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int o = NODE_R - 6;
                    g2.drawLine(p.x - o, p.y - o, p.x + o, p.y + o);
                    g2.drawLine(p.x + o, p.y - o, p.x - o, p.y + o);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Click handler
    // ─────────────────────────────────────────────────────────
    private void handleClick(Point click) {
        Node hit = nodeAt(click);
        if (hit == null) {
            selectedNode = null;
            foundPaths.clear();
            setStatus("Clicked empty space.");
            canvas.repaint(); updateInfoArea();
            return;
        }
        selectedNode = hit;

        if (mode == Mode.CHANGE_PASSABILITY) {
            boolean newVal = !hit.isPassable();
            hit.setPassable(newVal);
            setStatus("Node " + hit.getId() + " passable=" + newVal);
            foundPaths.clear();

        } else { // FIND_PATH
            foundPaths.clear();
            List<Exit> exits = nodeList.stream()
                .filter(n -> n instanceof Exit && n.isPassable())
                .map(n -> (Exit) n)
                .collect(Collectors.toList());

            if (exits.isEmpty()) {
                setStatus("No passable exits in the graph.");
            } else {
                PriorityQueue<PathCandidate> allPaths = new PriorityQueue<>();
                for (Exit ex : exits)
                    allPaths.addAll(hit.findKShortestPaths(ex, 3));
                for (int i = 0; i < 3 && !allPaths.isEmpty(); i++)
                    foundPaths.add(allPaths.poll());

                if (foundPaths.isEmpty())
                    setStatus("No reachable paths from " + hit.getId() + ".");
                else
                    setStatus("Top " + foundPaths.size() + " paths from " + hit.getId() + " found.");
            }
        }
        canvas.repaint();
        updateInfoArea();
    }

    private Node nodeAt(Point p) {
        for (Node n : nodeList) {
            Point np = positions.get(n);
            if (np != null && p.distance(np) <= HIT_R) return n;
        }
        return null;
    }

    // ── Rounded border helper ─────────────────────────────────
    static class RoundedBorder extends AbstractBorder {
        private final int arc; private final Color col;
        RoundedBorder(int arc, Color col) { this.arc = arc; this.col = col; }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(col); g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
            g2.dispose();
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(4, 10, 4, 10); }
    }

    // ── Entry point ───────────────────────────────────────────
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(GraphGUI::new);
    }
}
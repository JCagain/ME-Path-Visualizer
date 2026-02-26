import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Swing GUI for visualising and interacting with a weighted Node/Exit graph.
 *
 * <p>Two interaction modes:
 * <ul>
 *   <li><b>Change Passability</b> — click a node to toggle its passable state.</li>
 *   <li><b>Find Path</b> — click a node to highlight the 3 shortest paths to all passable exits.</li>
 * </ul>
 *
 * Compile together with Node.java, Exit.java, PathCandidate.java, Graph.java:
 * <pre>
 *   javac Node.java PathCandidate.java Exit.java Graph.java GraphGUI.java
 *   java  GraphGUI
 * </pre>
 */
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
        new Color(255, 200,  60),   // gold
        new Color(255, 120, 200),   // pink
        new Color(80,  220, 255),   // cyan
    };

    private static final int NODE_R = 22;   // node circle radius
    private static final int HIT_R  = NODE_R + 6; // click hit radius

    // ── Interaction mode ──────────────────────────────────────
    enum Mode { CHANGE_PASSABILITY, FIND_PATH }
    private Mode mode = Mode.CHANGE_PASSABILITY;

    // ── Graph state ───────────────────────────────────────────
    private final List<Node>          nodeList   = new ArrayList<>();
    private final Map<Node, Point>    positions  = new LinkedHashMap<>();
    private final List<PathCandidate> foundPaths = new ArrayList<>();
    private Node selectedNode = null;

    // ── Widgets ───────────────────────────────────────────────
    private final GraphCanvas canvas = new GraphCanvas();
    private JTextArea infoArea;
    private JLabel    statusLabel;

    // ─────────────────────────────────────────────────────────
    public GraphGUI() {
        super("Graph Visualizer");
        buildSampleGraph();
        buildUI();
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1360, 780);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ── Sample graph ──────────────────────────────────────────
    // Four floors (0-3), each a 6-node ring with random edge weights 1-2.
    // Adjacent floors are connected at two bridging nodes (distance 2).
    // Floor 0 has three Exit nodes.
    private void buildSampleGraph() {
        Random rng = new Random(42); // fixed seed for reproducibility

        // ── Node creation ─────────────────────────────────────
        // Floor layout: 4 hexagonal rings drawn left-to-right.
        // Canvas area ≈ 1100 × 650 (after toolbar/statusbar).
        // Ring centres: x = 160, 420, 680, 940  y = 330
        // Each ring has 6 nodes at angles 0°, 60°, 120°, 180°, 240°, 300°
        // with radius 110px.

        int[]    cx     = {160, 420, 680, 940};
        int      cy     = 330;
        int      radius = 110;
        int      FLOORS = 4;
        int      PER_FL = 6;

        // nodes[floor][index]
        Node[][] nodes = new Node[FLOORS][PER_FL];

        // Exit labels for floor 0, indices 0, 2, 4
        String[] exitNames  = {"North Gate", "East Gate", "South Gate"};
        int[]    exitIdx    = {0, 2, 4};

        for (int fl = 0; fl < FLOORS; fl++) {
            int ei = 0; // exit name pointer
            for (int i = 0; i < PER_FL; i++) {
                double angle = Math.toRadians(i * 60 - 90); // start at top
                int px = cx[fl] + (int)(radius * Math.cos(angle));
                int py = cy     + (int)(radius * Math.sin(angle));

                String id   = fl + "" + (char)('A' + i);   // e.g. "0A", "1C"
                float  temp = 18f + rng.nextFloat() * 12f; // 18–30 °C
                float  gas  =        rng.nextFloat() * 0.1f; // 0–0.1

                boolean isExit = (fl == 0) && (i == exitIdx[0] || i == exitIdx[1] || i == exitIdx[2]);
                if (isExit) {
                    nodes[fl][i] = new Exit(id, exitNames[ei++], fl, true, temp, gas);
                } else {
                    nodes[fl][i] = new Node(id, fl, temp, gas);
                }
                positions.put(nodes[fl][i], new Point(px, py));
                nodeList.add(nodes[fl][i]);
            }
        }

        // ── Ring edges (random weight 1 or 2) ─────────────────
        for (int fl = 0; fl < FLOORS; fl++) {
            for (int i = 0; i < PER_FL; i++) {
                float w = 1f + rng.nextInt(2); // 1 or 2
                nodes[fl][i].addBidirectionalNeighbor(nodes[fl][(i + 1) % PER_FL], w);
            }
        }

        // ── Inter-floor bridges (distance 2) ──────────────────
        // Connect floor F to floor F+1 at node indices 1 and 4
        // (top-right and bottom-left of the hexagon — visually intuitive).
        int[] bridgeIdx = {1, 4};
        for (int fl = 0; fl < FLOORS - 1; fl++) {
            for (int bi : bridgeIdx) {
                nodes[fl][bi].addBidirectionalNeighbor(nodes[fl + 1][bi], 2f);
            }
        }
    }

    // ── UI layout ─────────────────────────────────────────────
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

    // ── Top bar ───────────────────────────────────────────────
    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 10));
        bar.setBackground(PANEL_BG);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COL));

        JLabel title = new JLabel("PATH VISUALIZER");
        title.setFont(new Font("Courier New", Font.BOLD, 15));
        title.setForeground(ACCENT);
        bar.add(title);

        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 28));
        sep.setForeground(BORDER_COL);
        bar.add(sep);

        JToggleButton btnPass = makeToggle("Change Passability", true);
        JToggleButton btnPath = makeToggle("Find Path",          false);
        ButtonGroup   bg      = new ButtonGroup();
        bg.add(btnPass); bg.add(btnPath);
        bar.add(btnPass); bar.add(btnPath);

        btnPass.addActionListener(e -> {
            mode = Mode.CHANGE_PASSABILITY;
            foundPaths.clear(); selectedNode = null;
            setStatus("Click a node to toggle its passability.");
            canvas.repaint(); updateInfoArea();
        });
        btnPath.addActionListener(e -> {
            mode = Mode.FIND_PATH;
            foundPaths.clear(); selectedNode = null;
            setStatus("Click a node to find the 3 shortest paths to all exits.");
            canvas.repaint(); updateInfoArea();
        });

        bar.add(Box.createHorizontalStrut(20));
        bar.add(legendDot(NODE_BLUE,  "Node"));
        bar.add(legendDot(NODE_GREEN, "Exit"));
        bar.add(legendDot(NODE_RED,   "Impassable"));
        bar.add(legendDash("Inter-floor bridge"));
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
        b.setPreferredSize(new Dimension(200, 30));
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
        lbl.setForeground(TEXT_BRIGHT);
        p.add(dot); p.add(lbl);
        return p;
    }

    private JPanel legendDash(String label) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        JLabel dash = new JLabel("- -");
        dash.setFont(new Font("Courier New", Font.BOLD, 13));
        dash.setForeground(new Color(180, 130, 255));
        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Courier New", Font.PLAIN, 11));
        lbl.setForeground(TEXT_DIM);
        p.add(dash); p.add(lbl);
        return p;
    }

    // ── Side panel ────────────────────────────────────────────
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
        sb.append("Type:      ").append(selectedNode instanceof Exit ? "Exit" : "Node").append("\n");
        if (selectedNode instanceof Exit)
            sb.append("Name:      ").append(((Exit) selectedNode).getExitName()).append("\n");
        sb.append("Floor:     ").append(selectedNode.getFloor()).append("\n");
        sb.append("Passable:  ").append(selectedNode.isPassable()).append("\n");
        sb.append("Override:  ").append(selectedNode.hasPassableOverride()).append("\n");
        sb.append(String.format("Temp:      %.1f C%n",  selectedNode.getTemperature()));
        sb.append(String.format("Gas:       %.2f%n",    selectedNode.getGasConcentration()));

        if (!foundPaths.isEmpty()) {
            sb.append("\n── Paths ──────────────\n");
            for (int i = 0; i < foundPaths.size(); i++) {
                PathCandidate pc = foundPaths.get(i);
                sb.append(String.format("#%d  dist=%.1f%n", i + 1, pc.totalDistance));
                sb.append("  ")
                  .append(pc.nodes.stream().map(Node::getId).collect(Collectors.joining(" -> ")))
                  .append("\n");
            }
        }
        infoArea.setText(sb.toString());
        infoArea.setCaretPosition(0);
    }

    // ── Status bar ────────────────────────────────────────────
    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 6));
        bar.setBackground(PANEL_BG);
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_COL));
        statusLabel = new JLabel("Click a node to toggle its passability.");
        statusLabel.setFont(new Font("Courier New", Font.PLAIN, 15));
        statusLabel.setForeground(TEXT_BRIGHT);
        bar.add(statusLabel);
        return bar;
    }

    private void setStatus(String msg) { statusLabel.setText(msg); }

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
            drawFloorLabels(g2);
            drawEdges(g2);
            drawPathHighlights(g2);
            drawNodes(g2);
        }

        // ── Floor labels ──────────────────────────────────────
        private void drawFloorLabels(Graphics2D g2) {
            int[] cx = {160, 420, 680, 940};
            g2.setFont(new Font("Courier New", Font.BOLD, 12));
            for (int fl = 0; fl < 4; fl++) {
                String lbl = "Floor " + fl + (fl == 0 ? "  (exits)" : "");
                FontMetrics fm = g2.getFontMetrics();
                int x = cx[fl] - fm.stringWidth(lbl) / 2;
                // Dim pill background
                g2.setColor(new Color(40, 45, 65, 200));
                g2.fillRoundRect(x - 6, 28, fm.stringWidth(lbl) + 12, 20, 8, 8);
                g2.setColor(fl == 0 ? NODE_GREEN.darker() : TEXT_DIM);
                g2.drawString(lbl, x, 43);
            }
        }

        // ── Edges ─────────────────────────────────────────────
        private void drawEdges(Graphics2D g2) {
            // Draw inter-floor bridge edges first (behind ring edges)
            Set<String> drawn = new HashSet<>();
            Color BRIDGE_COL = new Color(180, 130, 255, 160); // purple tint for bridges
            float[] dash = {6f, 4f};
            Stroke bridgeStroke = new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                                                  BasicStroke.JOIN_ROUND, 1f, dash, 0f);
            Stroke ringStroke   = new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                                                  BasicStroke.JOIN_ROUND);
            for (Node n : nodeList) {
                Point p1 = positions.get(n);
                for (Map.Entry<Node, Float> e : n.getNeighbors().entrySet()) {
                    Node   m   = e.getKey();
                    String key = n.getId().compareTo(m.getId()) < 0
                                 ? n.getId() + "_" + m.getId()
                                 : m.getId() + "_" + n.getId();
                    if (!drawn.add(key)) continue;
                    Point p2 = positions.get(m);

                    boolean isBridge = n.getFloor() != m.getFloor();
                    g2.setColor(isBridge ? BRIDGE_COL : EDGE_COL);
                    g2.setStroke(isBridge ? bridgeStroke : ringStroke);
                    g2.drawLine(p1.x, p1.y, p2.x, p2.y);

                    // Weight pill
                    int mx = (p1.x + p2.x) / 2, my = (p1.y + p2.y) / 2;
                    g2.setFont(new Font("Courier New", Font.PLAIN, 10));
                    String      wt = String.valueOf((int) e.getValue().floatValue());
                    FontMetrics fm = g2.getFontMetrics();
                    int tw = fm.stringWidth(wt);
                    g2.setColor(new Color(22, 25, 38, 200));
                    g2.fillRoundRect(mx - tw / 2 - 3, my - 8, tw + 6, 14, 6, 6);
                    g2.setColor(isBridge ? BRIDGE_COL.brighter() : EDGE_WEIGHT);
                    g2.drawString(wt, mx - tw / 2, my + 3);
                }
            }
        }

        // ── Path highlights ────────────────────────────────────
        private void drawPathHighlights(Graphics2D g2) {
            for (int pi = 0; pi < foundPaths.size(); pi++) {
                PathCandidate pc    = foundPaths.get(pi);
                Color         col   = PATH_COLORS[pi % PATH_COLORS.length];
                float         alpha = 1f - pi * 0.2f;
                Color lineCol = new Color(col.getRed(), col.getGreen(), col.getBlue(),
                                          (int)(255 * alpha));
                g2.setStroke(new BasicStroke(4f + (foundPaths.size() - pi),
                             BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(lineCol);

                for (int i = 0; i < pc.nodes.size() - 1; i++) {
                    Point pa = positions.get(pc.nodes.get(i));
                    Point pb = positions.get(pc.nodes.get(i + 1));
                    if (pa != null && pb != null) g2.drawLine(pa.x, pa.y, pb.x, pb.y);
                }

                // Distance badge near midpoint
                int   mid = pc.nodes.size() / 2;
                Point mp  = positions.get(pc.nodes.get(mid));
                if (mp != null) {
                    String      badge = "#" + (pi + 1) + "  " + String.format("%.0f", pc.totalDistance);
                    g2.setFont(new Font("Courier New", Font.BOLD, 11));
                    FontMetrics fm    = g2.getFontMetrics();
                    int         bw    = fm.stringWidth(badge) + 10;
                    g2.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 200));
                    g2.fillRoundRect(mp.x + 8, mp.y - 18, bw, 18, 8, 8);
                    g2.setColor(BG);
                    g2.drawString(badge, mp.x + 13, mp.y - 4);
                }
            }
        }

        // ── Nodes ──────────────────────────────────────────────
        private void drawNodes(Graphics2D g2) {
            for (Node n : nodeList) {
                Point   p      = positions.get(n);
                boolean isExit = n instanceof Exit;
                boolean pass   = n.isPassable();
                Color   fill   = pass ? (isExit ? NODE_GREEN : NODE_BLUE) : NODE_RED;

                // Selection glow + ring
                if (n == selectedNode) {
                    g2.setColor(new Color(SEL_RING.getRed(), SEL_RING.getGreen(),
                                          SEL_RING.getBlue(), 60));
                    g2.fillOval(p.x - NODE_R - 8, p.y - NODE_R - 8,
                                (NODE_R + 8) * 2, (NODE_R + 8) * 2);
                    g2.setColor(SEL_RING);
                    g2.setStroke(new BasicStroke(2.5f));
                    g2.drawOval(p.x - NODE_R - 5, p.y - NODE_R - 5,
                                (NODE_R + 5) * 2, (NODE_R + 5) * 2);
                }

                // Drop shadow
                g2.setColor(new Color(0, 0, 0, 80));
                g2.fillOval(p.x - NODE_R + 3, p.y - NODE_R + 4, NODE_R * 2, NODE_R * 2);

                // Radial gradient fill
                RadialGradientPaint grad = new RadialGradientPaint(
                    new Point2D.Float(p.x - NODE_R / 3f, p.y - NODE_R / 3f),
                    NODE_R * 1.2f, new float[]{0f, 1f},
                    new Color[]{fill.brighter(), fill.darker()});
                g2.setPaint(grad);
                g2.fillOval(p.x - NODE_R, p.y - NODE_R, NODE_R * 2, NODE_R * 2);

                // Outline
                g2.setPaint(fill.brighter());
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(p.x - NODE_R, p.y - NODE_R, NODE_R * 2, NODE_R * 2);

                // Exit diamond marker
                if (isExit) {
                    int[] xs = {p.x, p.x + 7, p.x, p.x - 7};
                    int[] ys = {p.y - 7, p.y, p.y + 7, p.y};
                    g2.setColor(BG);           g2.fillPolygon(xs, ys, 4);
                    g2.setColor(TEXT_BRIGHT);  g2.setStroke(new BasicStroke(1f));
                    g2.drawPolygon(xs, ys, 4);
                }

                // ID label
                g2.setColor(TEXT_BRIGHT);
                g2.setFont(new Font("Courier New", Font.BOLD, 12));
                FontMetrics fm  = g2.getFontMetrics();
                String      lbl = n.getId();
                g2.drawString(lbl, p.x - fm.stringWidth(lbl) / 2, p.y + fm.getAscent() / 2 - 1);

                // Sub-label: exit name or temperature
                g2.setFont(new Font("Courier New", Font.PLAIN, 9));
                g2.setColor(TEXT_DIM);
                String sub = isExit ? ((Exit) n).getExitName()
                                    : String.format("%.0fC  F%d",
                                                    n.getTemperature(), n.getFloor());
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
            setStatus("Node " + hit.getId() + "  passable=" + newVal);
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

                setStatus(foundPaths.isEmpty()
                    ? "No reachable paths from " + hit.getId() + "."
                    : "Top " + foundPaths.size() + " paths from " + hit.getId() + " found.");
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

    // ── Rounded border ────────────────────────────────────────
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
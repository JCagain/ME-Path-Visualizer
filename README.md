# Graph Visualizer

A Java Swing application for visualising and interacting with a weighted,
multi-floor Node/Exit graph. Supports real-time passability toggling and
shortest-path finding across floors.

---

## Project Structure

```
.
├── Node.java            # Graph node with environmental passability logic
│                        # and Dijkstra / Yen's K-Shortest Paths algorithms
├── Exit.java            # Exit node, extends Node, adds exitName
├── PathCandidate.java   # Immutable path result: distance + ordered node list
├── Graph.java           # Graph registry and structural validator
└── GraphGUI.java        # Swing GUI — visualisation and interaction only
```

### Class responsibilities

| Class | Role |
|---|---|
| `Node` | Stores id, floor, temperature, gas concentration, and passability thresholds. Provides `isPassable()`, `setPassable()`, `findNearestExit()`, `shortestPathTo()`, and `findKShortestPaths()`. |
| `Exit` | Subclass of `Node` with an additional `exitName` field. Unlike `Node`, an `Exit` can be explicitly marked as blocked (e.g. fire, structural damage). |
| `PathCandidate` | Immutable value object holding a `totalDistance` (float) and an unmodifiable `List<Node>` representing one computed path. Implements `Comparable` for use in priority queues. |
| `Graph` | Maintains a `Map<String, Node>` registry. Provides `addNode()`, `getNode()`, `getAllNodes()`, and `validate()` which checks every node has at least one neighbour. |
| `GraphGUI` | Pure presentation layer. Renders nodes, edges, path highlights, and a details panel. Contains no graph algorithm logic. |

---

## Requirements

- Java 11 or later (Java 21 LTS recommended)
- No external libraries — standard JDK only

Install JDK on Ubuntu/Debian:
```bash
sudo apt install openjdk-21-jdk-headless
```

Install JDK on Windows:
Download the installer from https://adoptium.net, choose **Windows x64 .msi**.

---

## Compile & Run

Place all five `.java` files in the same directory, then:

```bash
# Compile (order matters: Node before Exit and PathCandidate, all before GraphGUI)
javac Node.java PathCandidate.java Exit.java Graph.java GraphGUI.java

# Run
java GraphGUI
```

---

## Passability Logic

A node is passable when **all** of the following hold:

1. No manual override is set, **and**
2. `temperature <= temperatureThreshold` (default 60 °C), **and**
3. `gasConcentration <= gasConcentrationThreshold` (default 0.5)

A manual override (set via `setPassable(bool)`) bypasses threshold evaluation
entirely. Call `clearPassableOverride()` to restore threshold-based behaviour.

---

## Pathfinding Algorithms

### Dijkstra (single shortest path)
Used by `shortestPathTo(Node target)` and `findNearestExit()`.
Only traverses nodes where `isPassable()` is true; the target itself is always
reachable as a terminal.
Time complexity: **O((V + E) log V)**

### Yen's K-Shortest Paths
Used by `findKShortestPaths(Node target, int k)`.
Builds on Dijkstra to find K simple (loop-free) paths in ascending order of
total distance. Internally uses a confirmed list **A** and a candidate
priority queue **B**, pruning duplicate paths at each spur iteration.
Time complexity: **O(K · V · (E + V log V))**

> **Note on implementation**: all Dijkstra variants use a typed `NE`
> (NodeEntry) priority-queue record instead of `float[]` arrays indexed by
> `System.identityHashCode()`. This avoids hash-collision bugs that caused
> `NullPointerException` in earlier versions.

---

## GUI Overview

| Area | Description |
|---|---|
| **Toolbar** | Mode toggle buttons, colour legend, and `? Help` button |
| **Canvas** | Interactive graph drawing; click nodes to interact |
| **Details panel** | Shows selected node's id, type, floor, passability, temperature, gas, and any computed paths |
| **Status bar** | Contextual feedback for the last action |

### Interaction modes

**Change Passability** — click any node to toggle its passable state.
The node turns red with a × overlay when impassable. Click again to restore.

**Find Path** — click any node to compute the 3 globally shortest paths from
that node to all reachable passable exits (using Yen's algorithm across all
exits, then taking the top 3 by distance). Paths are drawn as gold / pink /
cyan overlays with distance badges.

Click the `? Help` button in the toolbar for a full in-app guide.

---

## Extending the Graph

To load your own graph instead of the built-in sample, edit `buildSampleGraph()`
in `GraphGUI.java`:

```java
private void buildSampleGraph() {
    Node n1 = new Node("Room1", 0, 22f, 0.02f);
    Exit ex = new Exit("Exit1", "Main Door", 0, true, 20f, 0.01f);
    n1.addBidirectionalNeighbor(ex, 5f);

    nodeList.add(n1);
    nodeList.add(ex);
    positions.put(n1, new Point(200, 300));
    positions.put(ex, new Point(400, 300));
}
```

Alternatively, use `Graph.java` to build and validate the graph programmatically,
then pass its nodes to `GraphGUI`.

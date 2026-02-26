import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents a candidate path produced by shortest-path algorithms.
 * Stores the total edge-weight distance and the ordered sequence of nodes.
 * Implements Comparable so it can be used directly in a PriorityQueue.
 */
public class PathCandidate implements Comparable<PathCandidate> {

    public final float      totalDistance;
    public final List<Node> nodes;

    /**
     * @param totalDistance sum of all edge weights along the path
     * @param nodes         ordered list of nodes from source to target
     */
    public PathCandidate(float totalDistance, List<Node> nodes) {
        this.totalDistance = totalDistance;
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    /** Shorter paths sort first. */
    @Override
    public int compareTo(PathCandidate other) {
        return Float.compare(this.totalDistance, other.totalDistance);
    }

    @Override
    public String toString() {
        return String.format("%.1f  [ %s ]", totalDistance,
                nodes.stream().map(Node::getId).collect(Collectors.joining(" -> ")));
    }
}

/**
 * Represents an exit point in the graph.
 * Unlike a regular Node, an Exit can be blocked (e.g. by fire or structural damage),
 * so its passable state is fully controlled by the caller and not hardcoded.
 */
public class Exit extends Node {

    private final String exitName;

    /**
     * @param id               unique identifier for this exit node
     * @param exitName         human-readable label (e.g. "North Gate")
     * @param floor            floor level this exit is on
     * @param passable         whether this exit is currently usable
     * @param temperature      temperature at this exit
     * @param gasConcentration gas concentration at this exit
     */
    public Exit(String id, String exitName, int floor, boolean passable,
                float temperature, float gasConcentration) {
        super(id, floor, temperature, gasConcentration);
        this.exitName = exitName;
        if (!passable) setPassable(false);
    }

    public String getExitName() { return exitName; }

    @Override
    public String toString() {
        return String.format("Exit{id='%s', name='%s', floor=%d, passable=%b, temp=%.1f, gas=%.2f}",
                getId(), exitName, getFloor(), isPassable(), getTemperature(), getGasConcentration());
    }
}

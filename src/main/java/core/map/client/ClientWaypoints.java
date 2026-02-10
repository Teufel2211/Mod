package core.map.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientWaypoints {
    private static final Map<String, ClientWaypoint> WAYPOINTS = new ConcurrentHashMap<>();

    private ClientWaypoints() {}

    public static void replaceAll(Map<String, ClientWaypoint> waypoints) {
        WAYPOINTS.clear();
        WAYPOINTS.putAll(waypoints);
    }

    public static Map<String, ClientWaypoint> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(WAYPOINTS));
    }
}


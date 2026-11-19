package app.cloudvoyage;

import static app.cloudvoyage.Flight.Airport;

final class FlightFollowCamera {
    private static final long IDLE_MS = 5_000, RETURN_MS = 760;
    final GlobeCamera camera;
    private final double[] start, end;
    private final float zoom;
    private boolean touching;
    private long returnAt, returnStarted;
    private float returnZoom;
    private double[] returnDirection;

    FlightFollowCamera(Airport origin, Airport destination, float zoomLimit) {
        camera = new GlobeCamera(origin, null);
        start = GlobeCamera.vector(origin.lat,origin.lon);
        end = destination == null ? start : GlobeCamera.vector(destination.lat,destination.lon);
        double distance = destination == null ? 0 : Airport.distance(origin,destination);
        zoom = Math.min(zoomLimit,(float)Math.max(36,Math.min(110,95_000 / Math.max(distance,1))));
        camera.lookAt(start,zoom);
    }

    void touchStarted() {
        touching = true;
        returnDirection = null;
    }

    void touchEnded(long now) {
        touching = false;
        returnAt = now + IDLE_MS;
    }

    void recenter(long now) {
        touching = false;
        returnAt = now;
    }

    boolean animating() { return returnDirection != null; }

    void update(float progress, long now, boolean animate) {
        if (touching || now < returnAt) return;
        double[] target = GlobeCamera.arc(start,end,Math.max(0,Math.min(1,progress)));
        if (returnAt != 0) {
            returnAt = 0;
            if (animate) {
                returnDirection = camera.direction();
                returnZoom = camera.zoom;
                returnStarted = now;
                return;
            }
        }
        if (returnDirection != null) {
            float phase = Math.min(1,(now-returnStarted)/(float)RETURN_MS);
            if (phase < 1) {
                float eased = phase*phase*(3-2*phase);
                double[] center = GlobeCamera.arc(returnDirection,target,eased);
                float scale = returnZoom*(float)Math.pow(zoom/returnZoom,eased);
                camera.lookAt(center,scale);
                return;
            }
            returnDirection = null;
        }
        camera.lookAt(target,zoom);
    }
}

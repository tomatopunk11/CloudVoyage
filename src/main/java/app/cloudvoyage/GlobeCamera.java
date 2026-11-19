package app.cloudvoyage;

import static app.cloudvoyage.Flight.Airport;

final class GlobeCamera {
    static final float MAX_ZOOM = 2048;
    private double latitude, longitude;
    private double sinLat, cosLat, sinLon, cosLon;
    float zoom = 1;
    private boolean manuallyAdjusted;

    GlobeCamera(Airport origin, Airport destination) {
        double[] middle = destination == null ? vector(origin.lat, origin.lon)
                : arc(vector(origin.lat, origin.lon), vector(destination.lat, destination.lon), .5);
        setCenter(Math.toDegrees(Math.atan2(middle[2], Math.hypot(middle[0], middle[1]))),
                Math.toDegrees(Math.atan2(middle[1], middle[0])));
    }

    GlobeCamera(GlobeCamera source) {
        setCenter(source.latitude,source.longitude);
        zoom = source.zoom;
    }

    boolean sameView(GlobeCamera other) {
        return latitude == other.latitude && longitude == other.longitude && zoom == other.zoom;
    }

    void textureCoordinates(float east, float north, float front, double[] out) {
        double horizontal = cosLat*front - sinLat*north;
        double z = sinLat*front + cosLat*north;
        double x = cosLon*horizontal - sinLon*east, y = sinLon*horizontal + cosLon*east;
        double u = .5 + Math.atan2(y,x) / (2*Math.PI);
        out[0] = u == 1 ? 0 : u;
        out[1] = .5 - Math.asin(Math.max(-1, Math.min(1,z))) / Math.PI;
    }

    private void setCenter(double lat, double lon) {
        latitude = Math.max(-89.9, Math.min(89.9, lat));
        longitude = ((lon + 180) % 360 + 360) % 360 - 180;
        double latRad = Math.toRadians(latitude), lonRad = Math.toRadians(longitude);
        sinLat = Math.sin(latRad);
        cosLat = Math.cos(latRad);
        sinLon = Math.sin(lonRad);
        cosLon = Math.cos(lonRad);
    }

    float routeZoom(double[][] route, int width, int height, float radius) {
        float maxX = 0, maxY = 0;
        float[] point = new float[3];
        for (double[] sample : route) {
            project(sample, point);
            maxX = Math.max(maxX, Math.abs(point[0]));
            maxY = Math.max(maxY, Math.abs(point[1]));
        }
        float fitX = width * .31f / Math.max(1, radius * maxX);
        float fitY = height * .29f / Math.max(1, radius * maxY);
        return Math.max(1, Math.min(18, Math.min(fitX, fitY)));
    }

    void centerOn(GlobeCamera target) {
        setCenter(target.latitude,target.longitude);
        zoom = 1;
        manuallyAdjusted = false;
    }

    void lookAt(double[] direction, float scale) {
        setCenter(Math.toDegrees(Math.atan2(direction[2],Math.hypot(direction[0],direction[1]))),
                Math.toDegrees(Math.atan2(direction[1],direction[0])));
        zoom = scale;
    }

    double[] direction() { return vector(latitude,longitude); }

    double latitude() { return latitude; }

    boolean followsRoute() { return !manuallyAdjusted; }

    void drag(double east, double north) {
        manuallyAdjusted = true;
        setCenter(latitude + north, longitude + east);
    }

    void zoomBy(float scale) {
        manuallyAdjusted = true;
        zoom = Math.max(1, Math.min(MAX_ZOOM, zoom * scale));
    }

    static double[] vector(double lat, double lon) {
        double phi = Math.toRadians(lat), lambda = Math.toRadians(lon);
        return new double[]{Math.cos(phi)*Math.cos(lambda), Math.cos(phi)*Math.sin(lambda), Math.sin(phi)};
    }

    static double[] arc(double[] a, double[] b, double fraction) {
        double dot = Math.max(-1, Math.min(1, a[0]*b[0] + a[1]*b[1] + a[2]*b[2]));
        double angle = Math.acos(dot);
        if (angle < 1e-6) return a;
        double axisX = b[0] - dot*a[0], axisY = b[1] - dot*a[1], axisZ = b[2] - dot*a[2];
        double length = Math.hypot(Math.hypot(axisX, axisY), axisZ);
        if (length < 1e-9) {
            // Antipodes have infinitely many great circles. Pick a perpendicular axis.
            axisX = -a[1]; axisY = a[0]; axisZ = 0;
            length = Math.hypot(axisX, axisY);
            if (length < 1e-9) { axisX = 1; axisY = 0; length = 1; }
        }
        double along = Math.cos(angle*fraction), across = Math.sin(angle*fraction)/length;
        return new double[]{a[0]*along+axisX*across, a[1]*along+axisY*across, a[2]*along+axisZ*across};
    }

    void project(double[] point, float[] out) {
        project(point[0],point[1],point[2],out);
    }

    void project(double x, double y, double z, float[] out) {
        double horizontal = cosLon*x + sinLon*y;
        out[0] = (float)(cosLon*y - sinLon*x);
        out[1] = (float)(cosLat*z - sinLat*horizontal);
        out[2] = (float)(cosLat*horizontal + sinLat*z);
    }
}

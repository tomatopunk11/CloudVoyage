package app.cloudvoyage;

final class SatelliteProjection {
    private static final double LIMIT = 85.0511287798066;
    private static final double TOP = (90 - LIMIT) / 180;
    private static final double[] MERCATOR = new double[8193];

    static {
        for (int i=0; i<MERCATOR.length; i++) {
            double v = TOP + (1 - 2 * TOP) * i / (MERCATOR.length - 1);
            double latitude = (.5 - v) * Math.PI;
            MERCATOR[i] = .5 - Math.log(Math.tan(Math.PI/4 + latitude/2)) / (2*Math.PI);
        }
    }

    static double tileY(double globeV) {
        if (globeV < TOP || globeV > 1 - TOP) return -1;
        double index = (globeV - TOP) / (1 - 2 * TOP) * (MERCATOR.length - 1);
        int low = Math.min(MERCATOR.length-2,(int)index);
        double v = MERCATOR[low] + (MERCATOR[low+1] - MERCATOR[low]) * (index - low);
        return Math.max(0, Math.min(Math.nextDown(1d), v));
    }

    static int zoom(float radius, double latitude) {
        double factor = Math.max(.25,Math.cos(Math.toRadians(latitude)));
        double pixelsAcross = 2 * Math.PI * radius * factor;
        return Math.max(0, Math.min(16,(int)Math.round(Math.log(pixelsAcross / 512) / Math.log(2))));
    }
}

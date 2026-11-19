package app.cloudvoyage;

final class WindowLayout {
    static final int SEAT_TOUCH_WIDTH = 588;
    final int width, height, margin;
    final boolean twoPanes, stackedTelemetry, wrapChoices;

    WindowLayout(int width, int height, float fontScale) {
        this.width = Math.max(1,width);
        this.height = Math.max(1,height);
        margin = width < 360 ? 12 : width < 600 ? 18 : 24;
        twoPanes = width >= (fontScale >= 1.5f ? 800 : 680);
        stackedTelemetry = width < 350 || fontScale >= 1.3f;
        wrapChoices = width < 345 || fontScale >= 1.35f;
    }

    int contentWidth(int preferred) { return Math.min(width,preferred); }

    int panelWidth() { return Math.min(380,Math.max(260,width*37/100)); }

    int fullReadoutHeight() { return Math.min(170,Math.max(64,height/4)); }

    int seatWidth(float fontScale) {
        return Math.round(SEAT_TOUCH_WIDTH * Math.max(1,Math.min(1.25f,fontScale)));
    }

    int mapHeight(boolean flight) {
        if (twoPanes) return Math.max(160,Math.min(flight?760:690,height-108));
        return Math.max(128,Math.min(flight?560:460,Math.round(height*(flight?.60f:.53f))));
    }
}

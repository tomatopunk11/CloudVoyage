package app.cloudvoyage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import static app.cloudvoyage.Flight.*;

public final class FlightRulesTest {
    public static void main(String[] args) {
        Airport hkg = Airports.find("HKG");
        Airport szx = Airports.find("SZX");
        Airport pvg = Airports.find("PVG");
        Clock start = new Clock(1_700_000_000_000L, 70_000L, 8);

        Session open = new Session(Mode.OPEN,hkg,null,"学习","12A",180*MINUTE,start);
        open.pause(new Clock(start.wall+20*MINUTE,start.mono+20*MINUTE,8));
        equal(20*MINUTE,open.focused(new Clock(start.wall+55*MINUTE,start.mono+55*MINUTE,8)),"Pause stops clock");
        open.resume(new Clock(start.wall+55*MINUTE,start.mono+55*MINUTE,8));
        Ticket trip = open.finish(new Clock(start.wall+80*MINUTE,start.mono+80*MINUTE,8),Airports.ALL);
        equal(45*MINUTE,trip.focused,"Work excludes pause");
        equal(2,trip.segments.size(),"Pause keeps distinct runs");
        equal(25*MINUTE,trip.segments.get(1).duration(),"Second focus segment");
        check(!trip.destination.code.equals(hkg.code),"45-minute trip reaches another airport");
        check(Airport.distance(hkg,trip.destination) <= 585.0,"Airport falls within travel budget");
        for (Airport airport : Airports.ALL) {
            double distance = Airport.distance(hkg,airport);
            check(distance > 585.0 || distance <= Airport.distance(hkg,trip.destination),"Farthest reachable airport");
        }
        Session tiny = new Session(Mode.OPEN,hkg,null,"阅读","12C",5*MINUTE,start);
        Ticket local = tiny.finish(new Clock(start.wall+5000,start.mono+5000,8),Airports.ALL);
        equal(5000L,local.focused,"Short focus is retained");
        check(hkg.code.equals(local.destination.code),"Short focus can land at origin");
        check(Airport.distance(hkg,szx)>30,"Airport coordinate example");
        check(Airport.distance(hkg,pvg)>1000,"Long route distance example");

        Session capped = new Session(Mode.OPEN,hkg,null,"工作","13C",15*MINUTE,start);
        Ticket limited = capped.finish(new Clock(start.wall+360*MINUTE,start.mono+360*MINUTE,8),Airports.ALL);
        equal(15*MINUTE,limited.focused,"Late wake respects max");
        equal(start.wall+15*MINUTE,limited.endedAt(),"Late wake preserves intended end");
        Session reboot = new Session(Mode.TIMED,hkg,szx,"工作","12A",25*MINUTE,start);
        equal(10*MINUTE,reboot.focused(new Clock(start.wall+10*MINUTE,2_500,9)),"Reboot wall-clock recovery");
        equal(5*MINUTE,reboot.focused(new Clock(start.wall-20*MINUTE,start.mono+5*MINUTE,8)),"Same boot ignores wall-clock edit");
        equal("SZX",reboot.finish(new Clock(start.wall+28*MINUTE,2_600,9),Airports.ALL).destination.code,"Timed route remains fixed");

        ZoneId hk=ZoneId.of("Asia/Hong_Kong");
        LocalDate boundary=LocalDate.of(2026,11,1);
        long midnight=midnight(boundary,hk);
        Ticket cross=new Ticket("across",Mode.OPEN,hkg,szx,"学习","12A",40*MINUTE,
                Arrays.asList(new Segment(midnight-20*MINUTE,midnight+10*MINUTE),
                        new Segment(midnight+30*MINUTE,midnight+40*MINUTE)));
        History.Summary before=History.group(Collections.singletonList(cross),boundary.minusDays(1),boundary,hk);
        History.Summary after=History.group(Collections.singletonList(cross),boundary,boundary.plusDays(1),hk);
        equal(20*MINUTE,before.total,"Part before midnight");
        equal(20*MINUTE,after.total,"Part after midnight excludes break");
        equal(1,after.count,"One ticket for two focus intervals on same date");
        equal(2,after.days.get(0).entries.get(0).segments.size(),"Visible split intervals");
        equal(40*MINUTE,History.group(Collections.singletonList(cross),boundary.minusDays(3),
                boundary.plusDays(3),hk).total,"Cross-date aggregate matches total");
        equal(boundary.minusDays(6),Period.WEEK.start(boundary),"Week starts Monday");
        equal(LocalDate.of(2026,12,1),Period.MONTH.step(Period.MONTH.start(boundary),1),"Calendar month navigation");
        ZoneId la=ZoneId.of("America/Los_Angeles");
        LocalDate dst=LocalDate.of(2026,3,8);
        equal(23*HOUR,midnight(dst.plusDays(1),la)-midnight(dst,la),"DST calendar day");
        check(Math.abs(SatelliteProjection.tileY(.5)-.5)<1e-8,"Equator maps to the middle XYZ row");
        check(SatelliteProjection.tileY(.05)<.24 && SatelliteProjection.tileY(.95)>.76,
                "Mercator tiles align north to south");
        check(SatelliteProjection.tileY(0)<0 && SatelliteProjection.tileY(1)<0,
                "Polar cap uses the offline globe");
        check(SatelliteProjection.zoom(200,0)<SatelliteProjection.zoom(20_000,0),
                "Zooming in uses finer satellite imagery");
        globe(hkg);
        flightCamera();
        adaptiveWindows();
        routeFraming(hkg,szx,pvg,Airports.find("LAX"));
        System.out.println("PASS: route inference, pause, deadline, reboot, wall-clock edits, day/week/month, DST, globe geometry, flight following and adaptive windows.");
    }

    private static void globe(Airport origin) {
        GlobeCamera camera = new GlobeCamera(origin,null);
        float[] point = new float[3];
        camera.project(GlobeCamera.vector(origin.lat,origin.lon),point);
        check(Math.abs(point[0])+Math.abs(point[1])<1e-5 && point[2]>.99,"Origin starts centered");
        camera.project(GlobeCamera.vector(-origin.lat,origin.lon+180),point);
        check(point[2]<-.99,"The opposite hemisphere is hidden");
        camera.drag(180,-2*origin.lat);
        camera.project(GlobeCamera.vector(-origin.lat,origin.lon+180),point);
        check(point[2]>.99,"Rotating the globe brings the opposite side forward");
        camera.zoomBy(GlobeCamera.MAX_ZOOM*2);
        equal(GlobeCamera.MAX_ZOOM,camera.zoom,"Zoom has a maximum");
        camera.zoomBy(.5f/GlobeCamera.MAX_ZOOM);
        equal(1f,camera.zoom,"Zoom has a minimum");

        double[] midpoint = GlobeCamera.arc(GlobeCamera.vector(0,179),GlobeCamera.vector(0,-179),.5);
        check(midpoint[0]<-.999 && Math.abs(midpoint[1])<1e-5,"Great circle crosses the date line");
        double[] antipodes = GlobeCamera.arc(GlobeCamera.vector(90,0),GlobeCamera.vector(-90,180),.5);
        check(Math.abs(antipodes[0]-1)<1e-5,"Polar antipodes produce a stable route");

        GlobeCamera dateLine = new GlobeCamera(new Airport("DAT","Date line",0,180),null);
        float[] westTile = new float[3], eastTile = new float[3];
        dateLine.project(-1,Math.sin(-Math.PI),0,westTile);
        dateLine.project(-1,Math.sin(Math.PI),0,eastTile);
        check(Math.abs(westTile[0]-eastTile[0])<1e-6 && westTile[2]>.99 && eastTile[2]>.99,
                "Opposite texture edges meet on the visible date line");
        GlobeCamera copy = new GlobeCamera(dateLine);
        check(copy.sameView(dateLine), "Snapshot starts at the same camera view");
        dateLine.drag(1,0);
        check(!copy.sameView(dateLine), "A gesture does not change the queued snapshot");
        double[] uv = new double[2];
        copy.textureCoordinates(0,0,1,uv);
        check(uv[0] >= 0 && uv[0] < 1 && (uv[0] < 1e-8 || uv[0] > 1-1e-8),
                "Date line maps to a valid wrapped tile index");
        copy.textureCoordinates(0,1,0,uv);
        check(uv[1] == 0, "North pole is the first texture row");
    }
    private static void routeFraming(Airport origin, Airport nearby, Airport regional, Airport overseas) {
        for (int[] size : new int[][]{{360,300},{360,610},{700,360}}) {
            float shortZoom = fittedZoom(origin,nearby,size[0],size[1]);
            float mediumZoom = fittedZoom(origin,regional,size[0],size[1]);
            float longZoom = fittedZoom(origin,overseas,size[0],size[1]);
            check(shortZoom > mediumZoom && mediumZoom > longZoom,
                    "Short routes fit at greater magnification on all viewports");
        }
    }

    private static float fittedZoom(Airport origin, Airport destination, int width, int height) {
        GlobeCamera camera = new GlobeCamera(origin,destination);
        float radius = Math.min(width*.45f,height*.435f);
        double[] start = GlobeCamera.vector(origin.lat,origin.lon);
        double[] end = GlobeCamera.vector(destination.lat,destination.lon);
        double[][] route = new double[97][];
        for (int i=0; i<route.length; i++) route[i]=GlobeCamera.arc(start,end,i/(double)(route.length-1));
        float zoom = camera.routeZoom(route,width,height,radius);
        float[] point = new float[3];
        for (double[] sample : route) {
            camera.project(sample,point);
            check(point[2] >= 0 && Math.abs(point[0])*radius*zoom <= width*.45f
                    && Math.abs(point[1])*radius*zoom <= height*.44f,"Visible great-circle framing");
        }
        return zoom;
    }

    private static void flightCamera() {
        Airport origin = new Airport("WST","West",12,178);
        Airport destination = new Airport("EST","East",13,-177);
        FlightFollowCamera follow = new FlightFollowCamera(origin,destination,GlobeCamera.MAX_ZOOM);
        check(follow.camera.zoom > 18,"Flight follows at a closer zoom than route overview");
        double[] from = GlobeCamera.vector(origin.lat,origin.lon);
        double[] to = GlobeCamera.vector(destination.lat,destination.lon);
        follow.update(.10f,1_000,true);
        assertCentered(follow,GlobeCamera.arc(from,to,.10f),"Flight starts centered on the plane");
        follow.touchStarted();
        follow.camera.drag(-5,1);
        follow.camera.zoomBy(.4f);
        GlobeCamera manual=new GlobeCamera(follow.camera);
        follow.update(.4f,12_000,true);
        check(follow.camera.sameView(manual),"A held touch keeps manual framing");
        follow.touchEnded(12_000);
        follow.update(.45f,16_999,true);
        check(follow.camera.sameView(manual),"Manual framing remains until idle timeout");
        follow.update(.5f,17_000,true);
        check(follow.animating() && follow.camera.sameView(manual),"Return begins from the browsed view");
        follow.update(.6f,17_380,true);
        check(follow.camera.direction()[0]<-.97,"Return takes the short path across the date line");
        follow.update(.7f,17_760,true);
        check(!follow.animating(),"Return animation finishes");
        assertCentered(follow,GlobeCamera.arc(from,to,.7f),"Return targets the latest plane position");
        follow.touchStarted();
        follow.camera.drag(40,10);
        follow.touchEnded(18_000);
        follow.recenter(18_100);
        follow.update(.8f,18_100,false);
        assertCentered(follow,GlobeCamera.arc(from,to,.8f),"Manual recenter immediately follows without animations");
        FlightFollowCamera open = new FlightFollowCamera(origin,null,18);
        open.update(.95f,1_000,true);
        equal(18f,open.camera.zoom,"Offline build uses the available zoom");
        assertCentered(open,from,"Open flight stays at its only known airport");
    }

    private static void assertCentered(FlightFollowCamera follow,double[] target,String message) {
        float[] point=new float[3];
        follow.camera.project(target,point);
        check(Math.abs(point[0])+Math.abs(point[1])<1e-5 && point[2]>.99,message);
    }

    private static void adaptiveWindows() {
        WindowLayout narrow = new WindowLayout(320,550,1);
        check(!narrow.twoPanes && narrow.wrapChoices && narrow.stackedTelemetry,"Narrow handset stacks controls");
        check(narrow.contentWidth(720)-2*narrow.margin<narrow.seatWidth(1),"Narrow handset can scroll the seat chart");
        WindowLayout phone = new WindowLayout(393,800,1);
        check(!phone.twoPanes && !phone.stackedTelemetry,"Regular handset preserves a single column");
        WindowLayout landscape = new WindowLayout(760,375,1);
        check(landscape.twoPanes && landscape.mapHeight(true)<landscape.height,"Landscape splits the map and controls");
        check(landscape.fullReadoutHeight()<=landscape.height/3,"Short full-screen readout leaves room for the aircraft");
        WindowLayout tablet = new WindowLayout(800,1100,1);
        check(tablet.twoPanes && tablet.contentWidth(1320)==800,"Portrait tablet has dedicated space for the map");
        check(tablet.panelWidth() >= 260 && tablet.panelWidth() < tablet.width/2,"Tablet retains a larger map pane");
        WindowLayout tabletWide = new WindowLayout(1366,900,1);
        check(tabletWide.twoPanes && tabletWide.panelWidth()<=380,"Large tablet limits sidebar width");
        WindowLayout split = new WindowLayout(480,750,1);
        check(!split.twoPanes && split.contentWidth(1320)==480,"Tablet split-screen uses available width");
        WindowLayout largeText = new WindowLayout(740,850,2);
        check(!largeText.twoPanes && largeText.stackedTelemetry && largeText.wrapChoices,"Large text receives extra horizontal room");
        check(largeText.seatWidth(2)>=WindowLayout.SEAT_TOUCH_WIDTH,"Larger fonts keep seat chart touch area");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
    private static void equal(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) throw new AssertionError(name+": "+expected+" != "+actual);
    }
}

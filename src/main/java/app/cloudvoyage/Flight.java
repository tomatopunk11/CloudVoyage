package app.cloudvoyage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Flight {
    enum Mode { TIMED, OPEN }
    enum Period {
        DAY("日"), WEEK("周"), MONTH("月");
        final String label;
        Period(String label) { this.label = label; }
        LocalDate start(LocalDate date) {
            switch (this) {
                case WEEK: return date.minusDays(date.getDayOfWeek().getValue() - 1L);
                case MONTH: return date.withDayOfMonth(1);
                default: return date;
            }
        }
        LocalDate step(LocalDate start, int count) {
            switch (this) {
                case WEEK: return start.plusWeeks(count);
                case MONTH: return start.plusMonths(count);
                default: return start.plusDays(count);
            }
        }
    }

    static final long MINUTE = 60_000;
    static final long HOUR = 60 * MINUTE;
    static final double SPEED = 780.0;

    static final class Airport {
        final String code, city;
        final double lat, lon;
        Airport(String code, String city, double lat, double lon) {
            this.code = code; this.city = city; this.lat = lat; this.lon = lon;
        }
        static double distance(Airport a, Airport b) {
            double latitude = Math.toRadians(b.lat - a.lat);
            double longitude = Math.toRadians(b.lon - a.lon);
            double h = Math.pow(Math.sin(latitude / 2), 2)
                    + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat))
                    * Math.pow(Math.sin(longitude / 2), 2);
            return 12_742 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, h))));
        }
        static Airport landing(Airport from, long focused, List<Airport> airports) {
            double budget = focused / (double) HOUR * SPEED;
            Airport destination = from;
            double longest = 0;
            for (Airport airport : airports) {
                double km = distance(from, airport);
                if (km <= budget && km > longest) {
                    destination = airport;
                    longest = km;
                }
            }
            return destination;
        }
    }

    static final class Segment {
        final long start, end;
        Segment(long start, long end) { this.start = start; this.end = end; }
        long duration() { return Math.max(0, end - start); }
        Segment overlap(long first, long last) {
            long left = Math.max(first, start), right = Math.min(last, end);
            return right > left ? new Segment(left, right) : null;
        }
    }

    static final class Clock {
        final long wall, mono;
        final int boot;
        Clock(long wall, long mono, int boot) { this.wall = wall; this.mono = mono; this.boot = boot; }
    }

    static final class Session {
        final String id, task, seat;
        final Mode mode;
        final Airport origin, plannedDestination;
        final long limit;
        final List<Segment> segments;
        long runWall, runMono, accumulated;
        int boot;
        boolean running;

        Session(Mode mode, Airport origin, Airport destination, String task, String seat,
                long limit, Clock now) {
            this(UUID.randomUUID().toString(), mode, origin, destination, task, seat, limit, new ArrayList<>());
            resume(now);
        }
        Session(String id, Mode mode, Airport origin, Airport destination, String task, String seat,
                long limit, List<Segment> segments) {
            this.id = id; this.mode = mode; this.origin = origin; this.plannedDestination = destination;
            this.task = task; this.seat = seat; this.limit = limit; this.segments = segments;
            for (Segment segment : segments) accumulated += segment.duration();
        }
        String number() { return Flight.number(id); }
        long currentRun(Clock now) {
            if (!running) return 0;
            boolean sameBoot = boot >= 0 && boot == now.boot && now.mono >= runMono;
            long elapsed = sameBoot ? now.mono - runMono : now.wall - runWall;
            return Math.max(0, Math.min(limit - accumulated, elapsed));
        }
        long focused(Clock now) { return accumulated + currentRun(now); }
        void pause(Clock now) {
            if (!running) return;
            long elapsed = currentRun(now);
            if (elapsed > 0) segments.add(new Segment(runWall, runWall + elapsed));
            accumulated += elapsed;
            running = false;
        }
        void resume(Clock now) {
            if (running || accumulated >= limit) return;
            runWall = now.wall; runMono = now.mono; boot = now.boot; running = true;
        }
        Ticket finish(Clock now, List<Airport> airports) {
            pause(now);
            if (segments.isEmpty()) return null;
            Airport arrival = mode == Mode.OPEN ? Airport.landing(origin, accumulated, airports) : plannedDestination;
            return new Ticket(id, mode, origin, arrival, task, seat, accumulated, segments);
        }
    }

    static final class Ticket {
        final String id, task, seat;
        final Mode mode;
        final Airport origin, destination;
        final long focused;
        final List<Segment> segments;
        Ticket(String id, Mode mode, Airport origin, Airport destination, String task, String seat,
                long focused, List<Segment> segments) {
            this.id = id; this.mode = mode; this.origin = origin; this.destination = destination;
            this.task = task; this.seat = seat; this.focused = focused;
            this.segments = new ArrayList<>(segments);
        }
        String number() { return Flight.number(id); }
        long endedAt() { return segments.get(segments.size() - 1).end; }
        int kilometers() { return (int) Math.round(Airport.distance(origin, destination)); }
        long total(long first, long last) {
            long duration = 0;
            for (Segment segment : segments) {
                Segment overlap = segment.overlap(first, last);
                if (overlap != null) duration += overlap.duration();
            }
            return duration;
        }
    }

    static String number(String id) { return "CY" + (1000 + Math.floorMod(id.hashCode(), 9000)); }

    static long midnight(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }
    static LocalDate date(long time, ZoneId zone) { return Instant.ofEpochMilli(time).atZone(zone).toLocalDate(); }
}

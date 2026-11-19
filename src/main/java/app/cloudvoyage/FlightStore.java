package app.cloudvoyage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import static app.cloudvoyage.Flight.*;

final class FlightStore extends SQLiteOpenHelper {
    private static FlightStore instance;
    private static Integer bootCount;
    static synchronized FlightStore get(Context context) {
        if (instance == null) instance = new FlightStore(context.getApplicationContext());
        return instance;
    }
    private FlightStore(Context context) { super(context, "voyages.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE active (slot INTEGER PRIMARY KEY CHECK(slot=1), payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE tickets (id TEXT PRIMARY KEY, first INTEGER NOT NULL, last INTEGER NOT NULL,"
                + " payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX tickets_period ON tickets(last, first)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from != 1 || to != 2) throw new IllegalStateException("No migration from schema " + from + " to " + to);
        db.execSQL("ALTER TABLE tickets RENAME TO tickets_v1");
        db.execSQL("CREATE TABLE tickets (id TEXT PRIMARY KEY, first INTEGER NOT NULL, last INTEGER NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("INSERT INTO tickets(id, first, last, payload) SELECT id, first, last, payload FROM tickets_v1");
        db.execSQL("DROP TABLE tickets_v1");
        db.execSQL("CREATE INDEX tickets_period ON tickets(last, first)");
    }
    static Clock clock(Context context) {
        if (bootCount == null) bootCount = Settings.Global.getInt(
                context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        return new Clock(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount);
    }
    synchronized Session active() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT payload FROM active WHERE slot=1", null)) {
            return cursor.moveToFirst() ? decodeSession(new JSONObject(cursor.getString(0))) : null;
        } catch (JSONException e) { throw new IllegalStateException("Cannot decode active flight", e); }
    }
    synchronized void save(Session session) {
        try {
            ContentValues row = new ContentValues();
            row.put("slot", 1); row.put("payload", encodeSession(session).toString());
            getWritableDatabase().insertWithOnConflict("active", null, row, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (JSONException e) { throw new IllegalStateException("Cannot save active flight", e); }
    }
    synchronized Ticket reconcile(Clock now) {
        Session session = active();
        return session != null && session.running && session.focused(now) >= session.limit
                ? complete(session.id, now) : null;
    }
    synchronized Ticket complete(String expectedId, Clock now) {
        Session session = active();
        if (session == null || !expectedId.equals(session.id)) return null;
        Ticket ticket = session.finish(now, Airports.ALL);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (ticket != null) {
                ContentValues row = new ContentValues();
                row.put("id", ticket.id);
                long first=Long.MAX_VALUE, last=Long.MIN_VALUE;
                for(Segment segment: ticket.segments) {
                    first=Math.min(first,segment.start);
                    last=Math.max(last,segment.end);
                }
                row.put("first", first);
                row.put("last", last);
                row.put("payload", common(ticket.id, ticket.mode, ticket.origin, ticket.destination,
                        ticket.task, ticket.seat, ticket.segments).toString());
                db.insertWithOnConflict("tickets", null, row, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.delete("active", "slot=1", null);
            db.setTransactionSuccessful();
        } catch (JSONException e) { throw new IllegalStateException("Cannot save ticket", e); }
        finally { db.endTransaction(); }
        return ticket;
    }
    synchronized void abort(String expectedId) {
        Session session = active();
        if (session != null && expectedId.equals(session.id)) getWritableDatabase().delete("active", "slot=1", null);
    }
    synchronized Ticket ticket(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT payload FROM tickets WHERE id=?", new String[]{id})) {
            return cursor.moveToFirst() ? decodeTicket(new JSONObject(cursor.getString(0))) : null;
        } catch (JSONException e) { throw new IllegalStateException("Cannot read ticket", e); }
    }
    synchronized List<Ticket> tickets(long first, long last) {
        List<Ticket> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT payload FROM tickets WHERE first<? AND last>? ORDER BY last DESC",
                new String[]{String.valueOf(last), String.valueOf(first)})) {
            while (cursor.moveToNext()) {
                result.add(decodeTicket(new JSONObject(cursor.getString(0))));
            }
            return result;
        } catch (JSONException e) { throw new IllegalStateException("Cannot read tickets", e); }
    }

    private static JSONObject airport(Airport airport) throws JSONException {
        return new JSONObject().put("code", airport.code).put("city", airport.city)
                .put("lat", airport.lat).put("lon", airport.lon);
    }
    private static Airport airport(JSONObject json) throws JSONException {
        return new Airport(json.getString("code"), json.getString("city"), json.getDouble("lat"), json.getDouble("lon"));
    }
    private static JSONObject common(String id, Mode mode, Airport from, Airport to, String task,
                                     String seat, List<Segment> segments) throws JSONException {
        JSONArray times = new JSONArray();
        for (Segment segment : segments) times.put(new JSONArray().put(segment.start).put(segment.end));
        return new JSONObject().put("id", id).put("mode", mode.name()).put("origin", airport(from))
                .put("destination", to == null ? JSONObject.NULL : airport(to))
                .put("task", task).put("seat", seat).put("segments", times);
    }
    private static List<Segment> segments(JSONObject json) throws JSONException {
        List<Segment> segments = new ArrayList<>();
        JSONArray data = json.getJSONArray("segments");
        for (int i=0;i<data.length();i++) {
            JSONArray item = data.getJSONArray(i);
            segments.add(new Segment(item.getLong(0), item.getLong(1)));
        }
        return segments;
    }
    private static JSONObject encodeSession(Session session) throws JSONException {
        return common(session.id, session.mode, session.origin, session.plannedDestination, session.task,
                session.seat, session.segments).put("limit", session.limit).put("runWall", session.runWall)
                .put("runMono", session.runMono).put("running", session.running).put("boot", session.boot);
    }
    private static Session decodeSession(JSONObject json) throws JSONException {
        Session session = new Session(json.getString("id"), Mode.valueOf(json.getString("mode")),
                airport(json.getJSONObject("origin")), json.isNull("destination") ? null : airport(json.getJSONObject("destination")),
                json.getString("task"), json.getString("seat"), json.getLong("limit"), segments(json));
        session.running = json.getBoolean("running"); session.runWall = json.getLong("runWall");
        session.runMono = json.getLong("runMono"); session.boot = json.getInt("boot");
        return session;
    }
    private static Ticket decodeTicket(JSONObject json) throws JSONException {
        List<Segment> segments = segments(json);
        long focused = 0;
        for (Segment segment : segments) focused += segment.duration();
        return new Ticket(json.getString("id"), Mode.valueOf(json.getString("mode")),
                airport(json.getJSONObject("origin")), airport(json.getJSONObject("destination")),
                json.getString("task"), json.getString("seat"), focused, segments);
    }
}

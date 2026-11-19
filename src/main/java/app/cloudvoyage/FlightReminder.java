package app.cloudvoyage;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import static app.cloudvoyage.Flight.*;

public final class FlightReminder extends BroadcastReceiver {
    private static final int STATUS = 40;
    private static final int ARRIVED = 41;
    static final String FLIGHT_STATUS = "flight_status";
    private static final String FLIGHT_ARRIVAL = "flight_arrival";
    private static final String ALARM = "app.cloudvoyage.ALARM";
    static final String OPEN_ACTIVE = "app.cloudvoyage.OPEN_ACTIVE";

    static void channels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(FLIGHT_STATUS, "飞行状态", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(FLIGHT_ARRIVAL, "降落提醒", NotificationManager.IMPORTANCE_DEFAULT));
    }
    static boolean exactAllowed(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    private static PendingIntent alarm(Context context, String id) {
        Intent intent = new Intent(context, FlightReminder.class).setAction(ALARM).putExtra("flight", id);
        return PendingIntent.getBroadcast(context, 10, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static PendingIntent open(Context context, String ticketId) {
        Intent intent = new Intent(context, MainActivity.class).putExtra("ticket", ticketId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (ticketId == null) intent.setAction(OPEN_ACTIVE);
        return PendingIntent.getActivity(context, ticketId == null ? 20 : 21, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static void sync(Context context, Session session) {
        channels(context);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        PendingIntent pending = alarm(context, session == null ? null : session.id);
        alarms.cancel(pending);
        if (session == null) {
            notifications.cancel(STATUS);
            return;
        }
        Clock now = FlightStore.clock(context);
        long time = session.focused(now);
        if (session.running) {
            long trigger = now.mono + Math.max(0, session.limit - time);
            if (exactAllowed(context)) {
                try { alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending); }
                catch (SecurityException ignored) { alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending); }
            } else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pending);
        }
        if (!notifications.areNotificationsEnabled()) return;
        String route = session.origin.code + " → " + (session.mode == Mode.OPEN ? "降落后揭晓" : session.plannedDestination.code);
        Notification.Builder notification = new Notification.Builder(context, FLIGHT_STATUS)
                .setSmallIcon(R.drawable.notification_plane).setColor(0xFFFFD689)
                .setContentTitle(session.running ? "飞行中 · " + route : "航班已暂停 · " + route)
                .setContentText(session.task).setOnlyAlertOnce(true).setOngoing(true)
                .setContentIntent(open(context, null));
        if (session.running) {
            boolean countdown = session.mode == Mode.TIMED;
            notification.setWhen(now.wall + (countdown ? session.limit - time : -time))
                    .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(countdown);
        }
        notifications.notify(STATUS, notification.build());
    }
    static void arrived(Context context, Ticket ticket) {
        sync(context, null);
        channels(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.areNotificationsEnabled()) manager.notify(ARRIVED,
                new Notification.Builder(context, FLIGHT_ARRIVAL)
                        .setSmallIcon(R.drawable.notification_plane).setColor(0xFFFFD689)
                        .setContentTitle("已降落 · " + ticket.destination.city)
                        .setContentText(ticket.origin.code + " → " + ticket.destination.code + " · 已存入飞行日志")
                        .setContentIntent(open(context, ticket.id)).setAutoCancel(true).build());
    }
    @Override public void onReceive(Context context, Intent intent) {
        FlightStore store = FlightStore.get(context);
        Session session = store.active();
        if (session == null) { sync(context, null); return; }
        if (ALARM.equals(intent.getAction()) && !session.id.equals(intent.getStringExtra("flight"))) return;
        Ticket ticket = store.reconcile(FlightStore.clock(context));
        if (ticket != null) arrived(context, ticket);
        else sync(context, store.active());
    }
}

package app.cloudvoyage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static app.cloudvoyage.Flight.*;

final class History {
    static final class Entry {
        final Ticket ticket;
        final List<Segment> segments = new ArrayList<>();
        Entry(Ticket ticket) { this.ticket = ticket; }
        long total() {
            long value = 0;
            for (Segment segment : segments) value += segment.duration();
            return value;
        }
    }
    static final class Day {
        final LocalDate date;
        final long first, last;
        final List<Entry> entries = new ArrayList<>();
        long total;
        Day(LocalDate date, ZoneId zone) {
            this.date = date; first = midnight(date, zone); last = midnight(date.plusDays(1), zone);
        }
    }
    static final class Summary {
        final List<Day> days = new ArrayList<>();
        long total;
        int count;
    }
    static Summary group(List<Ticket> tickets, LocalDate first, LocalDate until, ZoneId zone) {
        Summary result = new Summary();
        long low = midnight(first, zone), high = midnight(until, zone);
        for (Ticket ticket : tickets) if (ticket.total(low, high) > 0) result.count++;
        for (LocalDate date = first; date.isBefore(until); date = date.plusDays(1)) {
            Day day = new Day(date, zone);
            for (Ticket ticket : tickets) {
                Entry entry = new Entry(ticket);
                for (Segment segment : ticket.segments) {
                    Segment overlap = segment.overlap(day.first, day.last);
                    if (overlap != null) entry.segments.add(overlap);
                }
                if (entry.segments.isEmpty()) continue;
                entry.segments.sort(Comparator.comparingLong(segment -> segment.start));
                day.total += entry.total();
                day.entries.add(entry);
            }
            day.entries.sort(Comparator.comparingLong(entry -> entry.segments.get(0).start));
            result.total += day.total;
            result.days.add(day);
        }
        return result;
    }
}

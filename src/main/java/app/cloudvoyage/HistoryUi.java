package app.cloudvoyage;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import static app.cloudvoyage.Flight.*;
import static app.cloudvoyage.MainActivity.*;

final class HistoryUi {
    private static final DateTimeFormatter WEEK_DATE=DateTimeFormatter.ofPattern("M 月 d 日");
    private static final DateTimeFormatter FULL_DATE=DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日");
    private static final DateTimeFormatter DATE_TIME=DateTimeFormatter.ofPattern("M 月 d 日 HH:mm:ss");

    static void show(MainActivity ui, LinearLayout body, FlightStore store, Period period,
                     LocalDate anchor) {
        LocalDate first=period.start(anchor), until=period.step(first,1);
        ZoneId zone=ZoneId.systemDefault();
        List<Ticket> tickets=store.tickets(midnight(first,zone),midnight(until,zone));
        History.Summary data=History.group(tickets,first,until,zone);
        ui.section("飞行日志","");
        Button[] tabs=new Button[Period.values().length];
        for(Period item:Period.values()){
            tabs[item.ordinal()]=ui.choice(item.label,item==period,()->ui.changePeriod(item));
        }
        ui.add(body,ui.segmented(tabs),-2,12);
        LinearLayout dateRow=ui.row();
        Button previous=ui.choice("‹",false,()->ui.shiftPeriod(-1));
        previous.setContentDescription("上一"+period.label);
        Button next=ui.choice("›",false,()->ui.shiftPeriod(1));
        next.setContentDescription("下一"+period.label);
        dateRow.addView(previous,new LinearLayout.LayoutParams(ui.dp(48),-2));
        String span=period==Period.DAY?first.format(FULL_DATE)
                :period==Period.MONTH?first.getYear()+" 年 "+first.getMonthValue()+" 月"
                :first.format(WEEK_DATE)+" 至 "+until.minusDays(1).format(WEEK_DATE);
        Button label=ui.choice(span+"  ⌄",false,ui::pickHistoryDate);
        label.setContentDescription(span+"，选择日期");
        dateRow.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        dateRow.addView(next,new LinearLayout.LayoutParams(ui.dp(48),-2));
        ui.add(body,dateRow,-2,7);

        LinearLayout summary=ui.card();
        summary.setBackground(ui.shape(SURFACE,0,18));
        LinearLayout summaryHeader=ui.row();
        summaryHeader.addView(ui.text("专注合计",13,ACCENT,true),new LinearLayout.LayoutParams(0,-2,1));
        summaryHeader.addView(ui.text(data.count+" 趟航班",13,MUTED,false));
        ui.add(summary,summaryHeader,-2,0);
        ui.add(summary,ui.text(MainActivity.duration(data.total),32,TEXT,true),-2,2);
        ui.add(summary,new Chart(ui,data.days),period==Period.DAY?61:88,10);
        ui.add(body,summary,-2,9);
        if(!first.equals(period.start(LocalDate.now()))) {
            ui.add(body,ui.choice("回到当"+period.label,false,ui::currentPeriod),-2,5);
        }

        boolean any=false;
        for(int i=data.days.size()-1;i>=0;i--){
            History.Day day=data.days.get(i);
            boolean heading=false;
            for(History.Entry entry:day.entries){
                any=true;
                if(!heading){
                    String headingText=period==Period.DAY?"航班记录":day.date.format(WEEK_DATE)+"  ·  "+MainActivity.duration(day.total);
                    ui.add(body,ui.text(headingText,15,ACCENT,true),-2,16);
                    heading=true;
                }
                ui.add(body,entryView(ui,entry,day,zone),-2,8);
            }
        }
        if(!any){
            LinearLayout empty=ui.card();
            ui.add(empty,ui.text("这段时间还没有专注记录",16,TEXT,true),-2,0);
            ui.add(empty,ui.text("完成一趟航班后，这里会显示机票和专注时间段",13,MUTED,false),-2,5);
            ui.add(body,empty,-2,13);
        }
    }

    private static LinearLayout entryView(MainActivity ui,History.Entry entry,History.Day day,ZoneId zone){
        Ticket ticket=entry.ticket;
        LinearLayout row=ui.card();
        row.setFocusable(true);row.setClickable(true);
        row.setOnClickListener(view->ui.openTicket(ticket.id));
        LinearLayout title=ui.row();
        TextView route=ui.mono(ticket.origin.code+" → "+ticket.destination.code,21,TEXT);
        title.addView(route,new LinearLayout.LayoutParams(0,-2,1));
        title.addView(ui.text("›",22,ACCENT,true));
        ui.add(row,title,-2,0);
        ui.add(row,ui.text(ticket.origin.city+" → "+ticket.destination.city+"  ·  "+ticket.task,13,MUTED,false),-2,2);
        for(Segment segment:entry.segments){
            String start=Instant.ofEpochMilli(segment.start).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            String end=segment.end==day.last?"24:00:00":Instant.ofEpochMilli(segment.end).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            ui.add(row,ui.mono(start+" – "+end,12,ACCENT),-2,4);
        }
        ui.add(row,ui.text("当日专注 "+MainActivity.duration(entry.total()),12,MUTED,false),-2,5);
        return row;
    }
    static TextView segmentLabel(MainActivity ui,Segment segment) {
        ZoneId zone=ZoneId.systemDefault();
        return ui.text(Instant.ofEpochMilli(segment.start).atZone(zone).format(DATE_TIME)+"  –  "
                +Instant.ofEpochMilli(segment.end).atZone(zone).format(DATE_TIME),13,TEXT,false);
    }

    private static final class Chart extends View {
        private final MainActivity ui;
        private final List<History.Day> days;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Chart(MainActivity ui,List<History.Day> days){
            super(ui);this.ui=ui;this.days=days;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas){
            float bottom=getHeight()-ui.dp(22), w=getWidth();
            paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(ui.dp(10));paint.setColor(MUTED);
            if(days.size()==1){
                History.Day day=days.get(0);
                paint.setColor(EDGE);
                canvas.drawRoundRect(new RectF(0,ui.dp(12),w,bottom),ui.dp(4),ui.dp(4),paint);
                paint.setColor(ACCENT);
                for(History.Entry item:day.entries)for(Segment hit:item.segments){
                    float left=w*(hit.start-day.first)/(day.last-day.first);
                    float right=w*(hit.end-day.first)/(day.last-day.first);
                    canvas.drawRoundRect(new RectF(left,ui.dp(12),Math.min(w,Math.max(left+ui.dp(2),right)),bottom),
                            ui.dp(2),ui.dp(2),paint);
                }
                paint.setColor(MUTED);
                for(int i=0;i<=4;i++){
                    String label=String.format(java.util.Locale.ROOT,"%02d",i*6);
                    float x=Math.max(0,Math.min(w-paint.measureText(label),w*i/4-paint.measureText(label)/2));
                    canvas.drawText(label,x,getHeight()-ui.dp(4),paint);
                }
            } else {
                long max=MINUTE;
                for(History.Day day:days)max=Math.max(max,day.total);
                float slot=w/days.size(), inset=Math.min(ui.dp(5),slot*.18f);
                for(int i=0;i<days.size();i++){
                    History.Day day=days.get(i);
                    float height=Math.max(ui.dp(2),bottom*.90f*day.total/max), x=slot*i+inset;
                    paint.setColor(day.total>0?ACCENT:EDGE);
                    canvas.drawRoundRect(new RectF(x,bottom-height,slot*(i+1)-inset,bottom),ui.dp(2),ui.dp(2),paint);
                    if(days.size()<=7||i==0||i==days.size()-1||day.date.getDayOfMonth()%7==0){
                        paint.setColor(MUTED);String label=String.valueOf(day.date.getDayOfMonth());
                        float textX=Math.max(0,Math.min(w-paint.measureText(label),slot*(i+.5f)-paint.measureText(label)/2));
                        canvas.drawText(label,textX,getHeight()-ui.dp(4),paint);
                    }
                }
            }
        }
    }
}

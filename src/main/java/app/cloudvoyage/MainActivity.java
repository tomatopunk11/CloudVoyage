package app.cloudvoyage;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static app.cloudvoyage.Flight.*;

public final class MainActivity extends Activity {
    static final int BACK=0xFF101015, SURFACE=0xFF1B1C24, EDGE=0xFF393842;
    static final int TEXT=0xFFF4F0EA, MUTED=0xFFB4AEBB;
    static final int ACCENT=0xFFFFCF70, SELECTED=0xFF373029;
    private static final int PLAN=0, SEAT=1, PASS=2, AIR=3, LOG=4, DETAIL=5, ARRIVAL=6, PLAN_MAP=7, AIR_MAP=8;
    private FlightStore store;
    private SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int page=PLAN;
    private boolean window, departing, resumed;
    private Mode mode=Mode.TIMED;
    private Airport origin,destination;
    private boolean explicitDestination;
    private boolean mapSelectsDeparture;
    private GlobeCamera plannerCamera;
    private FlightFollowCamera flyingCamera;
    private String cameraFlightId;
    private int plannerMapReturnScroll;
    private int timedMinutes, openMinutes;
    private String task="学习", seat="12A", selectedTicket, draftId;
    private Period period=Period.DAY;
    private LocalDate anchor=LocalDate.now();
    private Session active;
    private LinearLayout root, body;
    private ScrollView scroll;
    private int historyScroll;
    private WindowLayout layout;
    private HorizontalScrollView seatsViewport;
    private boolean expandedSeats = true;
    private int restoredScroll, restoredSeatX = -1;
    private boolean resizePending;
    private final Runnable resizeLayout = this::applyWindowSize;
    private FlightDialog modal;
    private FlightArt flightArt;
    private TextView clockText, statusText, extraText, mapDistance;
    private ProgressBar progress;
    private final Runnable ticker=new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this,400);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store=FlightStore.get(this);
        prefs=getSharedPreferences("preferences",MODE_PRIVATE);
        origin=Airports.find(prefs.getString("origin","HKG"));
        timedMinutes=prefs.getInt("timed",60);
        openMinutes=prefs.getInt("limit",180);
        destination=Airports.recommend(origin,timedMinutes).get(0);
        if(saved!=null) {
            page=saved.getInt("page"); window=saved.getBoolean("window");
            selectedTicket=saved.getString("ticket");
            mode=Mode.valueOf(saved.getString("mode","TIMED"));
            destination=Airports.find(saved.getString("destination",destination.code));
            explicitDestination=saved.getBoolean("explicit");
            mapSelectsDeparture=saved.getBoolean("mapSelectsDeparture");
            seat=saved.getString("seat","12A"); task=saved.getString("task","学习"); draftId=saved.getString("draft");
            period=Period.valueOf(saved.getString("period","DAY"));
            anchor=LocalDate.ofEpochDay(saved.getLong("anchor",LocalDate.now().toEpochDay()));
            historyScroll=saved.getInt("historyScroll");
            plannerMapReturnScroll=saved.getInt("plannerMapReturnScroll");
            expandedSeats=saved.getBoolean("expandedSeats",true);
            restoredScroll=saved.getInt("scroll");
            restoredSeatX=saved.getInt("seatX",-1);
        }
        active=store.active();
        if(saved==null && active!=null) page=AIR;
        if(active==null && (page==AIR || page==AIR_MAP)) page=PLAN;
        if(Build.VERSION.SDK_INT>=30) getWindow().setDecorFitsSystemWindows(false);
        android.content.res.Configuration configuration=getResources().getConfiguration();
        layout=new WindowLayout(configuration.screenWidthDp,configuration.screenHeightDp,configuration.fontScale);
        setUpRoot();
        if(saved==null) openFromIntent(getIntent());
        render();
        restoreScroll();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page",page); out.putBoolean("window",window);
        out.putString("ticket",selectedTicket); out.putString("mode",mode.name()); out.putString("destination",destination.code);
        out.putBoolean("explicit",explicitDestination); out.putString("seat",seat); out.putString("task",task);
        out.putBoolean("mapSelectsDeparture",mapSelectsDeparture);
        out.putString("draft",draftId); out.putString("period",period.name()); out.putLong("anchor",anchor.toEpochDay());
        out.putInt("historyScroll",page==LOG && scroll!=null?scroll.getScrollY():historyScroll);
        out.putInt("plannerMapReturnScroll",plannerMapReturnScroll);
        out.putBoolean("expandedSeats",expandedSeats);
        out.putInt("scroll",scroll==null?0:scroll.getScrollY());
        out.putInt("seatX",seatsViewport==null?-1:seatsViewport.getScrollX());
    }
    @Override protected void onResume() {
        super.onResume();
        resumed=true;
        reconcile();
        FlightReminder.sync(this,active);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }
    @Override protected void onPause() {
        resumed=false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if(!departing){openFromIntent(intent);render();}
    }
    private void openFromIntent(Intent intent) {
        String ticket=intent.getStringExtra("ticket");
        if(ticket!=null && store.ticket(ticket)!=null) {selectedTicket=ticket;page=ARRIVAL;}
        else if(FlightReminder.OPEN_ACTIVE.equals(intent.getAction()) && active!=null) page=AIR;
    }
    private void setUpRoot() {
        root=column();
        root.setBackgroundColor(BACK);
        if (Build.VERSION.SDK_INT>=30) {
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets system=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                if(root.getPaddingLeft()!=system.left || root.getPaddingTop()!=system.top
                        || root.getPaddingRight()!=system.right || root.getPaddingBottom()!=system.bottom){
                    root.setPadding(system.left,system.top,system.right,system.bottom);
                    scheduleResize();
                }
                return insets;
            });
        }
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(r-l!=or-ol || b-t!=ob-ot)scheduleResize();
        });
        setContentView(root);
        root.requestApplyInsets();
    }
    private void scheduleResize(){
        if(resizePending)return;
        resizePending=true;
        root.post(resizeLayout);
    }

    private void applyWindowSize(){
        resizePending=false;
        if(isDestroyed() || root.getWidth()==0)return;
        float density=getResources().getDisplayMetrics().density;
        int width=Math.round((root.getWidth()-root.getPaddingLeft()-root.getPaddingRight())/density);
        int height=Math.round((root.getHeight()-root.getPaddingTop()-root.getPaddingBottom())/density);
        if(width<=0 || height<=0 || (width==layout.width && height==layout.height))return;
        if(departing || modal!=null)return;
        if(Build.VERSION.SDK_INT>=30 && root.getRootWindowInsets()!=null
                && root.getRootWindowInsets().isVisible(WindowInsets.Type.ime()))return;
        rememberScroll();
        layout=new WindowLayout(width,height,getResources().getConfiguration().fontScale);
        render();
        restoreScroll();
    }

    private void rememberScroll(){
        restoredScroll=scroll==null?0:scroll.getScrollY();
        restoredSeatX=seatsViewport==null?-1:seatsViewport.getScrollX();
        if(page==LOG)historyScroll=restoredScroll;
    }

    private void restoreScroll(){
        ScrollView vertical=scroll;
        HorizontalScrollView horizontal=seatsViewport;
        int restoreY=restoredScroll, restoreX=restoredSeatX;
        restoredScroll=0;
        restoredSeatX=-1;
        if(vertical!=null && restoreY>0)vertical.post(()->vertical.scrollTo(0,restoreY));
        if(horizontal!=null && restoreX>=0)horizontal.post(()->horizontal.scrollTo(restoreX,0));
    }

    private boolean contentHasPanes(){return layout.twoPanes && (page==PLAN || page==AIR);}

    private void navigate(int screen) {
        if (departing) return;
        page=screen;
        render();
    }
    private void render() {
        if (modal != null) modal.dismiss();
        root.removeAllViews();
        scroll=null;seatsViewport=null;
        flightArt=null;clockText=null;statusText=null;extraText=null;mapDistance=null;progress=null;
        if (page==PLAN_MAP || page==AIR_MAP) {
            fullMap(page==AIR_MAP);
            return;
        }
        LinearLayout bar=row();
        bar.setPadding(dp(layout.margin),dp(6),dp(layout.margin),dp(1));
        ImageView mark=new ImageView(this);
        mark.setImageResource(R.drawable.launcher_mark);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markLayout=new LinearLayout.LayoutParams(dp(24),dp(24));
        markLayout.rightMargin=dp(8);
        bar.addView(mark,markLayout);
        TextView brand=text("云上航程",16,TEXT,true);
        bar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button home=choice(active==null?"飞行":"返舱",page==PLAN||page==SEAT||page==PASS||page==AIR,
                ()->navigate(active==null?PLAN:AIR));
        bar.addView(home);
        Button history=choice("日志",page==LOG||page==DETAIL,()->navigate(LOG));
        history.setContentDescription("飞行日志");
        bar.addView(history,leftMargin(4));
        root.addView(centered(bar,screenWidth()));

        scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout center=new FrameLayout(this);
        scroll.addView(center,new ScrollView.LayoutParams(-1,-2));
        body=column();
        body.setPadding(dp(layout.margin),dp(11),dp(layout.margin),dp(22));
        int width=screenWidth();
        FrameLayout.LayoutParams content=new FrameLayout.LayoutParams(width,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        center.addView(body,content);
        switch (page) {
            case SEAT: seatScreen(); break;
            case PASS: passScreen(); break;
            case AIR: flyingScreen(); break;
            case LOG:
                HistoryUi.show(this,body,store,period,anchor);
                scroll.post(()->{if(page==LOG)scroll.scrollTo(0,historyScroll);});
                break;
            case DETAIL: ticketScreen(false); break;
            case ARRIVAL: ticketScreen(true); break;
            default: planner();
        }
    }
    private void planner() {
        LinearLayout heading=row();
        heading.addView(text("下一趟航班",30,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(choice("提醒设置",false,this::reminderSettings));
        add(body,heading,-2,0);
        LinearLayout controls=contentHasPanes()?column():body;
        if(contentHasPanes()) addPanes(plannerMap(),controls,false);
        add(controls,segmented(choice("定时航班",mode==Mode.TIMED,()->switchMode(Mode.TIMED)),
                choice("自由航班",mode==Mode.OPEN,()->switchMode(Mode.OPEN))),-2,9);
        if(!contentHasPanes())add(body,plannerMap(),layout.mapHeight(false),12);
        LinearLayout mapControls=row();
        Button search=choice("搜索机场",false,()->chooseAirport(mode==Mode.OPEN||mapSelectsDeparture));
        mapControls.addView(search,new LinearLayout.LayoutParams(0,-2,1));
        boolean departure=mode==Mode.OPEN || mapSelectsDeparture;
        mapControls.addView(choice("选出发",departure,()->selectMapRole(true)),new LinearLayout.LayoutParams(0,-2,1));
        if(mode==Mode.TIMED)mapControls.addView(choice("选到达",!departure,()->selectMapRole(false)),new LinearLayout.LayoutParams(0,-2,1));
        add(controls,mapControls,-2,2);
        LinearLayout airports=row();
        airports.setBackground(shape(SURFACE,EDGE,17));
        airports.addView(airportControl("出发",origin,()->chooseAirport(true)),new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow=new ImageView(this);
        arrow.setImageResource(R.drawable.notification_plane);
        arrow.setColorFilter(ACCENT);
        arrow.setRotation(90);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        airports.addView(arrow,new LinearLayout.LayoutParams(dp(18),dp(18)));
        airports.addView(airportControl("到达",mode==Mode.OPEN?null:destination,mode==Mode.OPEN?null:()->chooseAirport(false)),
                new LinearLayout.LayoutParams(0,-2,1));
        add(controls,airports,-2,6);
        LinearLayout duration=row();
        TextView caption=text(mode==Mode.OPEN?"最长飞行时长":"专注时长",15,TEXT,true);
        duration.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
        Button time=button(duration(minutes()*MINUTE)+"  ▾",false);
        time.setOnClickListener(v->chooseDuration());
        duration.addView(time); add(controls,duration,-2,10);
        Button[] presets=new Button[4];
        int[] options=mode==Mode.OPEN?new int[]{60,120,180,240}:new int[]{30,60,120,180};
        String[] optionLabels=mode==Mode.OPEN?new String[]{"一小时","两小时","三小时","四小时"}
                :new String[]{"半小时","一小时","两小时","三小时"};
        for(int i=0;i<options.length;i++){
            int value=options[i];
            presets[i]=choice(optionLabels[i],value==minutes(),()->setMinutes(value));
        }
        LinearLayout presetPanel=choiceGrid(presets);
        presetPanel.setBackground(shape(SURFACE,EDGE,13));
        presetPanel.setPadding(dp(4),dp(3),dp(4),dp(3));
        add(controls,presetPanel,-2,4);
        Button board=button("办理值机  →",true);
        board.setOnClickListener(v->navigate(SEAT));
        if(contentHasPanes())add(controls,board,-2,14);
        else footer(board);
    }

    private void addPanes(View panorama, LinearLayout controls, boolean flight){
        LinearLayout panes=row();
        panes.setGravity(Gravity.TOP);
        panes.addView(panorama,new LinearLayout.LayoutParams(0,dp(layout.mapHeight(flight)),1));
        LinearLayout.LayoutParams sidebar=new LinearLayout.LayoutParams(dp(layout.panelWidth()),-2);
        sidebar.leftMargin=dp(18);
        panes.addView(controls,sidebar);
        add(body,panes,-2,12);
    }

    private LinearLayout choiceGrid(Button... buttons){
        LinearLayout grid=column();
        int columns=layout.wrapChoices?2:buttons.length;
        for(int first=0;first<buttons.length;first+=columns){
            LinearLayout group=row();
            for(int i=first;i<Math.min(buttons.length,first+columns);i++){
                LinearLayout.LayoutParams item=new LinearLayout.LayoutParams(0,-2,1);
                if(i>first)item.leftMargin=dp(5);
                group.addView(buttons[i],item);
            }
            add(grid,group,-2,first==0?0:4);
        }
        return grid;
    }

    private FrameLayout plannerMap() {
        Airport to=mode==Mode.TIMED?destination:null;
        boolean firstView=plannerCamera==null;
        if(firstView)plannerCamera=new GlobeCamera(origin,to);
        FrameLayout map=new FrameLayout(this);
        map.setBackground(shape(0xFF0C0C13,0,23));
        map.setClipToOutline(true);
        FlightArt art=plannerArt(to);
        if(firstView && plannerCamera.followsRoute())art.fitRoute();
        map.addView(art,new FrameLayout.LayoutParams(-1,-1));
        map.addView(mapAction("全屏",()->{
            plannerMapReturnScroll=scroll.getScrollY();
            navigate(PLAN_MAP);
        }),mapOverlay(Gravity.TOP|Gravity.END));
        if(to!=null){
            TextView caption=text(Math.round(Airport.distance(origin,to))+" km",12,0xFFF1EAE0,true);
            caption.setPadding(dp(9),dp(5),dp(9),dp(5));
            caption.setBackground(shape(0xE51F2028,0,8));
            FrameLayout.LayoutParams label=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START);
            label.leftMargin=dp(12);label.bottomMargin=dp(38);
            map.addView(caption,label);
        }
        map.addView(mapCredit(art),mapOverlay(Gravity.BOTTOM|Gravity.START));
        return map;
    }

    private FlightArt plannerArt(Airport to) {
        return new FlightArt(this,origin,to,false,plannerCamera,
                airport->setAirport(airport,mode==Mode.OPEN||mapSelectsDeparture,false));
    }

    private LinearLayout mapCredit(FlightArt art) {
        LinearLayout credit=row();
        credit.setBackground(shape(0xF2F4F7F4,0,7));
        credit.setPadding(dp(5),0,dp(6),0);
        ImageView logo=new ImageView(this);
        logo.setImageResource(R.drawable.maptiler_logo);
        logo.setContentDescription("MapTiler");
        logo.setOnClickListener(v->openMapSource("https://www.maptiler.com/"));
        credit.addView(logo,new LinearLayout.LayoutParams(dp(64),dp(23)));
        TextView status=text("离线航图",10,BoardingPass.INK,true);
        status.setPadding(dp(4),dp(3),dp(4),dp(3));
        credit.addView(status);
        TextView osm=text("© OpenStreetMap contributors",9,BoardingPass.INK,false);
        osm.setPadding(dp(4),0,0,0);
        osm.setOnClickListener(v->openMapSource("https://www.openstreetmap.org/copyright"));
        credit.addView(osm);
        logo.setVisibility(View.GONE);
        osm.setVisibility(View.GONE);
        art.onMapStatus((message,online)->{
            String label=online?"© MapTiler":message;
            int visibility=online?View.VISIBLE:View.GONE;
            if (status.getText().toString().equals(label) && logo.getVisibility()==visibility) return;
            status.setText(label);
            status.setOnClickListener(online?v->openMapSource("https://www.maptiler.com/copyright/"):null);
            status.setClickable(online);
            logo.setVisibility(visibility);
            osm.setVisibility(visibility);
        });
        return credit;
    }

    private void openMapSource(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException ignored) {}
    }

    private FrameLayout.LayoutParams mapOverlay(int gravity) {
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,-2,gravity);
        params.setMargins(dp(12),dp(12),dp(12),dp(12));
        return params;
    }

    private ImageView mapIcon(int icon,String label,Runnable action){
        android.widget.ImageButton view=new android.widget.ImageButton(this);
        view.setImageResource(icon);
        view.setContentDescription(label);
        view.setColorFilter(TEXT);
        view.setPadding(dp(13),dp(13),dp(13),dp(13));
        view.setMinimumWidth(dp(48));
        view.setMinimumHeight(dp(48));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                shape(0xE322222C,0x667A7383,15),shape(Color.WHITE,0,15)));
        view.setFocusable(true);
        view.setOnClickListener(v->action.run());
        return view;
    }

    private Button mapAction(String caption,Runnable action) {
        Button control=button(caption,false);
        control.setTextColor(0xFFF1EAE0);
        control.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                shape(0xE322222C,0x667A7383,15),shape(Color.WHITE,0,15)));
        control.setOnClickListener(v->action.run());
        return control;
    }

    private void closeFullMap() {
        if(page==AIR_MAP){navigate(active==null?PLAN:AIR);return;}
        navigate(PLAN);
        scroll.post(()->{if(page==PLAN)scroll.scrollTo(0,plannerMapReturnScroll);});
    }

    private void fullMap(boolean flying) {
        if(flying && active==null){navigate(PLAN);return;}
        FrameLayout map=new FrameLayout(this);
        map.setBackgroundColor(0xFF0C0C13);
        if(flying) flightArt=airArt(active,false);
        else {
            Airport to=mode==Mode.TIMED?destination:null;
            boolean firstView=plannerCamera==null;
            if(firstView)plannerCamera=new GlobeCamera(origin,to);
            flightArt=plannerArt(to);
            if(firstView)flightArt.fitRoute();
        }
        map.addView(flightArt,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout top=row();
        top.addView(mapIcon(R.drawable.map_back,"返回",this::closeFullMap));
        top.addView(mapIcon(R.drawable.map_center,flying?"回到飞机":"回到航线",
                flying?flightArt::centerAircraft:flightArt::resetRoute),leftMargin(8));
        if(flying){
            top.addView(mapIcon(active.running?R.drawable.map_pause:R.drawable.map_play,
                    active.running?"暂停":"继续",this::pauseOrResume),leftMargin(8));
        }
        map.addView(top,mapOverlay(Gravity.TOP|Gravity.START));
        LinearLayout bottom=column();
        bottom.setPadding(dp(14),dp(10),dp(14),dp(12));
        bottom.setBackground(shape(0xED20202A,0x667A7383,20));
        if(flying){
            int pale=0xFFF7F0E8, muted=0xFFC1B9C8;
            extraText=text("",12,muted,true);
            extraText.setLetterSpacing(.08f);
            add(bottom,extraText,-2,0);
            add(bottom,flightReadouts(pale,muted,true),-2,8);
        } else {
            boolean departure=mode==Mode.OPEN||mapSelectsDeparture;
            add(bottom,text(departure?"选择出发机场":"选择到达机场",17,0xFFF7F0E8,true),-2,0);
            add(bottom,text(origin.code+"  →  "+(mode==Mode.TIMED?destination.code:"待揭晓"),
                    13,0xFFC1B9C8,false),-2,2);
            Button search=mapAction("搜索机场",()->chooseAirport(departure));
            if(mode==Mode.TIMED){
                LinearLayout controls=row();
                pair(controls,mapAction(departure?"改选到达":"改选出发",()->selectMapRole(!departure)),search);
                add(bottom,controls,-2,8);
            }else add(bottom,search,-2,8);
        }
        FrameLayout.LayoutParams panel=mapOverlay(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        panel.width=Math.max(dp(48),Math.min(dp(450),dp(layout.width-24)));
        panel.bottomMargin=dp(43);
        ScrollView readoutScroll=new ScrollView(this);
        readoutScroll.setBackground(bottom.getBackground());
        bottom.setBackground(null);
        readoutScroll.addView(bottom,new ScrollView.LayoutParams(-1,-2));
        if(layout.twoPanes && layout.height<570){
            LinearLayout panes=row();
            panes.setGravity(Gravity.TOP);
            panes.addView(map,new LinearLayout.LayoutParams(0,-1,1));
            readoutScroll.setFillViewport(true);
            LinearLayout.LayoutParams aside=new LinearLayout.LayoutParams(dp(layout.panelWidth()),-1);
            aside.leftMargin=dp(6);
            panes.addView(readoutScroll,aside);
            root.addView(panes,new LinearLayout.LayoutParams(-1,0,1));
        }else{
            if(layout.height<470 || layout.stackedTelemetry)panel.height=dp(layout.fullReadoutHeight());
            map.addView(readoutScroll,panel);
            root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
        }
        map.addView(mapCredit(flightArt),mapOverlay(Gravity.BOTTOM|Gravity.START));
        if(flying)updateTelemetry(FlightStore.clock(this));
    }

    private void selectMapRole(boolean departure) {
        if(mapSelectsDeparture==departure || mode==Mode.OPEN)return;
        mapSelectsDeparture=departure;
        if(page==PLAN_MAP){render();return;}
        int top=scroll.getScrollY();
        render();
        scroll.post(()->{if(page==PLAN)scroll.scrollTo(0,top);});
    }

    private void switchMode(Mode next) {
        if(mode==next)return;
        mode=next;
        plannerCamera=null;
        render();
    }

    private void seatScreen() {
        section("办理值机",origin.city+"  →  "+ (mode==Mode.OPEN?"降落后揭晓":destination.city));
        add(body,text("专注任务",14,MUTED,true),-2,18);
        Button[] tasks=new Button[4];
        int taskIndex=0;
        for(String value:new String[]{"学习","工作","阅读","创作"}){
            tasks[taskIndex++]=choice(value,task.equals(value),()->{
                rememberScroll();task=value;render();restoreScroll();
            });
        }
        add(body,choiceGrid(tasks),-2,8);
        TextView selection=text(seatSummary(),14,ACCENT,true);
        selection.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        SeatCabin deck=new SeatCabin(this,seat,value->{
            seat=value;
            selection.setText(seatSummary());
        });
        Button[] jumps=new Button[4];
        for(SeatCabin.Cabin cabin:SeatCabin.Cabin.values()){
            jumps[cabin.ordinal()]=seatJump(cabin.label,deck.cabinAnchor(cabin));
        }
        jumps[3]=seatJump("机翼",deck.wingAnchor());
        LinearLayout deckHeading=row();
        deckHeading.addView(text("选择座位",20,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        int available=layout.contentWidth(720)-layout.margin*2;
        int seatWidth=layout.seatWidth(getResources().getConfiguration().fontScale);
        if(available<seatWidth){
            Button zoom=button(expandedSeats?"看整机":"放大选座",false);
            zoom.setOnClickListener(v->{
                rememberScroll();
                expandedSeats=!expandedSeats;
                restoredSeatX=-1;
                render();
                restoreScroll();
            });
            deckHeading.addView(zoom);
        }
        add(body,deckHeading,-2,19);
        add(body,choiceGrid(jumps),-2,8);
        if(expandedSeats && available<seatWidth){
            deck.setMinimumWidth(dp(seatWidth));
            seatsViewport=new HorizontalScrollView(this);
            seatsViewport.setFillViewport(true);
            seatsViewport.setHorizontalScrollBarEnabled(true);
            seatsViewport.addView(deck,new FrameLayout.LayoutParams(-2,-2));
            add(body,seatsViewport,-2,12);
        }else add(body,deck,-2,12);
        add(body,choice("修改航线",false,()->navigate(PLAN)),-2,12);
        LinearLayout actions=column();
        add(actions,selection,-2,0);
        Button ticket=button("生成登机牌",true);
        ticket.setOnClickListener(v->{draftId=java.util.UUID.randomUUID().toString();navigate(PASS);});
        add(actions,ticket,-2,6);
        ticket.setMinHeight(dp(54));
        footerContent(actions);
    }

    private Button seatJump(String label,View anchor){
        Button jump=button(label,false);
        jump.setPadding(dp(5),dp(6),dp(5),dp(6));
        jump.setOnClickListener(v->{
            android.graphics.Rect bounds=new android.graphics.Rect();
            anchor.getDrawingRect(bounds);
            scroll.offsetDescendantRectToMyCoords(anchor,bounds);
            scroll.smoothScrollTo(0,Math.max(0,bounds.top-dp(8)));
        });
        return jump;
    }

    private String seatSummary(){
        return seat+" · "+SeatCabin.Cabin.of(seat).label+" · "+SeatCabin.seatType(seat)
                +" · "+SeatCabin.wingPosition(seat);
    }

    private void passScreen() {
        section("准备登机","");
        BoardingPass pass=ticketPanel("登机牌",Flight.number(draftId),mode,origin,mode==Mode.OPEN?null:destination,task,seat,
                (mode==Mode.OPEN?"上限 ":"")+duration(minutes()*MINUTE));
        add(body,pass,-2,22);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        Button start=button("撕下票根，起飞  →",true);
        start.setOnClickListener(view->{
            if (departing) return;
            departing=true;
            start.setEnabled(false);
            start.setText("正在起飞");
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            pass.tear(()->{
                departing=false;
                begin();
            });
        });
        add(body,choice("修改座位",false,()->navigate(SEAT)),-2,18);
        footer(start);
    }

    private void begin() {
        active=store.active();
        if(active==null){
            active=new Session(draftId,mode,origin,mode==Mode.OPEN?null:destination,task,seat,
                    minutes()*MINUTE,new ArrayList<>());
            active.resume(FlightStore.clock(this));
            store.save(active);
        }
        FlightReminder.sync(this,active);
        navigate(AIR);
        if (resumed) allowNotificationsOnce();
    }

    private void flyingScreen() {
        if(active==null){navigate(PLAN);return;}
        Session session=active;
        section(session.mode==Mode.OPEN?"自由航班":"定时航班",session.number()+"  ·  "+session.task+"  ·  座位 "+session.seat);
        FrameLayout panorama=new FrameLayout(this);
        panorama.setBackground(shape(0xFF0C0C13,0,23));
        panorama.setClipToOutline(true);
        flightArt=airArt(session,window);
        panorama.addView(flightArt,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout mapActions=row();
        mapActions.addView(mapIcon(session.running?R.drawable.map_pause:R.drawable.map_play,
                session.running?"暂停":"继续",this::pauseOrResume));
        if(!window){
            mapActions.addView(mapIcon(R.drawable.map_center,"回到飞机",flightArt::centerAircraft),leftMargin(8));
            mapActions.addView(mapIcon(R.drawable.map_expand,"全屏",()->navigate(AIR_MAP)),leftMargin(8));
            panorama.addView(mapCredit(flightArt),mapOverlay(Gravity.BOTTOM|Gravity.START));
        }
        panorama.addView(mapActions,mapOverlay(Gravity.TOP|Gravity.START));
        LinearLayout controls=contentHasPanes()?card():body;
        if(contentHasPanes())addPanes(panorama,controls,true);
        else add(body,panorama,layout.mapHeight(true),14);
        add(controls,segmented(choice("航图",!window,()->{window=false;render();}),
                choice("舷窗",window,()->{window=true;render();})),-2,8);
        add(controls,flightReadouts(TEXT,MUTED,false),-2,14);
        extraText=text("",12,ACCENT,true);
        extraText.setLetterSpacing(.045f);
        add(controls,extraText,-2,6);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(EDGE));
        add(controls,progress,5,6);
        Button landing=button(session.mode==Mode.OPEN?"完成并降落":"提前返航",session.mode==Mode.OPEN);
        landing.setOnClickListener(view->endFlight());
        if(contentHasPanes())add(controls,landing,-2,16);
        else footer(landing);
        updateTelemetry(FlightStore.clock(this));
    }

    private LinearLayout flightReadouts(int ink,int secondary,boolean full){
        boolean sidebar=layout.twoPanes && (!full || layout.height<570);
        boolean stacked=layout.stackedTelemetry || (sidebar && layout.panelWidth()<320);
        LinearLayout cells=stacked?column():row();
        LinearLayout time=column();
        statusText=text("",12,secondary,false);
        clockText=mono("",full?29:34,ink);
        tabularReadout(clockText,full?29:34);
        add(time,statusText,-2,0);add(time,clockText,-2,1);
        if(stacked)add(cells,time,-2,0);
        else cells.addView(time,new LinearLayout.LayoutParams(0,-2,1));
        if(active.mode==Mode.TIMED){
            LinearLayout distance=column();
            TextView distanceLabel=text("剩余里程",12,secondary,false);
            mapDistance=mono("",26,ink);
            tabularReadout(mapDistance,26);
            if(!stacked){
                distanceLabel.setGravity(Gravity.END);
                mapDistance.setGravity(Gravity.END);
            }
            add(distance,distanceLabel,-2,0);add(distance,mapDistance,-2,2);
            if(stacked)add(cells,distance,-2,6);
            else cells.addView(distance,new LinearLayout.LayoutParams(0,-2,1));
        }
        return cells;
    }

    private void tabularReadout(TextView label,int size){
        label.setMaxLines(1);
        label.setAutoSizeTextTypeUniformWithConfiguration(14,size,1,android.util.TypedValue.COMPLEX_UNIT_SP);
    }

    private FlightArt airArt(Session session, boolean showWindow) {
        FlightArt art=new FlightArt(this,session.origin,session.plannedDestination,showWindow,null,null);
        if(!showWindow){
            if(!session.id.equals(cameraFlightId) || flyingCamera==null){
                flyingCamera=new FlightFollowCamera(session.origin,session.plannedDestination,FlightArt.zoomLimit());
                cameraFlightId=session.id;
            }
            art.follow(flyingCamera);
        }
        return art;
    }

    private void updateTelemetry(Clock now) {
        if(clockText==null || active==null)return;
        long elapsed=active.focused(now), remaining=active.limit-elapsed;
        clockText.setText(timer(active.mode==Mode.OPEN?elapsed:remaining,active.mode==Mode.TIMED));
        if(active.mode==Mode.OPEN){
            if(statusText!=null)statusText.setText(active.running?"已专注":"已暂停 · 已专注");
            extraText.setText("起点 "+active.origin.city+"   ·   上限 "+duration(active.limit)+"   ·   到达地待揭晓");
        } else {
            if(statusText!=null)statusText.setText(active.running?"剩余时间":"已暂停 · 剩余时间");
            int km=(int)Math.round(Airport.distance(active.origin,active.plannedDestination)*remaining/active.limit);
            String endpoints=active.origin.code+"  →  "+active.plannedDestination.code;
            if(mapDistance!=null){
                extraText.setText(endpoints);
                mapDistance.setText(String.format(java.util.Locale.getDefault(),"%,d km",km));
            }else extraText.setText(endpoints+"   ·   剩余 "+km+" km");
        }
        if(progress!=null)progress.setProgress((int)(elapsed*1000/active.limit));
        if(flightArt!=null){
            flightArt.refreshScene();
            flightArt.progress((float)elapsed/active.limit);
        }
    }
    private void tick() {
        if(active==null)return;
        Clock now=FlightStore.clock(this);
        if(active.running && active.focused(now)>=active.limit){reconcile();return;}
        if(page==AIR||page==AIR_MAP)updateTelemetry(now);
    }
    private void reconcile() {
        String before=active==null?null:active.id;
        Ticket arrived=store.reconcile(FlightStore.clock(this));
        if(arrived!=null)FlightReminder.arrived(this,arrived);
        active=store.active();
        if(arrived==null && before!=null && active==null)arrived=store.ticket(before);
        if(arrived!=null){
            if(page==AIR||page==AIR_MAP||page==PLAN)showArrival(arrived);
            else if(page==LOG)render();
        }else if((page==AIR||page==AIR_MAP)&&active==null)navigate(PLAN);
    }
    private void pauseOrResume() {
        reconcile();if(active==null)return;
        Clock now=FlightStore.clock(this);
        if(active.running)active.pause(now);
        else active.resume(now);
        store.save(active);FlightReminder.sync(this,active);render();
    }
    private void endFlight() {
        reconcile();if(active==null)return;
        if(active.mode==Mode.OPEN){
            Ticket ticket=store.complete(active.id,FlightStore.clock(this));
            active=null;
            if(ticket!=null){FlightReminder.arrived(this,ticket);showArrival(ticket);}
            else {FlightReminder.sync(this,null);navigate(PLAN);}
        } else {
            FlightDialog dialog=showModal("提前返航？","这趟航班不会计入专注日志。",null);
            modalActions(dialog,"继续飞行","确认返航",this::abort);
        }
    }
    private void abort() {
        reconcile();if(active==null)return;
        store.abort(active.id);active=null;FlightReminder.sync(this,null);navigate(PLAN);
    }
    private void showArrival(Ticket ticket){selectedTicket=ticket.id;navigate(ARRIVAL);}

    private void ticketScreen(boolean arrival) {
        Ticket ticket=store.ticket(selectedTicket);
        if(ticket==null){navigate(LOG);return;}
        String title=arrival?(ticket.origin.code.equals(ticket.destination.code)?"已完成本场航班":"已安全降落"):"飞行机票";
        section(title,ticket.kilometers()+" km · 模拟航程");
        FlightArt route=new FlightArt(this,ticket.origin,ticket.destination,false,null,null);
        route.progress(1);
        FrameLayout map=new FrameLayout(this);
        map.setBackground(shape(0xFF0C0C13,0,23));
        map.setClipToOutline(true);
        map.addView(route,new FrameLayout.LayoutParams(-1,-1));
        map.addView(mapCredit(route),mapOverlay(Gravity.BOTTOM|Gravity.START));
        add(body,map,171,12);
        add(body,ticketPanel("飞行机票",ticket.number(),ticket.mode,ticket.origin,ticket.destination,
                ticket.task,ticket.seat,duration(ticket.focused)),-2,12);
        LinearLayout timeline=card();
        add(timeline,text("专注时间段",14,ACCENT,true),-2,0);
        for(Segment segment:ticket.segments)add(timeline,HistoryUi.segmentLabel(this,segment),-2,8);
        add(body,timeline,-2,12);
        Button back=button(arrival?"安排下一趟航班":"返回飞行日志",true);
        back.setOnClickListener(view->navigate(arrival?PLAN:LOG));
        footer(back);
    }

    private BoardingPass ticketPanel(String title,String number,Mode kind,Airport from,Airport to,
                                     String task,String seat,String info) {
        int ink=BoardingPass.INK, secondary=BoardingPass.SECONDARY_INK;
        LinearLayout face=column();face.setPadding(dp(20),dp(15),dp(20),dp(17));
        LinearLayout top=row();
        top.addView(text(title,15,secondary,true),new LinearLayout.LayoutParams(0,-2,1));
        top.addView(mono(number,14,ink));add(face,top,-2,0);
        LinearLayout route=row();
        LinearLayout left=column(),right=column();
        TextView fromCode=mono(from.code,45,ink);
        tabularReadout(fromCode,45);
        add(left,fromCode,-2,0);add(left,text(from.city,14,secondary,false),-2,0);
        TextView code=mono(to==null?"···":to.code,45,ink);code.setGravity(Gravity.END);
        tabularReadout(code,45);
        TextView city=text(to==null?"降落后揭晓":to.city,14,secondary,false);city.setGravity(Gravity.END);
        add(right,code,-2,0);add(right,city,-2,0);
        route.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        ImageView plane=new ImageView(this);
        plane.setImageResource(R.drawable.notification_plane);
        plane.setColorFilter(secondary);
        plane.setRotation(90);
        plane.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        route.addView(plane,new LinearLayout.LayoutParams(dp(23),dp(23)));
        route.addView(right,new LinearLayout.LayoutParams(0,-2,1));add(face,route,-2,17);
        LinearLayout coupon=column();
        coupon.setPadding(dp(20),dp(13),dp(20),dp(15));
        LinearLayout stub=row();
        LinearLayout details=column();
        add(details,text((kind==Mode.OPEN?"自由":"定时")+"航班 · "+task,12,secondary,false),-2,0);
        add(details,text(info,15,ink,true),-2,2);
        stub.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout seatInfo=column();
        TextView label=text("座位",12,secondary,false);label.setGravity(Gravity.END);
        add(seatInfo,label,-2,0);add(seatInfo,mono(seat,19,ink),-2,2);
        stub.addView(seatInfo);
        add(coupon,stub,-2,0);
        add(coupon,new BoardingPass.Barcode(this,number),27,11);
        return new BoardingPass(this,face,coupon);
    }

    void changePeriod(Period value){period=value;historyScroll=0;render();}
    void shiftPeriod(int amount){jumpToDate(period.step(period.start(anchor),amount));}
    void currentPeriod(){jumpToDate(LocalDate.now());}
    void pickHistoryDate(){
        android.widget.DatePicker picker=new android.widget.DatePicker(this);
        picker.init(anchor.getYear(),anchor.getMonthValue()-1,anchor.getDayOfMonth(),null);
        ScrollView calendar=new ScrollView(this);
        calendar.setFillViewport(true);
        calendar.addView(picker,new ScrollView.LayoutParams(-1,-2));
        calendar.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(Math.max(68,Math.min(350,layout.height-170)))));
        FlightDialog dialog=showModal("跳转到日期","",calendar);
        modalActions(dialog,"取消","查看记录",()->jumpToDate(LocalDate.of(
                picker.getYear(),picker.getMonth()+1,picker.getDayOfMonth())));
    }
    private void jumpToDate(LocalDate date){anchor=date;historyScroll=0;render();}
    void openTicket(String id){historyScroll=scroll.getScrollY();selectedTicket=id;navigate(DETAIL);}

    private int minutes(){return mode==Mode.OPEN?openMinutes:timedMinutes;}
    private void setMinutes(int value){
        if(mode==Mode.OPEN){openMinutes=value;prefs.edit().putInt("limit",value).apply();}
        else {
            timedMinutes=value;
            if(!explicitDestination){destination=Airports.recommend(origin,value).get(0);plannerCamera=null;}
            prefs.edit().putInt("timed",value).apply();
        }
        render();
    }
    private void chooseDuration(){
        NumberPicker picker=new NumberPicker(this);
        picker.setMinValue(5);picker.setMaxValue(480);picker.setValue(minutes());
        picker.setWrapSelectorWheel(false);
        picker.setFormatter(value->value+" 分钟");
        LinearLayout box=column();
        box.setGravity(Gravity.CENTER);
        box.addView(picker,new LinearLayout.LayoutParams(dp(165),-2));
        FlightDialog dialog=showModal(mode==Mode.OPEN?"最长飞行时长":"专注时长","选择 5 至 480 分钟",box);
        modalActions(dialog,"取消","确定",()->{
            picker.clearFocus();setMinutes(picker.getValue());
        });
    }
    private void chooseAirport(boolean departure){
        List<Airport> choices=departure?Airports.ALL:Airports.recommend(origin,timedMinutes);
        LinearLayout panel=column();
        EditText search=new EditText(this);
        search.setSingleLine(true);search.setHint("搜索城市或机场三字码");search.setTextSize(16);
        search.setTextColor(TEXT);search.setHintTextColor(MUTED);
        search.setPadding(dp(12),dp(9),dp(12),dp(9));
        search.setBackground(shape(BACK,EDGE,12));
        search.setMinHeight(dp(48));
        panel.addView(search,new LinearLayout.LayoutParams(-1,-2));
        android.widget.ListView list=new android.widget.ListView(this);
        list.setDividerHeight(1);
        LinearLayout.LayoutParams results=new LinearLayout.LayoutParams(-1,0,1);
        results.topMargin=dp(8);
        panel.addView(list,results);
        List<Airport> filtered=new ArrayList<>();
        List<String> labels=new ArrayList<>();
        android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(
                this,android.R.layout.simple_list_item_1,labels);
        list.setAdapter(adapter);
        TextView noMatch=text("没有匹配的机场",14,MUTED,false);
        list.setEmptyView(noMatch);
        panel.addView(noMatch,new LinearLayout.LayoutParams(-1,0,1));
        java.util.function.Consumer<String> refresh=query->{
            String needle=query.trim().toUpperCase(Locale.ROOT);
            filtered.clear();labels.clear();
            for(Airport airport:choices){
                if(!airport.code.contains(needle)&&!airport.city.contains(needle))continue;
                filtered.add(airport);labels.add(airport.code+"   "+airport.city);
            }
            adapter.notifyDataSetChanged();
        };
        refresh.accept("");
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){refresh.accept(s.toString());}
            public void afterTextChanged(Editable editable){}
        });
        panel.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));
        FlightDialog dialog=showModal(departure?"选择出发机场":"选择到达机场","",panel);
        modalActions(dialog,"关闭",null,null);
        list.setOnItemClickListener((p,v,i,id)->{
            Airport airport=filtered.get(i);
            dialog.dismiss();
            setAirport(airport,departure,true);
        });
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.getWindow().setLayout(dialogWidth(),dp(Math.max(145,Math.min(414,layout.height-16))));
    }

    private void setAirport(Airport airport,boolean departure,boolean recenter){
        if(departure){
            if(airport!=origin){
                origin=airport;
                if(!explicitDestination || destination==origin){
                    explicitDestination=false;
                    destination=Airports.recommend(origin,timedMinutes).get(0);
                }
                prefs.edit().putString("origin",origin.code).apply();
            }
        } else {
            if(airport==origin){
                Toast.makeText(this,"请选择不同的到达机场",Toast.LENGTH_SHORT).show();
                return;
            }
            destination=airport;
            explicitDestination=true;
        }
        if(recenter || (plannerCamera!=null && plannerCamera.followsRoute()))plannerCamera=null;
        if(page==PLAN_MAP){render();return;}
        int top=scroll.getScrollY();
        render();
        scroll.post(()->{if(page==PLAN)scroll.scrollTo(0,top);});
    }
    private void allowNotificationsOnce(){
        if(Build.VERSION.SDK_INT>=33 && !prefs.getBoolean("askedNotifications",false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            prefs.edit().putBoolean("askedNotifications",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==20 && active!=null)FlightReminder.sync(this,active);
    }
    private void reminderSettings(){
        boolean notification=getSystemService(NotificationManager.class).areNotificationsEnabled();
        boolean exact=FlightReminder.exactAllowed(this);
        LinearLayout panel=column();
        Button notifications=button("打开系统通知设置",false);
        notifications.setOnClickListener(v->{
            modal.dismiss();
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
        });
        add(panel,notifications,-2,0);
        if(Build.VERSION.SDK_INT>=31&&!exact){
            Button precise=button("设置准点提醒",false);
            precise.setOnClickListener(v->{
                modal.dismiss();
                try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}
                catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"此设备未提供准点提醒设置",Toast.LENGTH_SHORT).show();}
            });
            add(panel,precise,-2,8);
        }
        FlightDialog dialog=showModal("提醒设置",(notification?"通知已开启。":"通知已关闭。")+"\n"
                +(exact?"准点降落提醒已授权。":"锁屏时降落提醒可能延迟，打开应用时会自动结算。"),panel);
        modalActions(dialog,"完成",null,null);
    }

    private final class FlightDialog extends Dialog {
        final LinearLayout panel;

        FlightDialog(){
            super(MainActivity.this);
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            panel=column();
        }
    }

    private FlightDialog showModal(String title,String message,View content){
        if(modal!=null)modal.dismiss();
        FlightDialog dialog=new FlightDialog();
        LinearLayout panel=dialog.panel;
        panel.setPadding(dp(19),dp(16),dp(19),dp(18));
        panel.setBackground(shape(SURFACE,EDGE,26));
        panel.setFocusableInTouchMode(true);
        TextView heading=text(title,22,TEXT,true);
        heading.setAccessibilityHeading(true);
        add(panel,heading,-2,0);
        if(!message.isEmpty())add(panel,text(message,14,MUTED,false),-2,6);
        if(content!=null){
            if(content.getLayoutParams() instanceof LinearLayout.LayoutParams){
                ((LinearLayout.LayoutParams)content.getLayoutParams()).topMargin=dp(14);
                panel.addView(content);
            }else add(panel,content,-2,14);
        }
        panel.requestFocus();
        dialog.setContentView(panel);
        dialog.setOnDismissListener(v->{if(modal==dialog){modal=null;scheduleResize();}});
        dialog.setCanceledOnTouchOutside(true);
        modal=dialog;
        android.view.Window window=dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.46f);
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                |android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        window.setLayout(dialogWidth(),-2);
        return dialog;
    }

    private int dialogWidth(){return dp(Math.max(96,Math.min(460,layout.width-24)));}

    private void modalActions(FlightDialog dialog,String dismiss,String confirm,Runnable action){
        LinearLayout actions=row();
        Button close=button(dismiss,confirm==null);
        close.setOnClickListener(v->dialog.dismiss());
        if(confirm==null)add(actions,close,-2,0);
        else {
            Button accept=button(confirm,true);
            accept.setOnClickListener(v->{dialog.dismiss();action.run();});
            pair(actions,close,accept);
        }
        add(dialog.panel,actions,-2,15);
    }

    @Override public void onBackPressed(){
        if(departing)return;
        switch(page){
            case PLAN_MAP:case AIR_MAP:closeFullMap();break;
            case SEAT:navigate(PLAN);break;
            case PASS:navigate(SEAT);break;
            case DETAIL:navigate(LOG);break;
            case ARRIVAL:navigate(active==null?PLAN:AIR);break;
            case LOG:navigate(active==null?PLAN:AIR);break;
            default:super.onBackPressed();
        }
    }

    private int screenWidth(){return dp(layout.contentWidth(contentHasPanes()?1320:page==SEAT?720:620));}
    private FrameLayout centered(View child,int width){
        FrameLayout frame=new FrameLayout(this);
        frame.addView(child,new FrameLayout.LayoutParams(width,-2,Gravity.CENTER_HORIZONTAL));
        return frame;
    }
    private void footer(Button primary){
        primary.setMinHeight(dp(54));
        footerContent(primary);
    }
    private void footerContent(View child){
        FrameLayout foot=centered(child,Math.max(dp(150),body.getLayoutParams().width-dp(layout.margin*2)));
        foot.setBackgroundColor(BACK);
        foot.setPadding(0,dp(8),0,dp(10));
        root.addView(foot,new LinearLayout.LayoutParams(-1,-2));
    }
    LinearLayout segmented(Button... choices){
        LinearLayout group=row();
        group.setBackground(shape(0xFF191A23,0,14));
        group.setPadding(dp(3),dp(3),dp(3),dp(3));
        for(Button choice:choices){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
            if(group.getChildCount()>0)lp.leftMargin=dp(4);
            group.addView(choice,lp);
        }
        return group;
    }
    private LinearLayout airportControl(String label,Airport airport,Runnable select){
        LinearLayout control=column();
        control.setPadding(dp(14),dp(12),dp(14),dp(12));
        add(control,text(label,12,MUTED,true),-2,0);
        if(airport==null){
            add(control,text("待揭晓",25,TEXT,true),-2,1);
            return control;
        }
        LinearLayout name=row();
        name.addView(mono(airport.code,29,TEXT),new LinearLayout.LayoutParams(0,-2,1));
        name.addView(text("⌄",16,ACCENT,true));
        add(control,name,-2,1);
        add(control,text(airport.city,13,MUTED,false),-2,0);
        control.setContentDescription(label+"机场 "+airport.city+" "+airport.code);
        control.setFocusable(true);
        control.setOnClickListener(view->select.run());
        control.setForeground(new RippleDrawable(ColorStateList.valueOf(0x22FFCF70),null,shape(Color.WHITE,0,14)));
        return control;
    }

    int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    LinearLayout column(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);return box;}
    LinearLayout row(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);return box;
    }
    void add(LinearLayout parent,View child,int height,int top){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,height<0?height:dp(height));
        params.topMargin=dp(top);parent.addView(child,params);
    }
    void pair(LinearLayout row,View left,View right){
        row.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams last=new LinearLayout.LayoutParams(0,-2,1);
        last.leftMargin=dp(7);row.addView(right,last);
    }
    private LinearLayout.LayoutParams leftMargin(int gap){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=dp(gap);return lp;
    }
    GradientDrawable shape(int fill,int border,int radius){
        GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(dp(radius));
        if(border!=0)bg.setStroke(dp(1),border);
        return bg;
    }
    LinearLayout card(){
        LinearLayout card=column();card.setPadding(dp(14),dp(13),dp(14),dp(13));
        card.setBackground(shape(SURFACE,EDGE,18));return card;
    }
    TextView text(String message,float size,int color,boolean bold){
        TextView label=new TextView(this);
        label.setText(message);label.setTextSize(size);label.setTextColor(color);
        label.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        return label;
    }
    TextView mono(String message,float size,int color){
        TextView label=text(message,size,color,true);
        label.setFontFeatureSettings("tnum");
        if(size>=24)label.setLetterSpacing(-.025f);
        return label;
    }
    Button button(String caption,boolean primary){
        Button button=new Button(this);
        button.setAllCaps(false);button.setText(caption);button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        button.setTextColor(primary?0xFF21190F:TEXT);
        button.setMinimumHeight(dp(48));button.setMinHeight(dp(48));
        button.setMinimumWidth(0);button.setMinWidth(0);
        button.setPadding(dp(10),dp(8),dp(10),dp(8));button.setElevation(0);button.setStateListAnimator(null);
        int base=primary?ACCENT:SURFACE, border=primary?0:EDGE;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x37FFFFFF:0x24FFCF70),
                shape(base,border,primary?26:14),shape(Color.WHITE,0,primary?26:14)));
        return button;
    }
    Button choice(String caption,boolean selected,Runnable action){
        Button button=button(caption,false);
        button.setSelected(selected);
        button.setTextColor(selected?ACCENT:TEXT);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24FFCF70),
                shape(selected?SELECTED:Color.TRANSPARENT,0,11),shape(Color.WHITE,0,11)));
        if(action!=null)button.setOnClickListener(v->action.run());
        else {button.setEnabled(false);button.setTextColor(MUTED);}
        return button;
    }
    void section(String title,String subtitle){
        add(body,text(title,30,TEXT,true),-2,2);
        if(!subtitle.isEmpty())add(body,text(subtitle,14,MUTED,false),-2,5);
    }
    static String duration(long value){
        long seconds=Math.max(0,value/1000);
        long hours=seconds/3600, minutes=seconds/60%60;
        if(hours>0)return hours+" 小时"+(minutes==0?"":" "+minutes+" 分钟")+(seconds%60==0?"":" "+seconds%60+" 秒");
        if(minutes>0)return minutes+" 分钟"+(seconds%60==0?"":" "+seconds%60+" 秒");
        return seconds+" 秒";
    }
    static String timer(long value,boolean ceil){
        long seconds=(Math.max(0,value)+(ceil?999:0))/1000;
        if(seconds>=3600)return String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60);
        return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);
    }
}

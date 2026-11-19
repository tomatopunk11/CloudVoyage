package app.cloudvoyage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static app.cloudvoyage.Flight.Airport;

final class FlightArt extends View {
    private static final int INK = 0xFF0B202B, LIGHT = 0xFFF5EFE6, AMBER = 0xFFFFCF70;
    private static final Typeface LABEL = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final double[][] AIRPORT_POINTS = new double[Airports.ALL.size()][];
    private static final String[] SCENES = {"morning", "day", "evening", "night"};
    private static final Bitmap[] PHOTOS = new Bitmap[SCENES.length];

    static {
        for (int i=0; i<Airports.ALL.size(); i++) {
            Airport airport = Airports.ALL.get(i);
            AIRPORT_POINTS[i] = GlobeCamera.vector(airport.lat, airport.lon);
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float[] point = new float[3];
    private final List<AirportMarker> markers = new ArrayList<>();
    private final List<AirportMarker> visibleLabels = new ArrayList<>();
    private final AirportMarker[] airportMarkers = new AirportMarker[AIRPORT_POINTS.length];
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient mapShade;
    private final DashPathEffect dash;
    private final float density;
    private final Airport origin, destination;
    private final Drawable aircraft, airportGlyph;
    private final boolean window;
    private GlobeCamera camera;
    private boolean fitPending;
    private final GlobeTiles globe;
    private final Consumer<Airport> selectAirport;
    private final GestureDetector gestures;
    private final ScaleGestureDetector pinch;
    private final double[][] route = new double[97][];
    private float cx, cy, radius, progress = -1;
    private boolean pinching, touchingMap;
    private FlightFollowCamera follow;
    private final Runnable followFrame = this::updateFollow;
    private int scene = -1;
    private Shader backdrop;
    private java.util.function.BiConsumer<String, Boolean> mapStatus;

    private static final class AirportMarker {
        final Airport airport;
        final RectF label = new RectF();
        float x, y, priority;
        final boolean endpoint;

        AirportMarker(Airport airport, boolean endpoint) {
            this.airport = airport;
            this.endpoint = endpoint;
        }
    }

    FlightArt(Context context, Airport origin, Airport destination, boolean window, GlobeCamera viewport,
            Consumer<Airport> selectAirport) {
        super(context);
        this.origin = origin;
        this.destination = destination;
        this.window = window;
        this.selectAirport = selectAirport;
        density = getResources().getDisplayMetrics().density;
        aircraft = window ? null : context.getDrawable(R.drawable.route_aircraft);
        airportGlyph = window ? null : context.getDrawable(R.drawable.notification_plane).mutate();
        dash = new DashPathEffect(new float[]{dp(3),dp(4)}, 0);
        camera = viewport == null ? new GlobeCamera(origin, destination) : viewport;
        globe = window ? null : new GlobeTiles(this);
        if (window) {
            refreshScene();
            gestures = null;
            pinch = null;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            return;
        }
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(dp(1));
        for(int i=0;i<airportMarkers.length;i++){
            Airport airport=Airports.ALL.get(i);
            airportMarkers[i]=new AirportMarker(airport,airport.code.equals(origin.code)
                    || (destination!=null && airport.code.equals(destination.code)));
        }
        double[] start = GlobeCamera.vector(origin.lat, origin.lon);
        double[] end = destination == null ? start : GlobeCamera.vector(destination.lat, destination.lon);
        for (int i=0; i<route.length; i++) route[i] = GlobeCamera.arc(start, end, i/(double)(route.length-1));

        setContentDescription("三维航图。拖动旋转，双指缩放" + (selectAirport != null ? "。也可以从机场列表选取" : ""));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { pinching = true; return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                camera.zoomBy(detector.getScaleFactor());
                camera.zoom = Math.min(camera.zoom,zoomLimit());
                orbitChanged();
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent start, MotionEvent end, float dx, float dy) {
                if (!pinching) {
                    double scale = 65 / Math.max(1f, radius);
                    double horizontal = scale / Math.max(.18,Math.cos(Math.toRadians(camera.latitude())));
                    camera.drag(dx * horizontal, -dy * scale);
                    orbitChanged();
                }
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (selectAirport == null || !isAttachedToWindow()) return false;
                Airport airport = hitAirport(event.getX(), event.getY());
                if (airport != null) selectAirport.accept(airport);
                return airport != null;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                camera.zoomBy(camera.zoom >= zoomLimit() ? 1/zoomLimit() : 1.6f);
                camera.zoom = Math.min(camera.zoom,zoomLimit());
                orbitChanged();
                return true;
            }
        });
        gestures.setIsLongpressEnabled(false);
    }

    void refreshScene() {
        if (!window) return;
        int hour = java.time.LocalTime.now().getHour();
        int next = hour < 6 || hour >= 20 ? 3 : hour < 10 ? 0 : hour < 17 ? 1 : 2;
        if (scene == next) return;
        scene = next;
        if (PHOTOS[scene] == null) {
            try (InputStream input = getContext().getAssets().open("cabin-" + SCENES[scene] + ".jpg")) {
                PHOTOS[scene] = BitmapFactory.decodeStream(input);
            } catch (IOException e) {
                android.util.Log.w("CloudVoyage", "Unable to read window scene", e);
            }
        }
        invalidate();
    }

    void onMapStatus(java.util.function.BiConsumer<String, Boolean> listener) {
        mapStatus = listener;
    }

    static float zoomLimit() { return MapTilerTiles.configured() ? GlobeCamera.MAX_ZOOM : 18; }

    void follow(FlightFollowCamera controller) {
        follow = controller;
        camera = controller.camera;
        setContentDescription("三维航图，自动跟随飞机。拖动或缩放可暂时自由浏览");
    }

    void centerAircraft() {
        if (follow == null) return;
        follow.recenter(android.os.SystemClock.uptimeMillis());
        updateFollow();
    }

    private void updateFollow() {
        if (follow == null) return;
        follow.update(progress,android.os.SystemClock.uptimeMillis(),android.animation.ValueAnimator.areAnimatorsEnabled());
        orbitChanged();
        removeCallbacks(followFrame);
        if (follow.animating() && isAttachedToWindow()) postDelayed(followFrame,55);
    }

    void fitRoute() {
        fitPending = true;
        if (getWidth() > 0) {
            applyRouteFit();
            orbitChanged();
        }
    }

    private void applyRouteFit() {
        if (!fitPending) return;
        fitPending = false;
        if (destination != null) camera.zoom = camera.routeZoom(route,getWidth(),getHeight(),baseRadius());
    }

    private float baseRadius() { return Math.min(getWidth()*.45f,getHeight()*.435f); }

    private float dp(float value) { return density * value; }
    private void color(int value) { paint.setStyle(Paint.Style.FILL); paint.setColor(value); }

    void progress(float value) {
        boolean advanced = value != progress;
        progress = value;
        if (follow != null) updateFollow();
        else if (advanced) invalidate();
    }

    private void orbitChanged() {
        postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (window) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pinching = false;
            if (follow != null) {
                touchingMap = true;
                follow.touchStarted();
                removeCallbacks(followFrame);
            }
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            pinching = true;
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            gestures.onTouchEvent(cancel);
            cancel.recycle();
        }
        pinch.onTouchEvent(event);
        if (!pinching) gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            finishFollowGesture();
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    private void finishFollowGesture() {
        if (!touchingMap) return;
        touchingMap = false;
        follow.touchEnded(android.os.SystemClock.uptimeMillis());
    }

    @Override protected void onDetachedFromWindow() {
        finishFollowGesture();
        removeCallbacks(followFrame);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (window || w <= 0 || h <= 0) return;
        cx = w*.50f;
        cy = h*.49f;
        backdrop = new LinearGradient(0,0,w,h,0xFF181A2A,0xFF090A0F,Shader.TileMode.CLAMP);
        mapShade = new LinearGradient(0,0,0,h,new int[]{0xA30A0A11,0x080A0A11,0x120A0A11,0xB80A0A11},
                new float[]{0,.24f,.66f,1},Shader.TileMode.CLAMP);
        applyRouteFit();
    }

    void resetRoute() {
        GlobeCamera centered = new GlobeCamera(origin,destination);
        camera.centerOn(centered);
        fitRoute();
    }

    private boolean project(double[] coords, float lift) {
        camera.project(coords,point);
        point[0] = cx+point[0]*radius*lift;
        point[1] = cy-point[1]*radius*lift;
        return point[2] > 0;
    }

    private void grid(Canvas canvas) {
        Path lines = new Path();
        for (int lon=-180; lon<180; lon+=30) {
            boolean visible = false;
            for (int lat=-90; lat<=90; lat+=3) visible = append(lines,GlobeCamera.vector(lat,lon),1,visible);
        }
        for (int lat=-60; lat<=60; lat+=30) {
            boolean visible = false;
            for (int lon=-180; lon<=180; lon+=3) visible = append(lines,GlobeCamera.vector(lat,lon),1,visible);
        }
        stroke(canvas,lines,0x208EE9E3,.7f);
    }

    private boolean append(Path path, double[] coords, float lift, boolean previous) {
        if (!project(coords,lift)) return false;
        if (previous) path.lineTo(point[0],point[1]); else path.moveTo(point[0],point[1]);
        return true;
    }

    private void stroke(Canvas canvas, Path path, int tint, float width) {
        paint.setColor(tint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(width));
        canvas.drawPath(path,paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void route(Canvas canvas, float portion, int tint, boolean dashed) {
        Path path = new Path();
        boolean visible = false;
        int end = (int)(portion*(route.length-1));
        for (int i=0; i<=end; i++) visible = append(path,route[i],lift(i/(float)(route.length-1)),visible);
        if (portion < 1) append(path,along(portion),lift(portion),visible);
        stroke(canvas,path,0x48F9C990,5);
        if (dashed) paint.setPathEffect(dash);
        stroke(canvas,path,tint,dashed?1.45f:2);
        paint.setPathEffect(null);
    }

    private float lift(float fraction) {
        return destination == null ? 1 : 1+Math.min(.075f,.10f/camera.zoom)*(float)Math.sin(Math.PI*fraction);
    }

    private double[] along(float fraction) {
        return GlobeCamera.arc(route[0],route[route.length-1],fraction);
    }

    private boolean onScreen(float x, float y) {
        return x >= 0 && x < getWidth() && y >= 0 && y < getHeight();
    }

    private void drawAirports(Canvas canvas) {
        markers.clear();
        visibleLabels.clear();
        for (int i=0; i<AIRPORT_POINTS.length; i++) {
            AirportMarker marker=airportMarkers[i];
            if (!project(AIRPORT_POINTS[i],1) || !onScreen(point[0],point[1])) continue;
            marker.x=point[0];
            marker.y=point[1];
            marker.priority=(marker.x-cx)*(marker.x-cx)+(marker.y-cy)*(marker.y-cy);
            markers.add(marker);
            color(0xDB111014);
            canvas.drawCircle(marker.x,marker.y,dp(marker.endpoint?6:3.5f),paint);
            color(AMBER);
            canvas.drawCircle(marker.x,marker.y,dp(marker.endpoint?3.8f:2.1f),paint);
        }
        markers.sort(java.util.Comparator.comparing((AirportMarker marker)->!marker.endpoint)
                .thenComparingDouble(marker->marker.priority));
        for (AirportMarker marker : markers) {
            if (marker.endpoint || visibleLabels.size()<Math.max(6,Math.min(20,(int)(getWidth()/dp(30)))))
                drawAirportLabel(canvas,marker);
        }
    }

    private void drawAirportLabel(Canvas canvas, AirportMarker marker) {
        paint.setTypeface(LABEL);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(dp(marker.endpoint?12:10));
        float width=paint.measureText(marker.airport.code)+dp(marker.endpoint?30:24);
        float height=dp(marker.endpoint?23:18);
        float gap=dp(7), margin=dp(5);
        for (int position=0;position<4;position++){
            float left=(position%2==0)?marker.x+gap:marker.x-gap-width;
            float top=(position<2)?marker.y-gap-height:marker.y+gap;
            left=Math.max(margin,Math.min(getWidth()-width-margin,left));
            top=Math.max(margin,Math.min(getHeight()-height-margin,top));
            marker.label.set(left,top,left+width,top+height);
            boolean overlaps=false;
            for (AirportMarker used:visibleLabels) {
                RectF placed=used.label;
                if(left-gap<placed.right && left+width+gap>placed.left && top-gap<placed.bottom
                        && top+height+gap>placed.top) {
                    overlaps=true;
                    break;
                }
            }
            if(overlaps && (position<3 || !marker.endpoint)) continue;
            visibleLabels.add(marker);
            float corner=dp(marker.endpoint?6:4);
            color(marker.endpoint?AMBER:0xE61D1819);
            canvas.drawRoundRect(marker.label,corner,corner,paint);
            outlinePaint.setColor(marker.endpoint?AMBER:0xA8FFCF70);
            canvas.drawRoundRect(marker.label,corner,corner,outlinePaint);
            color(marker.endpoint?0xFF22180E:AMBER);
            int size=Math.round(dp(marker.endpoint?12:9)), iconLeft=Math.round(marker.label.left+dp(5));
            int iconTop=Math.round(marker.label.centerY()-size/2f);
            airportGlyph.setTint(paint.getColor());
            airportGlyph.setBounds(iconLeft,iconTop,iconLeft+size,iconTop+size);
            airportGlyph.draw(canvas);
            float baseline=marker.label.centerY()-(paint.ascent()+paint.descent())/2;
            canvas.drawText(marker.airport.code,marker.label.left+dp(marker.endpoint?20:17),baseline,paint);
            return;
        }
    }

    private Airport hitAirport(float x, float y) {
        for (int i=visibleLabels.size()-1;i>=0;i--){
            AirportMarker marker=visibleLabels.get(i);
            if(marker.label.contains(x,y))return marker.airport;
        }
        Airport nearest=null;
        float best=dp(25)*dp(25);
        for(AirportMarker marker:markers){
            float dx=marker.x-x,dy=marker.y-y;
            float distance=dx*dx+dy*dy;
            if(distance<best){nearest=marker.airport;best=distance;}
        }
        return nearest;
    }

    private void plane(Canvas canvas, float fraction) {
        fraction = Math.max(0,Math.min(1,fraction));
        if (!project(along(fraction),lift(fraction))) return;
        float x = point[0], y = point[1];
        float next = Math.min(1,fraction+.006f), previous = Math.max(0,fraction-.006f);
        project(along(next),lift(next));
        float endX = point[0], endY = point[1];
        project(along(previous),lift(previous));
        canvas.save();
        canvas.translate(x,y);
        if (destination != null) canvas.rotate(90+(float)Math.toDegrees(Math.atan2(endY-point[1],endX-point[0])));
        color(0xBB132B34); canvas.drawCircle(0,0,dp(15),paint);
        int size = Math.round(dp(16));
        aircraft.setBounds(-size,-size,size,size);
        aircraft.draw(canvas);
        canvas.restore();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (window) { drawWindow(canvas); return; }
        if (getWidth() == 0 || getHeight() == 0) return;
        color(0xFFFFFFFF);
        paint.setShader(backdrop);
        canvas.drawRect(0,0,getWidth(),getHeight(),paint);
        paint.setShader(null);
        radius = baseRadius()*camera.zoom;
        GlobeCamera visible = globe.draw(canvas,camera,cx,cy,radius,getWidth(),getHeight());
        if (visible == null) {
            color(LIGHT);
            paint.setTypeface(LABEL);
            paint.setTextSize(dp(14));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("正在绘制航图",cx,cy,paint);
            paint.setTextAlign(Paint.Align.LEFT);
            markers.clear();
            visibleLabels.clear();
            return;
        }
        paint.setShader(mapShade);
        canvas.drawRect(0,0,getWidth(),getHeight(),paint);
        paint.setShader(null);
        if (mapStatus != null) mapStatus.accept(globe.status(),globe.displaysOnline());
        GlobeCamera target = camera;
        camera = visible;
        radius = baseRadius()*visible.zoom;
        try {
            drawMapOverlays(canvas);
        } finally {
            camera = target;
            radius = baseRadius()*target.zoom;
        }
    }

    private void drawMapOverlays(Canvas canvas) {
        if (camera.zoom < 2) grid(canvas);
        if (destination != null && origin != destination) {
            if (progress < 1) route(canvas,1,0xF2EDE9DD,progress>=0);
            if (progress > 0) route(canvas,progress,AMBER,false);
        }
        drawAirports(canvas);
        if (progress >= 0) plane(canvas,progress);
    }

    private void drawWindow(Canvas canvas) {
        float w=getWidth(), h=getHeight();
        paint.setShader(new RadialGradient(w*.5f,h*.43f,Math.max(w,h)*.68f,
                0xFF262730,0xFF08090D,Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);paint.setShader(null);
        float width=Math.min(w*.79f,h*1.02f), height=h*.86f;
        RectF frame=new RectF((w-width)/2,(h-height)/2,(w+width)/2,(h+height)/2);
        float radius=frame.width()*.39f;
        color(0xFF344550);
        paint.setShader(new LinearGradient(frame.left,frame.top,frame.right,frame.bottom,
                0xFFD2CBB9,0xFF282830,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(frame,radius,radius,paint);paint.setShader(null);
        frame.inset(dp(5),dp(5));
        color(0xFF0A121E); canvas.drawRoundRect(frame,radius-dp(3),radius-dp(3),paint);
        frame.inset(dp(6),dp(6));
        Path mask=new Path();mask.addRoundRect(frame,radius-dp(8),radius-dp(8),Path.Direction.CW);
        canvas.save();canvas.clipPath(mask);
        Bitmap photo = PHOTOS[scene];
        if(photo!=null){
            float zoom=Math.max(frame.width()/photo.getWidth(),frame.height()/photo.getHeight());
            float pw=photo.getWidth()*zoom,ph=photo.getHeight()*zoom;
            color(0xFFFFFFFF);
            canvas.drawBitmap(photo,null,new RectF(frame.centerX()-pw/2,frame.centerY()-ph/2,
                    frame.centerX()+pw/2,frame.centerY()+ph/2),paint);
            paint.setShader(new LinearGradient(0,frame.top,0,frame.bottom,
                    new int[]{0x44172332,0x00122035,0x55122035},new float[]{0,.40f,1},Shader.TileMode.CLAMP));
            canvas.drawRect(frame,paint);paint.setShader(null);
        } else {
            paint.setShader(new LinearGradient(0,frame.top,0,frame.bottom,
                    new int[]{0xFF233C5F,0xFF4C6583,0xFFC9936C,0xFF465A73,0xFF19293E},
                    new float[]{0,.24f,.44f,.58f,1},Shader.TileMode.CLAMP));
            canvas.drawRect(frame,paint);paint.setShader(null);
            wing(canvas,frame);
        }
        canvas.restore();
    }
    private void wing(Canvas canvas, RectF frame) {
        float x=frame.left,y=frame.top,w=frame.width(),h=frame.height();
        Path wing=new Path();
        wing.moveTo(x+w*1.08f,y+h*.70f);wing.lineTo(x+w*.35f,y+h*.48f);
        wing.lineTo(x+w*.26f,y+h*.36f);wing.lineTo(x+w*.20f,y+h*.35f);
        wing.lineTo(x+w*.25f,y+h*.50f);wing.lineTo(x+w*1.08f,y+h*.85f);wing.close();
        color(0xFF182639);canvas.drawPath(wing,paint);
    }
}

package app.cloudvoyage;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class SeatCabin extends LinearLayout {

    private static final int WING_FIRST = 17, WING_LAST = 24;
    private static final int RAIL = 36;
    private static final Typeface SEAT_LABEL = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final MainActivity ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path(), wing = new Path();
    private final List<Seat> seats = new ArrayList<>();
    private final View[] rows = new View[47];
    private final View[] headers = new View[Cabin.values().length];
    private LinearGradient hullFill;

    enum Cabin {
        FIRST("头等舱", 1, 2, "A EF K", 0xFF4A3B2E),
        BUSINESS("商务舱", 7, 12, "AB DEF JK", 0xFF333A48),
        ECONOMY("经济舱", 17, 46, "ABC DEFG HJK", 0xFF2F3541);

        final String label;
        final int first, last, tint;
        final String[] blocks;

        Cabin(String label, int first, int last, String layout, int tint) {
            this.label=label; this.first=first; this.last=last; this.tint=tint;
            blocks=layout.split(" ");
        }

        static Cabin of(String code) {
            int number = rowOf(code);
            return number <= FIRST.last ? FIRST : number <= BUSINESS.last ? BUSINESS : ECONOMY;
        }
    }

    SeatCabin(MainActivity ui, String selected, Consumer<String> onSelect) {
        super(ui);
        this.ui = ui;
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        addView(new View(ui),new LayoutParams(-1,ui.dp(98)));
        for (Cabin cabin : Cabin.values()) {
            TextView label=ui.text(cabin.label,13,0xFFF1E9DC,true);
            label.setGravity(Gravity.CENTER);
            label.setAccessibilityHeading(true);
            headers[cabin.ordinal()]=label;
            addView(label,new LayoutParams(-1,ui.dp(42)));
            for (int number=cabin.first; number<=cabin.last; number++) {
                LinearLayout row=ui.row();
                row.setPadding(ui.dp(RAIL),0,ui.dp(RAIL),0);
                rows[number]=row;
                for (int block=0; block<cabin.blocks.length; block++) {
                    if (block>0) row.addView(aisle(Integer.toString(number)));
                    for (char letter : cabin.blocks[block].toCharArray()) {
                        Seat target=new Seat(number+String.valueOf(letter),cabin);
                        seats.add(target);
                        target.setOnClickListener(v -> {
                            select(target.code);
                            target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            onSelect.accept(target.code);
                        });
                        row.addView(target,new LayoutParams(0,-1,1));
                    }
                }
                addView(row,new LayoutParams(-1,ui.dp(cabin==Cabin.ECONOMY?48:56)));
            }
        }
        addView(new View(ui),new LayoutParams(-1,ui.dp(100)));
        select(selected);
    }

    View cabinAnchor(Cabin cabin) { return headers[cabin.ordinal()]; }

    View wingAnchor() { return rows[WING_FIRST]; }

    private TextView aisle(String value) {
        TextView label=ui.mono(value,10,0xFFC1BBC7);
        label.setGravity(Gravity.CENTER);
        label.setLayoutParams(new LayoutParams(ui.dp(18),-1));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return label;
    }

    private static int rowOf(String code) { return Integer.parseInt(code.substring(0,code.length()-1)); }

    static String wingPosition(String code) {
        int row = rowOf(code);
        return row < WING_FIRST ? "机翼前方" : row > WING_LAST ? "机翼后方" : "机翼旁";
    }

    static String seatType(String seat) {
        String[] blocks=Cabin.of(seat).blocks;
        char letter=seat.charAt(seat.length()-1);
        if (letter=='A'||letter=='K') return "靠窗";
        if (blocks[0].endsWith(String.valueOf(letter)) || blocks[1].startsWith(String.valueOf(letter))
                || blocks[1].endsWith(String.valueOf(letter)) || blocks[2].startsWith(String.valueOf(letter))) return "靠过道";
        return "中间座位";
    }

    private void select(String selected) {
        for (Seat item : seats) item.setSelected(item.code.equals(selected));
    }

    private float dp(float value) { return ui.dp(value); }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w,h,oldW,oldH);
        hullFill = new LinearGradient(dp(25),0,w-dp(25),0,
                new int[]{0xFF272934,0xFF30313D,0xFF272934},null,Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        float w=getWidth(), h=getHeight(), mid=w/2f;
        float wall=dp(25), right=w-wall;
        float nose=dp(84), stern=h-dp(96);
        float wingStart=rows[WING_FIRST].getTop(), wingEnd=rows[WING_LAST].getBottom();
        float wingTip=rows[29].getTop();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF171821);
        canvas.drawRoundRect(0,0,w,h,dp(21),dp(21),paint);
        drawWing(canvas,wall,wingStart,wingEnd,wingTip,stern);
        canvas.save();
        canvas.translate(w,0);
        canvas.scale(-1,1);
        drawWing(canvas,wall,wingStart,wingEnd,wingTip,stern);
        canvas.restore();
        paint.setColor(0xFFD4CCD9);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(SEAT_LABEL);
        paint.setTextSize(dp(10));
        float wingLabel = (wingStart + wingEnd) * .5f;
        wingLabel(canvas,wall*.54f,wingLabel,-90);
        wingLabel(canvas,w-wall*.54f,wingLabel,90);

        outline.rewind();
        outline.moveTo(mid,dp(13));
        outline.cubicTo(w*.37f,dp(14),wall,dp(48),wall,nose);
        outline.lineTo(wall,stern);
        outline.cubicTo(wall,stern+dp(29),w*.28f,h-dp(20),mid,h-dp(13));
        outline.cubicTo(w*.72f,h-dp(20),right,stern+dp(29),right,stern);
        outline.lineTo(right,nose);
        outline.cubicTo(right,dp(48),w*.63f,dp(14),mid,dp(13));
        outline.close();

        paint.setShader(hullFill);
        canvas.drawPath(outline,paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.25f));
        paint.setColor(0xFF696575);
        canvas.drawPath(outline,paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFA4BBCF);
        canvas.drawRoundRect(mid-dp(26),dp(41),mid-dp(3),dp(50),dp(3),dp(3),paint);
        canvas.drawRoundRect(mid+dp(3),dp(41),mid+dp(26),dp(50),dp(3),dp(3),paint);

        paint.setColor(0xFF3B3440);
        canvas.drawRect(wall+dp(1),wingStart,right-dp(1),wingEnd,paint);
        paint.setColor(0xFFA78C68);
        canvas.drawRect(wall,wingStart,right,wingStart+dp(1),paint);
        canvas.drawRect(wall,wingEnd-dp(1),right,wingEnd,paint);
        for (Cabin cabin : Cabin.values()) {
            View heading=headers[cabin.ordinal()];
            float top=heading.getTop(), bottom=heading.getBottom();
            paint.setColor(cabin.tint);
            canvas.drawRoundRect(wall+dp(14),top+dp(6),right-dp(14),bottom-dp(6),dp(8),dp(8),paint);
            door(canvas,wall,top+dp(13));
            door(canvas,right,top+dp(13));
            for (int row=cabin.first; row<=cabin.last; row++) {
                float center=rows[row].getTop()+rows[row].getHeight()*.5f;
                window(canvas,wall,center);
                window(canvas,right,center);
            }
        }
        door(canvas,wall,stern+dp(3));
        door(canvas,right,stern+dp(3));
        paint.setColor(0xFF484555);
        canvas.drawRoundRect(mid-dp(26),h-dp(52),mid+dp(26),h-dp(30),dp(5),dp(5),paint);
    }

    private void drawWing(Canvas canvas, float wall, float start, float end, float tip, float stern) {
        wing.rewind();
        wing.moveTo(wall+dp(7),start);
        wing.lineTo(dp(5),start+dp(56));
        wing.lineTo(dp(5),tip);
        wing.lineTo(dp(12),tip+dp(5));
        wing.lineTo(wall+dp(7),end);
        wing.close();
        paint.setColor(0xFF3F4152);
        canvas.drawPath(wing,paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xFF646679);
        paint.setStrokeWidth(dp(1));
        canvas.drawPath(wing,paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF77788F);
        canvas.drawRoundRect(dp(7),start+dp(22),dp(20),start+dp(65),dp(6),dp(6),paint);
        paint.setColor(0xFF4E5063);
        wing.rewind();
        wing.moveTo(wall+dp(21),stern-dp(19));
        wing.lineTo(dp(8),stern+dp(33));
        wing.lineTo(wall+dp(37),stern+dp(26));
        wing.close();
        canvas.drawPath(wing,paint);
    }

    private void wingLabel(Canvas canvas, float x, float y, float degrees) {
        canvas.save();
        canvas.rotate(degrees,x,y);
        canvas.drawText("机翼",x,y+dp(3),paint);
        canvas.restore();
    }

    private void window(Canvas canvas, float x, float y) {
        paint.setColor(0xFF86A1B8);
        canvas.drawRoundRect(x-dp(2),y-dp(6),x+dp(2),y+dp(6),dp(2),dp(2),paint);
    }

    private void door(Canvas canvas, float x, float y) {
        paint.setColor(0xFFD2A873);
        canvas.drawRoundRect(x-dp(3),y-dp(4),x+dp(3),y+dp(13),dp(2),dp(2),paint);
        paint.setColor(0xFF272934);
        canvas.drawRoundRect(x-dp(1),y-dp(2),x+dp(1),y+dp(11),dp(1),dp(1),paint);
    }

    private final class Seat extends View {
        private final String code;
        private final Cabin cabin;

        Seat(String code,Cabin cabin) {
            super(ui);
            this.code = code;
            this.cabin = cabin;
            setFocusable(true);
            setClickable(true);
            setContentDescription(code+"，"+cabin.label+"，"+seatType(code)+"，"+wingPosition(code));
        }

        @Override public void setSelected(boolean selected) {
            super.setSelected(selected);
            invalidate();
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Button");
            info.setCheckable(true);
            info.setChecked(isSelected());
        }

        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth()/2f, y = getHeight()/2f;
            float seatWidth = Math.min(ui.dp(cabin==Cabin.FIRST?42:cabin==Cabin.BUSINESS?30:23),getWidth()-ui.dp(4)), left = x-seatWidth/2, right = x+seatWidth/2;
            float top = y-ui.dp(12), bottom = y+ui.dp(12), radius = ui.dp(5);
            paint.setStyle(Paint.Style.FILL);
            if (isFocused() || isPressed()) {
                paint.setColor(MainActivity.SELECTED);
                canvas.drawRoundRect(left-ui.dp(3),top-ui.dp(3),right+ui.dp(3),bottom+ui.dp(3),radius,radius,paint);
            }
            paint.setColor(isSelected()?MainActivity.ACCENT:cabin.tint);
            canvas.drawRoundRect(left,top,right,bottom,radius,radius,paint);
            paint.setColor(isSelected()?MainActivity.ACCENT:0xFF707385);
            paint.setStrokeWidth(ui.dp(1));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRoundRect(left,top,right,bottom,radius,radius,paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(isSelected()?0xFF99734B:0xFF595B70);
            canvas.drawRoundRect(left+ui.dp(3),bottom-ui.dp(6),right-ui.dp(3),bottom-ui.dp(3),ui.dp(2),ui.dp(2),paint);
            canvas.drawRoundRect(left-ui.dp(2),y-ui.dp(3),left+ui.dp(2),bottom-ui.dp(1),ui.dp(2),ui.dp(2),paint);
            canvas.drawRoundRect(right-ui.dp(2),y-ui.dp(3),right+ui.dp(2),bottom-ui.dp(1),ui.dp(2),ui.dp(2),paint);
            if (isSelected()) {
                paint.setColor(0xFF20190D);
                paint.setStrokeWidth(ui.dp(1.6f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(x-ui.dp(5),y-ui.dp(3),x-ui.dp(1),y+ui.dp(1),paint);
                canvas.drawLine(x-ui.dp(1),y+ui.dp(1),x+ui.dp(5),y-ui.dp(6),paint);
            } else {
                paint.setColor(0xFFE3DDE6);
                paint.setTypeface(SEAT_LABEL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(ui.dp(10));
                canvas.drawText(code.substring(code.length()-1),x,y+ui.dp(2),paint);
            }
        }
    }
}

package app.cloudvoyage;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

final class BoardingPass extends LinearLayout {
    static final int INK = 0xFF1E1B20;
    static final int SECONDARY_INK = 0xFF5F555D;
    private final MainActivity ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path fragment = new Path();
    private final View perforation;
    private float progress;
    private final DashPathEffect dash;
    private ValueAnimator animator;
    private static final long TEAR_DURATION_MS = 2200;
    private static final float TENSION_END = .12f, TEAR_END = .74f;

    BoardingPass(MainActivity ui, View face, View stub) {
        super(ui);
        this.ui = ui;
        setOrientation(VERTICAL);
        addView(face, new LayoutParams(-1, -2));
        perforation = new View(ui);
        addView(perforation, new LayoutParams(-1, ui.dp(2)));
        addView(stub, new LayoutParams(-1, -2));
        dash = new DashPathEffect(new float[]{ui.dp(4), ui.dp(4)}, 0);
    }

    static final class Barcode extends View {
        private final Paint bars = new Paint();
        private final byte[] widths = new byte[69];

        Barcode(MainActivity ui,String number) {
            super(ui);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            java.util.Random random=new java.util.Random(number.hashCode());
            for(int i=0;i<widths.length;i++)widths[i]=(byte)(1+random.nextInt(3));
            bars.setColor(INK);
        }

        @Override protected void onDraw(Canvas canvas) {
            int units=0;
            for(byte width:widths)units+=width;
            float unit=getWidth()/(float)units;
            int position=0;
            for(int i=0;i<widths.length;i++){
                if(i%2==0)canvas.drawRect(Math.round(unit*position),0,
                        Math.round(unit*(position+widths[i])),getHeight(),bars);
                position+=widths[i];
            }
        }
    }

    void tear(Runnable onComplete) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            onComplete.run();
            return;
        }
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(TEAR_DURATION_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;
            @Override public void onAnimationCancel(Animator animation) { canceled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                animator = null;
                if (!canceled && isAttachedToWindow()) onComplete.run();
            }
        });
        animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        float width = getWidth(), height = getHeight();
        float seam = perforation.getTop() + perforation.getHeight() / 2f;
        outline.reset();
        outline.setFillType(Path.FillType.EVEN_ODD);
        outline.addRoundRect(new RectF(0, 0, width, height), ui.dp(19), ui.dp(19), Path.Direction.CW);
        outline.addCircle(0, seam, ui.dp(7), Path.Direction.CW);
        outline.addCircle(width, seam, ui.dp(7), Path.Direction.CW);
        if (progress == 0) {
            drawPaper(canvas, seam);
            return;
        }
        if (progress <= TENSION_END) {
            canvas.save();
            canvas.rotate(-.9f * progress/TENSION_END,width,seam);
            drawPaper(canvas,seam);
            canvas.restore();
            return;
        }
        float peel = Math.min(1, (progress - TENSION_END) / (TEAR_END - TENSION_END));
        float tornWidth = width * (peel*peel*(3-2*peel));
        float release = Math.max(0, (progress - TEAR_END) / (1 - TEAR_END));
        makeFragment(fragment, tornWidth, seam, height);
        canvas.save();
        canvas.clipOutPath(fragment);
        drawPaper(canvas, seam);
        canvas.restore();

        canvas.save();
        canvas.translate(ui.dp(21)*release, ui.dp(6)*peel + ui.dp(66)*release*release);
        canvas.rotate(-4*peel + 10*release, tornWidth, seam);
        int layer = canvas.saveLayerAlpha(0, 0, width, height, (int)(255 * (1 - release*release)));
        canvas.clipPath(fragment);
        drawPaper(canvas, seam);
        canvas.restoreToCount(layer);
        canvas.restore();
    }

    private void makeFragment(Path path, float end, float seam, float height) {
        path.reset();
        path.moveTo(0, seam);
        int spacing = Math.max(1,ui.dp(4));
        for (int x = spacing; x < end; x += spacing) {
            float depth = (x / spacing % 4 == 0 ? 1.7f : 1f)*Math.min(1,(end-x)/spacing);
            path.lineTo(x, seam + (x % (spacing * 2) == 0 ? ui.dp(depth) : -ui.dp(depth)));
        }
        path.lineTo(end, seam);
        path.lineTo(end, height);
        path.lineTo(0, height);
        path.close();
    }

    private void drawPaper(Canvas canvas, float seam) {
        canvas.save();
        canvas.clipPath(outline);
        paint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                new int[]{0xFFFFFCF3, 0xFFF8F2E8, 0xFFEDE4D8}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
        paint.setColor(0x156E5F61);
        paint.setStrokeWidth(1);
        for (int y = ui.dp(3); y < getHeight(); y += ui.dp(5)) {
            canvas.drawLine(0, y, getWidth(), y, paint);
        }
        paint.setColor(0x907D6B65);
        paint.setPathEffect(dash);
        canvas.drawLine(ui.dp(8), seam, getWidth() - ui.dp(8), seam, paint);
        paint.setPathEffect(null);
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}

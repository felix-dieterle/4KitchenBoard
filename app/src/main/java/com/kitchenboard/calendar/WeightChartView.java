package com.kitchenboard.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Custom View that renders a weight-progress diagram for one person.
 *
 * <p>Features:
 * <ul>
 *   <li>Actual weight data points connected by a line</li>
 *   <li>Linear-regression trend line (recommended progress)</li>
 *   <li>BMI reference / critical-marker horizontal lines (requires height)</li>
 *   <li>Healthy-BMI green band (BMI 18.5 – 25)</li>
 * </ul>
 */
public class WeightChartView extends View {

    // BMI thresholds
    private static final float BMI_UNDERWEIGHT    = 18.5f;
    private static final float BMI_NORMAL_UPPER   = 25.0f;
    private static final float BMI_OVERWEIGHT     = 30.0f;

    private float marginLeft;
    private float marginRight;
    private float marginTop;
    private float marginBottom;
    private float density;

    private final Paint paintAxis       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintGrid       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintDataLine   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintDataPoint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintTrend      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintBmi        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHealthyBand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLabel      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintBmiLabel   = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<WeightEntry> entries = new ArrayList<>();
    /** Height in cm – 0 means BMI lines are not drawn. */
    private int heightCm = 0;
    /** Color used for the actual-weight line/dots (taken from the person's color). */
    private int dataColor = Color.parseColor("#1E88E5");

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public WeightChartView(Context context) {
        super(context);
        init(context);
    }

    public WeightChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public WeightChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        marginLeft   = 56 * density;
        marginRight  = 16 * density;
        marginTop    = 16 * density;
        marginBottom = 40 * density;

        paintAxis.setColor(Color.parseColor("#CCCCCC"));
        paintAxis.setStrokeWidth(1.5f * density);
        paintAxis.setStyle(Paint.Style.STROKE);

        paintGrid.setColor(Color.parseColor("#EEEEEE"));
        paintGrid.setStrokeWidth(1f * density);
        paintGrid.setStyle(Paint.Style.STROKE);

        paintDataLine.setColor(dataColor);
        paintDataLine.setStrokeWidth(2.5f * density);
        paintDataLine.setStyle(Paint.Style.STROKE);
        paintDataLine.setStrokeJoin(Paint.Join.ROUND);
        paintDataLine.setStrokeCap(Paint.Cap.ROUND);

        paintDataPoint.setColor(dataColor);
        paintDataPoint.setStyle(Paint.Style.FILL);

        paintTrend.setColor(Color.parseColor("#FF9800"));
        paintTrend.setStrokeWidth(2f * density);
        paintTrend.setStyle(Paint.Style.STROKE);
        paintTrend.setPathEffect(new DashPathEffect(new float[]{10 * density, 6 * density}, 0));

        paintBmi.setStrokeWidth(1.5f * density);
        paintBmi.setStyle(Paint.Style.STROKE);
        paintBmi.setPathEffect(new DashPathEffect(new float[]{8 * density, 4 * density}, 0));

        paintHealthyBand.setStyle(Paint.Style.FILL);
        paintHealthyBand.setColor(Color.argb(40, 67, 160, 71)); // light green

        paintLabel.setColor(Color.parseColor("#555555"));
        paintLabel.setTextSize(11 * density);
        paintLabel.setAntiAlias(true);

        paintBmiLabel.setTextSize(10 * density);
        paintBmiLabel.setAntiAlias(true);
    }

    /** Sets the data to display; call {@link #invalidate()} after if the view is already shown. */
    public void setData(List<WeightEntry> entries, int heightCm, int personColor) {
        this.entries  = entries != null ? entries : new ArrayList<>();
        this.heightCm = heightCm;
        this.dataColor = personColor;

        paintDataLine.setColor(personColor);
        paintDataPoint.setColor(personColor);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float chartLeft   = marginLeft;
        float chartRight  = w - marginRight;
        float chartTop    = marginTop;
        float chartBottom = h - marginBottom;
        float chartW      = chartRight - chartLeft;
        float chartH      = chartBottom - chartTop;

        if (chartW <= 0 || chartH <= 0) return;

        // ── Determine Y range ───────────────────────────────────────────────
        float minY, maxY;
        if (entries.isEmpty()) {
            minY = 50f;
            maxY = 100f;
        } else {
            minY = entries.get(0).getWeightKg();
            maxY = entries.get(0).getWeightKg();
            for (WeightEntry e : entries) {
                if (e.getWeightKg() < minY) minY = e.getWeightKg();
                if (e.getWeightKg() > maxY) maxY = e.getWeightKg();
            }
        }

        // Extend Y range to include BMI lines if height is set
        if (heightCm > 0) {
            float hM = heightCm / 100f;
            float wUnder = BMI_UNDERWEIGHT * hM * hM;
            float wObese = BMI_OVERWEIGHT  * hM * hM;
            if (wUnder < minY) minY = wUnder;
            if (wObese > maxY) maxY = wObese;
        }

        // Add some padding
        float span = maxY - minY;
        if (span < 5f) span = 5f;
        minY -= span * 0.1f;
        maxY += span * 0.1f;

        // ── Determine X range ───────────────────────────────────────────────
        long minTs = 0, maxTs = 0;
        if (!entries.isEmpty()) {
            minTs = dateToMs(entries.get(0).getDate());
            maxTs = minTs;
            for (WeightEntry e : entries) {
                long ts = dateToMs(e.getDate());
                if (ts < minTs) minTs = ts;
                if (ts > maxTs) maxTs = ts;
            }
        }
        if (maxTs == minTs) {
            // Single point or empty: show ±15 days window
            minTs -= 15L * 24 * 3600 * 1000;
            maxTs += 15L * 24 * 3600 * 1000;
        } else {
            long xSpan = maxTs - minTs;
            minTs -= xSpan / 10;
            maxTs += xSpan / 10;
        }

        // ── Helper lambdas (via inline methods) ─────────────────────────────
        // (Java 7 compatible)
        final float finalMinY = minY, finalMaxY = maxY;
        final long  finalMinTs = minTs, finalMaxTs = maxTs;

        // ── Draw healthy BMI band ────────────────────────────────────────────
        if (heightCm > 0) {
            float hM = heightCm / 100f;
            float yBand0 = toY(BMI_UNDERWEIGHT  * hM * hM, finalMinY, finalMaxY, chartTop, chartBottom);
            float yBand1 = toY(BMI_NORMAL_UPPER * hM * hM, finalMinY, finalMaxY, chartTop, chartBottom);
            float bandTop    = Math.min(yBand0, yBand1);
            float bandBottom = Math.max(yBand0, yBand1);
            canvas.drawRect(chartLeft, bandTop, chartRight, bandBottom, paintHealthyBand);
        }

        // ── Draw horizontal grid lines ───────────────────────────────────────
        int gridStepKg = niceStep((int) Math.ceil((maxY - minY) / 5));
        int gridStart = (int) (Math.floor(minY / gridStepKg) * gridStepKg);
        for (int kg = gridStart; kg <= (int) Math.ceil(maxY); kg += gridStepKg) {
            float gy = toY(kg, finalMinY, finalMaxY, chartTop, chartBottom);
            if (gy < chartTop || gy > chartBottom) continue;
            canvas.drawLine(chartLeft, gy, chartRight, gy, paintGrid);
            canvas.drawText(kg + " kg", 2, gy + paintLabel.getTextSize() / 3, paintLabel);
        }

        // ── Draw axes ────────────────────────────────────────────────────────
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, paintAxis);
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, paintAxis);

        // ── Draw X-axis date labels ──────────────────────────────────────────
        drawDateLabels(canvas, finalMinTs, finalMaxTs, chartLeft, chartRight, chartBottom);

        // ── Draw BMI critical marker lines ──────────────────────────────────
        if (heightCm > 0) {
            float hM = heightCm / 100f;
            drawBmiLine(canvas, BMI_UNDERWEIGHT,  hM, finalMinY, finalMaxY,
                    chartTop, chartBottom, chartLeft, chartRight,
                    Color.parseColor("#F57C00"), "BMI " + BMI_UNDERWEIGHT + " (Untergewicht)");
            drawBmiLine(canvas, BMI_NORMAL_UPPER, hM, finalMinY, finalMaxY,
                    chartTop, chartBottom, chartLeft, chartRight,
                    Color.parseColor("#F57C00"), "BMI " + BMI_NORMAL_UPPER + " (Übergewicht)");
            drawBmiLine(canvas, BMI_OVERWEIGHT,   hM, finalMinY, finalMaxY,
                    chartTop, chartBottom, chartLeft, chartRight,
                    Color.parseColor("#D32F2F"), "BMI " + BMI_OVERWEIGHT + " (Adipositas)");
        }

        // ── Draw actual weight line ──────────────────────────────────────────
        if (entries.size() >= 2) {
            Path path = new Path();
            boolean first = true;
            for (WeightEntry e : entries) {
                float x = toX(dateToMs(e.getDate()), finalMinTs, finalMaxTs, chartLeft, chartRight);
                float y = toY(e.getWeightKg(), finalMinY, finalMaxY, chartTop, chartBottom);
                if (first) { path.moveTo(x, y); first = false; }
                else        { path.lineTo(x, y); }
            }
            canvas.drawPath(path, paintDataLine);
        }

        // Draw data points
        float dotRadius = 5 * density;
        for (WeightEntry e : entries) {
            float x = toX(dateToMs(e.getDate()), finalMinTs, finalMaxTs, chartLeft, chartRight);
            float y = toY(e.getWeightKg(), finalMinY, finalMaxY, chartTop, chartBottom);
            canvas.drawCircle(x, y, dotRadius, paintDataPoint);
        }

        // ── Draw trend line (linear regression) ──────────────────────────────
        if (entries.size() >= 2) {
            drawTrendLine(canvas, finalMinTs, finalMaxTs, finalMinY, finalMaxY,
                    chartLeft, chartRight, chartTop, chartBottom);
        }

        // ── Empty-state hint ────────────────────────────────────────────────
        if (entries.isEmpty()) {
            Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
            hint.setColor(Color.parseColor("#AAAAAA"));
            hint.setTextSize(13 * density);
            hint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Noch keine Einträge", chartLeft + chartW / 2,
                    chartTop + chartH / 2, hint);
        }
    }

    // ── BMI critical line ────────────────────────────────────────────────────

    private void drawBmiLine(Canvas canvas, float bmi, float hM,
                             float minY, float maxY,
                             float chartTop, float chartBottom,
                             float chartLeft, float chartRight,
                             int color, String label) {
        float weightAtBmi = bmi * hM * hM;
        float y = toY(weightAtBmi, minY, maxY, chartTop, chartBottom);
        if (y < chartTop || y > chartBottom) return;

        paintBmi.setColor(color);
        canvas.drawLine(chartLeft, y, chartRight, y, paintBmi);

        paintBmiLabel.setColor(color);
        paintBmiLabel.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(label, chartRight - 4,
                y - 3 * density, paintBmiLabel);
    }

    // ── Trend line via linear regression ─────────────────────────────────────

    private void drawTrendLine(Canvas canvas,
                               long minTs, long maxTs,
                               float minY, float maxY,
                               float chartLeft, float chartRight,
                               float chartTop, float chartBottom) {
        int n = entries.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        double range = maxTs - minTs;
        if (range == 0) return;
        for (WeightEntry e : entries) {
            double x = (dateToMs(e.getDate()) - minTs) / range; // normalised 0..1
            double y = e.getWeightKg();
            sumX  += x;
            sumY  += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        double denom = n * sumX2 - sumX * sumX;
        if (Math.abs(denom) < 1e-10) return;
        double slope     = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;

        float x0 = chartLeft;
        float x1 = chartRight;
        float y0 = toY((float) intercept, minY, maxY, chartTop, chartBottom);
        float y1 = toY((float) (intercept + slope), minY, maxY, chartTop, chartBottom);

        canvas.drawLine(x0, y0, x1, y1, paintTrend);
    }

    // ── Date labels on X axis ────────────────────────────────────────────────

    private void drawDateLabels(Canvas canvas,
                                long minTs, long maxTs,
                                float chartLeft, float chartRight,
                                float chartBottom) {
        long rangeMs = maxTs - minTs;
        if (rangeMs <= 0) return;

        // Choose a reasonable number of labels
        int numLabels = 4;
        long stepMs = rangeMs / (numLabels - 1);

        SimpleDateFormat fmt = new SimpleDateFormat("dd.MM", Locale.GERMANY);
        paintLabel.setTextAlign(Paint.Align.CENTER);
        float textY = chartBottom + paintLabel.getTextSize() * 1.5f;

        for (int i = 0; i < numLabels; i++) {
            long ts = minTs + i * stepMs;
            float x = toX(ts, minTs, maxTs, chartLeft, chartRight);
            canvas.drawText(fmt.format(new Date(ts)), x, textY, paintLabel);
        }
        paintLabel.setTextAlign(Paint.Align.LEFT); // reset
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    private static float toY(float value, float minY, float maxY,
                              float chartTop, float chartBottom) {
        if (maxY == minY) return (chartTop + chartBottom) / 2f;
        return chartBottom - (value - minY) / (maxY - minY) * (chartBottom - chartTop);
    }

    private static float toX(long ts, long minTs, long maxTs,
                              float chartLeft, float chartRight) {
        if (maxTs == minTs) return (chartLeft + chartRight) / 2f;
        return chartLeft + (float)(ts - minTs) / (maxTs - minTs) * (chartRight - chartLeft);
    }

    private static long dateToMs(String date) {
        try {
            Date d = DATE_FMT.parse(date);
            return d != null ? d.getTime() : 0L;
        } catch (ParseException e) {
            return 0L;
        }
    }

    /** Returns a "nice" step size for grid lines (1, 2, 5, 10, 20, 50, …). */
    private static int niceStep(int roughStep) {
        if (roughStep <= 1)  return 1;
        if (roughStep <= 2)  return 2;
        if (roughStep <= 5)  return 5;
        if (roughStep <= 10) return 10;
        if (roughStep <= 20) return 20;
        if (roughStep <= 25) return 25;
        if (roughStep <= 50) return 50;
        return 100;
    }
}

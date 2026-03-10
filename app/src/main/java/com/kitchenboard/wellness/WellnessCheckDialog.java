package com.kitchenboard.wellness;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.core.content.ContextCompat;

import com.kitchenboard.MainActivity;
import com.kitchenboard.R;
import com.kitchenboard.calendar.CalendarDatabaseHelper;
import com.kitchenboard.calendar.Person;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Multi-step morning wellness check dialog.
 *
 * <p>Step 0 – Rate Müdigkeit, Gesundheit, Stimmung (1–5).<br>
 * Step 1 – Choose the person the rating belongs to.<br>
 * Step 2 – Confirmation; offer to rate another person or finish.
 */
public class WellnessCheckDialog extends Dialog {

    /** Callback invoked when the user dismisses the dialog (clicks "Nein, fertig"). */
    public interface OnDismissListener {
        void onDismiss();
    }

    private final List<Person>           persons;
    private final CalendarDatabaseHelper db;
    private final String                 today;
    private final SharedPreferences      prefs;
    private       OnDismissListener      dismissListener;

    // UI state
    private ViewFlipper viewFlipper;
    private int[]       ratings          = {3, 3, 3}; // [tiredness, health, mood]
    private long        selectedPersonId = -1;
    private String      selectedPersonName = "";

    // Per-category rating button groups (3 categories × 5 ratings)
    private Button[][] ratingButtons = new Button[3][5];

    public WellnessCheckDialog(Context context, List<Person> persons,
                               CalendarDatabaseHelper db,
                               String today, SharedPreferences prefs) {
        super(context, R.style.WellnessDialogTheme);
        this.persons = persons;
        this.db      = db;
        this.today   = today;
        this.prefs   = prefs;
    }

    public void setOnDismissListener(OnDismissListener listener) {
        this.dismissListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_wellness_check);

        // Size the dialog to 80% of screen width
        if (getWindow() != null) {
            DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
            int width  = (int) (dm.widthPixels * 0.80f);
            getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        viewFlipper = findViewById(R.id.wc_view_flipper);

        setupHeader();
        setupRatingsPage();
        setupPersonPage();
        setupDonePage();

        viewFlipper.setDisplayedChild(0);
        pauseAutoAdvance();
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private void setupHeader() {
        TextView btnClose = findViewById(R.id.wc_btn_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss());
        }
    }

    // ── Page 0: Ratings ───────────────────────────────────────────────────────

    private void setupRatingsPage() {
        LinearLayout containerTiredness = findViewById(R.id.wc_rating_tiredness);
        LinearLayout containerHealth    = findViewById(R.id.wc_rating_health);
        LinearLayout containerMood      = findViewById(R.id.wc_rating_mood);

        buildRatingButtons(containerTiredness, 0);
        buildRatingButtons(containerHealth,    1);
        buildRatingButtons(containerMood,      2);

        Button btnNext = findViewById(R.id.wc_btn_next);
        if (btnNext != null) {
            btnNext.setOnClickListener(v -> {
                if (persons.isEmpty()) {
                    Toast.makeText(getContext(),
                            R.string.wellness_no_persons, Toast.LENGTH_LONG).show();
                    dismiss();
                    return;
                }
                flipTo(1);
            });
        }
    }

    private void buildRatingButtons(LinearLayout container, int categoryIndex) {
        Context ctx      = getContext();
        int     sizePx   = dp(48);
        int     marginPx = dp(4);

        container.removeAllViews();

        for (int i = 0; i < 5; i++) {
            final int value  = i + 1;
            final int catIdx = categoryIndex;

            Button btn = new Button(ctx);
            btn.setText(String.valueOf(value));
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setAllCaps(false);
            btn.setPadding(0, 0, 0, 0);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
            lp.setMargins(marginPx, marginPx, marginPx, marginPx);
            btn.setLayoutParams(lp);

            applyRatingButtonBackground(btn, false);

            btn.setOnClickListener(v -> {
                ratings[catIdx] = value;
                for (int j = 0; j < 5; j++) {
                    applyRatingButtonBackground(ratingButtons[catIdx][j], j + 1 == value);
                }
            });

            ratingButtons[categoryIndex][i] = btn;
            container.addView(btn);
        }

        // Pre-select the default rating (3)
        applyRatingButtonBackground(ratingButtons[categoryIndex][2], true);
    }

    private void applyRatingButtonBackground(Button btn, boolean selected) {
        if (selected) {
            btn.setBackground(makeCircle(
                    ContextCompat.getColor(getContext(), R.color.accent), 0, 0));
            btn.setTextColor(Color.WHITE);
        } else {
            btn.setBackground(makeCircle(Color.TRANSPARENT,
                    ContextCompat.getColor(getContext(), R.color.text_secondary), dp(2)));
            btn.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        }
    }

    /** Creates a circular {@link GradientDrawable} with optional fill and stroke. */
    private GradientDrawable makeCircle(int fillColor, int strokeColor, int strokeWidthPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fillColor);
        if (strokeWidthPx > 0) {
            d.setStroke(strokeWidthPx, strokeColor);
        }
        return d;
    }

    // ── Page 1: Person selection ───────────────────────────────────────────────

    private void setupPersonPage() {
        LinearLayout personGrid = findViewById(R.id.wc_person_grid);
        TextView     noPersons  = findViewById(R.id.wc_no_persons);

        if (persons.isEmpty()) {
            if (noPersons != null) noPersons.setVisibility(View.VISIBLE);
            return;
        }
        if (noPersons != null) noPersons.setVisibility(View.GONE);

        Button btnSave = findViewById(R.id.wc_btn_save);

        personGrid.removeAllViews();
        for (Person person : persons) {
            View card = buildPersonCard(person, btnSave);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            card.setLayoutParams(lp);
            personGrid.addView(card);
        }

        if (btnSave != null) {
            btnSave.setEnabled(false);
            btnSave.setOnClickListener(v -> saveEntry());
        }
    }

    private View buildPersonCard(Person person, Button btnSave) {
        Context ctx        = getContext();
        int     circleSize = dp(64);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));

        // Circle: photo or colored initials overlay
        android.widget.FrameLayout circleFrame = new android.widget.FrameLayout(ctx);
        circleFrame.setLayoutParams(new LinearLayout.LayoutParams(circleSize, circleSize));

        ImageView circle = new ImageView(ctx);
        circle.setLayoutParams(new android.widget.FrameLayout.LayoutParams(circleSize, circleSize));
        circle.setScaleType(ImageView.ScaleType.CENTER_CROP);
        circleFrame.addView(circle);

        boolean photoLoaded = false;
        if (person.getImagePath() != null) {
            File imgFile = new File(person.getImagePath());
            if (imgFile.exists()) {
                Bitmap bmp = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                if (bmp != null) {
                    circle.setImageBitmap(createCircularBitmap(bmp));
                    photoLoaded = true;
                }
            }
        }

        if (!photoLoaded) {
            circle.setBackground(makeCircle(parseColor(person.getColor()), 0, 0));
            circle.setImageDrawable(null);

            // Overlay the person's initial letter
            TextView tvInitial = new TextView(ctx);
            String initial = person.getName().isEmpty()
                    ? "?" : String.valueOf(person.getName().charAt(0)).toUpperCase(Locale.ROOT);
            tvInitial.setText(initial);
            tvInitial.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            tvInitial.setTypeface(null, Typeface.BOLD);
            tvInitial.setTextColor(Color.WHITE);
            tvInitial.setGravity(Gravity.CENTER);
            android.widget.FrameLayout.LayoutParams initialLp =
                    new android.widget.FrameLayout.LayoutParams(circleSize, circleSize);
            initialLp.gravity = Gravity.CENTER;
            tvInitial.setLayoutParams(initialLp);
            circleFrame.addView(tvInitial);
        }

        TextView tvName = new TextView(ctx);
        tvName.setText(person.getName());
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvName.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        tvName.setPadding(0, dp(4), 0, 0);
        tvName.setGravity(Gravity.CENTER_HORIZONTAL);
        tvName.setMaxLines(2);

        card.addView(circleFrame);
        card.addView(tvName);

        final long personId = person.getId();
        card.setOnClickListener(v -> {
            // Deselect all cards in the grid
            LinearLayout grid = (LinearLayout) card.getParent();
            for (int i = 0; i < grid.getChildCount(); i++) {
                grid.getChildAt(i).setBackground(null);
            }
            // Highlight the selected card
            GradientDrawable sel = makeCircle(
                    ContextCompat.getColor(ctx, R.color.accent_light),
                    ContextCompat.getColor(ctx, R.color.accent), dp(2));
            sel.setCornerRadius(dp(12));
            card.setBackground(sel);

            selectedPersonId   = personId;
            selectedPersonName = person.getName();
            if (btnSave != null) btnSave.setEnabled(true);
        });

        return card;
    }

    private void saveEntry() {
        if (selectedPersonId < 0) return;

        db.addWellnessEntry(selectedPersonId, today, ratings[0], ratings[1], ratings[2]);

        TextView tvDone = findViewById(R.id.wc_done_text);
        if (tvDone != null) {
            tvDone.setText(getContext().getString(R.string.wellness_saved_for, selectedPersonName));
        }

        flipTo(2);
    }

    // ── Page 2: Done ──────────────────────────────────────────────────────────

    private void setupDonePage() {
        Button btnMore = findViewById(R.id.wc_btn_more);
        Button btnDone = findViewById(R.id.wc_btn_done);

        if (btnMore != null) {
            btnMore.setOnClickListener(v -> {
                // Reset selection state and go back to ratings
                selectedPersonId   = -1;
                selectedPersonName = "";
                LinearLayout grid = findViewById(R.id.wc_person_grid);
                if (grid != null) {
                    for (int i = 0; i < grid.getChildCount(); i++) {
                        grid.getChildAt(i).setBackground(null);
                    }
                }
                Button btnSave = findViewById(R.id.wc_btn_save);
                if (btnSave != null) btnSave.setEnabled(false);

                flipTo(0);
            });
        }

        if (btnDone != null) {
            btnDone.setOnClickListener(v -> {
                prefs.edit().putString("wellness_last_date", today).apply();
                if (dismissListener != null) dismissListener.onDismiss();
                dismiss();
            });
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void dismiss() {
        resumeAutoAdvance();
        super.dismiss();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Slides the ViewFlipper to the given page index with a left-to-right animation. */
    private void flipTo(int pageIndex) {
        viewFlipper.setInAnimation(getContext(), android.R.anim.slide_in_left);
        viewFlipper.setOutAnimation(getContext(), android.R.anim.slide_out_right);
        viewFlipper.setDisplayedChild(pageIndex);
    }

    private int dp(int dp) {
        return Math.round(dp * getContext().getResources().getDisplayMetrics().density);
    }

    private int parseColor(String hex) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return Color.LTGRAY;
        }
    }

    /** Crops a bitmap into a circle. */
    private Bitmap createCircularBitmap(Bitmap source) {
        int size   = Math.min(source.getWidth(), source.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(
                Bitmap.createScaledBitmap(source, size, size, true),
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawOval(new RectF(0, 0, size, size), paint);
        return out;
    }

    private void pauseAutoAdvance() {
        try {
            if (getContext() instanceof MainActivity) {
                ((MainActivity) getContext()).pauseAutoAdvance();
            }
        } catch (ClassCastException ignored) { }
    }

    private void resumeAutoAdvance() {
        try {
            if (getContext() instanceof MainActivity) {
                ((MainActivity) getContext()).resumeAutoAdvance();
            }
        } catch (ClassCastException ignored) { }
    }
}

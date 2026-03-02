package com.kitchenboard.calendar;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CalendarView;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kitchenboard.MainActivity;
import com.kitchenboard.R;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CalendarFragment extends Fragment {

    private static final SimpleDateFormat DATE_FMT  =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat LABEL_FMT =
            new SimpleDateFormat("EEEE, dd. MMMM yyyy", Locale.GERMANY);
    private static final SimpleDateFormat SHORT_FMT =
            new SimpleDateFormat("dd.MM.", Locale.GERMANY);
    private static final SimpleDateFormat DAY_NAME_FMT =
            new SimpleDateFormat("EEE", Locale.GERMANY);

    private CalendarDatabaseHelper db;
    private AppointmentAdapter adapter;
    private TextView tvSelectedDate;
    private TextView tvEmpty;
    private LinearLayout llTemplateButtons;
    private LinearLayout llPersonFilter;

    // Multi-day strip views
    private LinearLayout llDayStrip;
    private LinearLayout llWeekNav;
    private TextView tvWeekRange;
    private CalendarView calendarView;

    // Mode buttons
    private Button btnMode3;
    private Button btnMode5;
    private Button btnMode7;
    private Button btnModeMonth;

    /** Current view mode: 3, 5, 7 (day strip) or -1 (full month). Default 3. */
    private int viewMode = 3;

    /** First day shown in the day strip. */
    private final Calendar stripStart = Calendar.getInstance();

    /** Currently selected date in YYYY-MM-DD format. */
    private String selectedDate;

    /** Earliest hour shown in the drag time-slot mapping (06:00). */
    private static final int DRAG_START_HOUR = 6;
    /** Number of hours covered by the day-strip column height (06:00–22:00). */
    private static final int DRAG_HOUR_RANGE = 16;

    /**
     * Active person/group filter.
     * null  = show all appointments (no filter).
     * non-null list = show only appointments whose person_id is in this list.
     */
    private List<Long> activeFilterPersonIds = null;

    /**
     * When filtering by a group, the group's own id so that appointments directly assigned to
     * the group are also included in the result.  null when not filtering by a group.
     */
    private Long activeFilterGroupId = null;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_calendar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = new CalendarDatabaseHelper(requireContext());
        adapter = new AppointmentAdapter();

        tvSelectedDate    = view.findViewById(R.id.tv_selected_date);
        tvEmpty           = view.findViewById(R.id.tv_appointments_empty);
        llTemplateButtons = view.findViewById(R.id.ll_template_buttons);
        llPersonFilter    = view.findViewById(R.id.ll_person_filter);
        llDayStrip        = view.findViewById(R.id.ll_day_strip);
        llWeekNav         = view.findViewById(R.id.ll_week_nav);
        tvWeekRange       = view.findViewById(R.id.tv_week_range);
        calendarView      = view.findViewById(R.id.calendar_view);
        btnMode3          = view.findViewById(R.id.btn_mode_3);
        btnMode5          = view.findViewById(R.id.btn_mode_5);
        btnMode7          = view.findViewById(R.id.btn_mode_7);
        btnModeMonth      = view.findViewById(R.id.btn_mode_month);

        // Default to today
        selectedDate = DATE_FMT.format(new Date());
        stripStart.setTime(new Date());
        normalizeCalendar(stripStart);

        updateDateLabel();
        refreshTemplateButtons();
        refreshPersonFilter();

        // ── RecyclerView ──────────────────────────────────────────────────────
        RecyclerView rv = view.findViewById(R.id.rv_appointments);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        // Swipe-left to delete
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView r,
                                  @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getAdapterPosition();
                Appointment apt = adapter.getItem(pos);
                // Reset the swipe animation before showing the confirmation dialog
                adapter.notifyItemChanged(pos);
                confirmDeleteAppointment(apt);
            }
        }).attachToRecyclerView(rv);

        adapter.setOnDeleteListener(new AppointmentAdapter.OnDeleteListener() {
            @Override
            public void onDelete(Appointment appointment) {
                confirmDeleteAppointment(appointment);
            }
        });

        // ── Full-month CalendarView (month mode only) ─────────────────────────
        calendarView.setOnDateChangeListener(new CalendarView.OnDateChangeListener() {
            @Override
            public void onSelectedDayChange(@NonNull CalendarView v,
                                            int year, int month, int dayOfMonth) {
                Calendar cal = Calendar.getInstance();
                cal.set(year, month, dayOfMonth);
                selectedDate = DATE_FMT.format(cal.getTime());
                updateDateLabel();
                refreshAppointments();
            }
        });

        // ── Mode buttons ──────────────────────────────────────────────────────
        btnMode3.setOnClickListener(v -> setViewMode(3));
        btnMode5.setOnClickListener(v -> setViewMode(5));
        btnMode7.setOnClickListener(v -> setViewMode(7));
        btnModeMonth.setOnClickListener(v -> setViewMode(-1));

        // ── Week navigation ───────────────────────────────────────────────────
        view.findViewById(R.id.btn_prev_week).setOnClickListener(v -> {
            stripStart.add(Calendar.DAY_OF_MONTH, -7);
            refreshDayStrip();
        });
        view.findViewById(R.id.btn_next_week).setOnClickListener(v -> {
            stripStart.add(Calendar.DAY_OF_MONTH, 7);
            refreshDayStrip();
        });

        // ── FAB ───────────────────────────────────────────────────────────────
        FloatingActionButton fab = view.findViewById(R.id.fab_add_appointment);
        fab.setOnClickListener(v -> showAddAppointmentDialog());

        // ── Manage templates ──────────────────────────────────────────────────
        view.findViewById(R.id.btn_manage_templates).setOnClickListener(
                v -> showManageTemplatesDialog());

        // ── Manage persons ────────────────────────────────────────────────────
        view.findViewById(R.id.btn_manage_persons).setOnClickListener(
                v -> showManagePersonsDialog());

        // Activate default 3-day mode
        setViewMode(3);
        refreshAppointments();
    }

    // ── View-mode switching ───────────────────────────────────────────────────

    private void setViewMode(int mode) {
        viewMode = mode;

        // Keep stripStart in sync with the selected date when entering day-strip mode
        if (mode > 0) {
            try {
                stripStart.setTime(DATE_FMT.parse(selectedDate));
            } catch (ParseException e) {
                stripStart.setTime(new Date());
            }
            normalizeCalendar(stripStart);
        }

        boolean isDayMode = (mode > 0);
        llDayStrip.setVisibility(isDayMode ? View.VISIBLE : View.GONE);
        llWeekNav.setVisibility(isDayMode ? View.VISIBLE : View.GONE);
        calendarView.setVisibility(isDayMode ? View.GONE : View.VISIBLE);

        if (isDayMode) {
            refreshDayStrip();
        } else {
            // Sync the native CalendarView to the currently selected date
            try {
                Date d = DATE_FMT.parse(selectedDate);
                if (d != null) calendarView.setDate(d.getTime(), false, true);
            } catch (ParseException ignored) {}
        }

        updateModeButtonAppearance();
    }

    private void updateModeButtonAppearance() {
        int activeColor   = ContextCompat.getColor(requireContext(), R.color.accent);
        int inactiveColor = ContextCompat.getColor(requireContext(), R.color.text_secondary);
        btnMode3.setTextColor(viewMode == 3  ? activeColor : inactiveColor);
        btnMode5.setTextColor(viewMode == 5  ? activeColor : inactiveColor);
        btnMode7.setTextColor(viewMode == 7  ? activeColor : inactiveColor);
        btnModeMonth.setTextColor(viewMode == -1 ? activeColor : inactiveColor);
    }

    // ── Day strip ─────────────────────────────────────────────────────────────

    private void refreshDayStrip() {
        llDayStrip.removeAllViews();
        String todayStr = DATE_FMT.format(new Date());
        int accentColor      = ContextCompat.getColor(requireContext(), R.color.accent);
        int accentLightColor = ContextCompat.getColor(requireContext(), R.color.accent_light);
        int textPrimary      = ContextCompat.getColor(requireContext(), R.color.text_primary);
        int textSecondary    = ContextCompat.getColor(requireContext(), R.color.text_secondary);
        int padPx = dpToPx(4);

        Calendar cal = (Calendar) stripStart.clone();
        for (int i = 0; i < viewMode; i++) {
            final String dateStr = DATE_FMT.format(cal.getTime());
            final boolean isSelected = dateStr.equals(selectedDate);
            boolean isToday = dateStr.equals(todayStr);

            // Outer FrameLayout cell (the "column")
            FrameLayout cell = new FrameLayout(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            cell.setLayoutParams(lp);
            if (isSelected) {
                cell.setBackgroundColor(accentLightColor);
            }

            // Inner content LinearLayout
            LinearLayout content = new LinearLayout(requireContext());
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            content.setPadding(padPx, padPx, padPx, padPx);
            FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            content.setLayoutParams(contentLp);

            TextView tvDayName = new TextView(requireContext());
            tvDayName.setText(DAY_NAME_FMT.format(cal.getTime()));
            tvDayName.setGravity(Gravity.CENTER);
            tvDayName.setTextSize(11f);
            tvDayName.setTextColor(textSecondary);
            content.addView(tvDayName);

            TextView tvDayNum = new TextView(requireContext());
            tvDayNum.setText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));
            tvDayNum.setGravity(Gravity.CENTER);
            tvDayNum.setTextSize(20f);
            tvDayNum.setTextColor(isToday ? accentColor : textPrimary);
            if (isToday) tvDayNum.setTypeface(null, Typeface.BOLD);
            content.addView(tvDayNum);

            // Appointment indicators
            List<Appointment> dayApts;
            if (activeFilterPersonIds == null) {
                dayApts = db.getAppointmentsForDate(dateStr);
            } else if (activeFilterPersonIds.size() == 1 && activeFilterGroupId == null) {
                dayApts = db.getAppointmentsForDate(dateStr, activeFilterPersonIds.get(0));
            } else {
                dayApts = db.getAppointmentsForDateByGroup(dateStr, activeFilterPersonIds, activeFilterGroupId);
            }
            int maxShow = 3;
            for (int j = 0; j < Math.min(dayApts.size(), maxShow); j++) {
                final Appointment apt = dayApts.get(j);
                TextView tvApt = new TextView(requireContext());
                String aptText = apt.getTime() != null
                        ? apt.getTime() + " " + apt.getTitle()
                        : apt.getTitle();
                tvApt.setText(aptText);
                tvApt.setGravity(Gravity.CENTER);
                tvApt.setTextSize(9f);
                tvApt.setTextColor(accentColor);
                tvApt.setMaxLines(1);
                tvApt.setEllipsize(TextUtils.TruncateAt.END);
                tvApt.setPadding(padPx, dpToPx(1), padPx, dpToPx(1));
                // Tap on appointment in day strip → select that day and offer delete
                tvApt.setOnClickListener(v2 -> {
                    selectedDate = dateStr;
                    updateDateLabel();
                    refreshAppointments();
                    refreshDayStrip();
                    confirmDeleteAppointment(apt);
                });
                // Long-press on appointment → start drag to move it to another day
                tvApt.setOnLongClickListener(v2 -> {
                    ClipData data = ClipData.newPlainText(
                            "appointment", String.valueOf(apt.getId()));
                    View.DragShadowBuilder shadow = new View.DragShadowBuilder(v2);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        v2.startDragAndDrop(data, shadow, null, 0);
                    } else {
                        v2.startDrag(data, shadow, null, 0);
                    }
                    return true;
                });
                content.addView(tvApt);
            }
            if (dayApts.size() > maxShow) {
                TextView tvMore = new TextView(requireContext());
                tvMore.setText("+" + (dayApts.size() - maxShow));
                tvMore.setGravity(Gravity.CENTER);
                tvMore.setTextSize(9f);
                tvMore.setTextColor(textSecondary);
                content.addView(tvMore);
            }

            cell.addView(content);

            // Drag-and-drop time-slot overlay
            TextView tvDragSlot = new TextView(requireContext());
            tvDragSlot.setGravity(Gravity.CENTER);
            tvDragSlot.setTextColor(Color.WHITE);
            tvDragSlot.setTextSize(10f);
            tvDragSlot.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.drag_slot_overlay));
            tvDragSlot.setVisibility(View.GONE);
            FrameLayout.LayoutParams slotLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(32));
            tvDragSlot.setLayoutParams(slotLp);
            cell.addView(tvDragSlot);

            cell.setOnClickListener(v -> {
                selectedDate = dateStr;
                updateDateLabel();
                refreshAppointments();
                refreshDayStrip();
            });

            cell.setOnDragListener((v, event) -> {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return true;
                    case DragEvent.ACTION_DRAG_ENTERED:
                    case DragEvent.ACTION_DRAG_LOCATION: {
                        float cellH = v.getHeight();
                        float slotH = dpToPx(32);
                        int hour = dragYToHour(event.getY(), cellH);
                        tvDragSlot.setText(String.format(Locale.US,
                                "%02d:00 – %02d:00", hour, hour + 1));
                        float slotTop = Math.min(
                                event.getY() - slotH / 2f, cellH - slotH);
                        tvDragSlot.setTranslationY(Math.max(0, slotTop));
                        tvDragSlot.setVisibility(View.VISIBLE);
                        if (!isSelected) v.setBackgroundColor(accentLightColor);
                        return true;
                    }
                    case DragEvent.ACTION_DRAG_EXITED: {
                        tvDragSlot.setVisibility(View.GONE);
                        if (!isSelected) v.setBackgroundColor(Color.TRANSPARENT);
                        return true;
                    }
                    case DragEvent.ACTION_DROP: {
                        tvDragSlot.setVisibility(View.GONE);
                        if (!isSelected) v.setBackgroundColor(Color.TRANSPARENT);
                        ClipData clipData = event.getClipData();
                        if (clipData != null && clipData.getItemCount() > 0) {
                            String clipLabel = clipData.getDescription().getLabel().toString();
                            String clipText  = clipData.getItemAt(0).getText().toString();
                            int hour = dragYToHour(event.getY(), v.getHeight());
                            String time = String.format(Locale.US, "%02d:00", hour);
                            if ("appointment".equals(clipLabel)) {
                                // Move existing appointment to this day/time
                                try {
                                    long aptId = Long.parseLong(clipText);
                                    db.updateAppointmentDateTime(aptId, dateStr, time);
                                } catch (NumberFormatException ignored) {}
                            } else {
                                // Drop from template button – create new appointment
                                db.addAppointment(dateStr, time, clipText);
                            }
                            v.post(() -> {
                                refreshAppointments();
                                refreshDayStrip();
                            });
                        }
                        return true;
                    }
                    case DragEvent.ACTION_DRAG_ENDED: {
                        tvDragSlot.setVisibility(View.GONE);
                        if (!isSelected) v.setBackgroundColor(Color.TRANSPARENT);
                        return false;
                    }
                }
                return false;
            });

            llDayStrip.addView(cell);

            // Vertical divider between cells (except last)
            if (i < viewMode - 1) {
                View div = new View(requireContext());
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                        1, LinearLayout.LayoutParams.MATCH_PARENT);
                div.setLayoutParams(dp);
                div.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.divider));
                llDayStrip.addView(div);
            }

            cal.add(Calendar.DAY_OF_MONTH, 1);
        }

        updateWeekRangeLabel();
    }

    private void updateWeekRangeLabel() {
        if (viewMode <= 0) return;
        Calendar endCal = (Calendar) stripStart.clone();
        endCal.add(Calendar.DAY_OF_MONTH, viewMode - 1);
        tvWeekRange.setText(SHORT_FMT.format(stripStart.getTime())
                + " – " + SHORT_FMT.format(endCal.getTime()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void normalizeCalendar(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Maps a drag event Y-coordinate within a cell to the nearest start hour. */
    private static int dragYToHour(float y, float cellHeight) {
        int hour = Math.round(DRAG_START_HOUR + y / cellHeight * DRAG_HOUR_RANGE);
        return Math.max(DRAG_START_HOUR, Math.min(DRAG_START_HOUR + DRAG_HOUR_RANGE - 1, hour));
    }

    private void updateDateLabel() {
        try {
            Date d = DATE_FMT.parse(selectedDate);
            tvSelectedDate.setText(LABEL_FMT.format(d));
        } catch (Exception e) {
            tvSelectedDate.setText(selectedDate);
        }
    }

    private void refreshAppointments() {
        List<Appointment> list;
        if (activeFilterPersonIds == null) {
            list = db.getAppointmentsForDate(selectedDate);
        } else if (activeFilterPersonIds.size() == 1 && activeFilterGroupId == null) {
            list = db.getAppointmentsForDate(selectedDate, activeFilterPersonIds.get(0));
        } else {
            list = db.getAppointmentsForDateByGroup(selectedDate, activeFilterPersonIds, activeFilterGroupId);
        }
        adapter.setPersons(db.getPersons());
        adapter.setGroups(db.getPersonGroups());
        adapter.setItems(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Rebuilds the persistent template quick-add buttons row. */
    private void refreshTemplateButtons() {
        llTemplateButtons.removeAllViews();
        List<Template> templates = db.getTemplates();
        int marginPx = dpToPx(8);
        for (final Template t : templates) {
            Button btn = new Button(requireContext());
            btn.setText(t.getTitle());
            btn.setAllCaps(false);
            btn.setTextSize(16f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, marginPx, 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> showRecurrenceDialog(t));
            btn.setOnLongClickListener(v -> {
                ClipData data = ClipData.newPlainText("template", t.getTitle());
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    v.startDragAndDrop(data, shadow, null, 0);
                } else {
                    v.startDrag(data, shadow, null, 0);
                }
                return true;
            });
            llTemplateButtons.addView(btn);
        }
    }

    // ── Person filter bar ─────────────────────────────────────────────────────

    /**
     * Rebuilds the person/group quick-filter strip.
     * "Alle" is always first, then groups, then individual persons.
     */
    private void refreshPersonFilter() {
        llPersonFilter.removeAllViews();
        int marginPx  = dpToPx(6);
        int accentColor   = ContextCompat.getColor(requireContext(), R.color.accent);
        int textSecondary = ContextCompat.getColor(requireContext(), R.color.text_secondary);

        // "Alle" button
        Button btnAll = new Button(requireContext());
        btnAll.setText(R.string.calendar_filter_all);
        btnAll.setAllCaps(false);
        btnAll.setTextSize(14f);
        btnAll.setTextColor(activeFilterPersonIds == null ? accentColor : textSecondary);
        LinearLayout.LayoutParams lpAll = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpAll.setMargins(0, 0, marginPx, 0);
        btnAll.setLayoutParams(lpAll);
        btnAll.setOnClickListener(v -> {
            activeFilterPersonIds = null;
            activeFilterGroupId   = null;
            refreshPersonFilter();
            refreshAppointments();
            refreshDayStrip();
        });
        llPersonFilter.addView(btnAll);

        // Group buttons
        List<PersonGroup> groups = db.getPersonGroups();
        for (final PersonGroup g : groups) {
            boolean isActive = isGroupActive(g);
            Button btn = new Button(requireContext());
            btn.setText("👥 " + g.getName());
            btn.setAllCaps(false);
            btn.setTextSize(14f);
            btn.setTextColor(isActive ? accentColor : textSecondary);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, marginPx, 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                List<Long> memberIds = db.getGroupMemberIds(g.getId());
                activeFilterPersonIds = memberIds.isEmpty() ? null : new ArrayList<>(memberIds);
                activeFilterGroupId   = g.getId();
                refreshPersonFilter();
                refreshAppointments();
                refreshDayStrip();
            });
            llPersonFilter.addView(btn);
        }

        // Individual person buttons
        List<Person> persons = db.getPersons();
        for (final Person p : persons) {
            boolean isActive = isPersonActive(p);
            Button btn = new Button(requireContext());
            btn.setText(p.getName());
            btn.setAllCaps(false);
            btn.setTextSize(14f);
            try {
                int personColor = Color.parseColor(p.getColor());
                btn.setTextColor(isActive ? personColor : textSecondary);
            } catch (IllegalArgumentException e) {
                btn.setTextColor(isActive ? accentColor : textSecondary);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, marginPx, 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                activeFilterPersonIds = new ArrayList<>();
                activeFilterPersonIds.add(p.getId());
                activeFilterGroupId   = null;
                refreshPersonFilter();
                refreshAppointments();
                refreshDayStrip();
            });
            llPersonFilter.addView(btn);
        }
    }

    private boolean isPersonActive(Person p) {
        return activeFilterPersonIds != null
                && activeFilterPersonIds.size() == 1
                && activeFilterPersonIds.get(0).equals(p.getId());
    }

    private boolean isGroupActive(PersonGroup g) {
        return activeFilterGroupId != null && activeFilterGroupId.equals(g.getId());
    }

    // ── Auto-advance helpers ──────────────────────────────────────────────────

    private void pauseAutoAdvance() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).pauseAutoAdvance();
        }
    }

    private void resumeAutoAdvance() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).resumeAutoAdvance();
        }
    }

    // ── Recurrence dialog ─────────────────────────────────────────────────────

    private void showRecurrenceDialog(final Template t) {
        if (!isAdded() || getContext() == null) return;

        final String[] recurrenceKeys = {
                "once", "daily", "weekdays", "mon_sat", "weekly", "monthly"
        };
        final int[] recurrenceLabelIds = {
                R.string.calendar_recurrence_once,
                R.string.calendar_recurrence_daily,
                R.string.calendar_recurrence_weekdays,
                R.string.calendar_recurrence_mon_sat,
                R.string.calendar_recurrence_weekly,
                R.string.calendar_recurrence_monthly
        };

        // Default end date = 1 month from currently selected date
        final Calendar endCal = Calendar.getInstance();
        try {
            endCal.setTime(DATE_FMT.parse(selectedDate));
        } catch (ParseException e) { /* keep today */ }
        endCal.add(Calendar.MONTH, 1);
        final String[] endDate = {DATE_FMT.format(endCal.getTime())};

        // Mutable start date and optional time for "once" mode
        final String[] startDate = {selectedDate};
        final String[] appointmentTime = {null};

        // Build dialog view
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padPx = dpToPx(16);
        layout.setPadding(padPx, padPx, padPx, padPx);

        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText(t.getTitle());
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, dpToPx(8));
        layout.addView(tvTitle);

        final RadioGroup rg = new RadioGroup(requireContext());
        rg.setOrientation(LinearLayout.VERTICAL);
        int rbPad = dpToPx(6);

        // Use generated IDs to avoid clashes with View.NO_ID
        final int[] rbIds = new int[recurrenceKeys.length];
        for (int i = 0; i < recurrenceLabelIds.length; i++) {
            RadioButton rb = new RadioButton(requireContext());
            rb.setText(recurrenceLabelIds[i]);
            rbIds[i] = View.generateViewId();
            rb.setId(rbIds[i]);
            rb.setTextSize(16f);
            rb.setPadding(rbPad, rbPad, rbPad, rbPad);
            rg.addView(rb);
        }
        rg.check(rbIds[0]);
        layout.addView(rg);

        // End date button (for recurring modes)
        final Button btnEndDate = new Button(requireContext());
        try {
            btnEndDate.setText(getString(R.string.calendar_recurrence_until,
                    LABEL_FMT.format(endCal.getTime())));
        } catch (Exception e) {
            btnEndDate.setText(getString(R.string.calendar_recurrence_until, endDate[0]));
        }
        btnEndDate.setAllCaps(false);
        btnEndDate.setTextSize(16f);
        btnEndDate.setVisibility(View.GONE);
        layout.addView(btnEndDate);

        // Start date button (for "once" mode)
        final Button btnStartDate = new Button(requireContext());
        try {
            btnStartDate.setText(getString(R.string.calendar_recurrence_date,
                    LABEL_FMT.format(DATE_FMT.parse(selectedDate))));
        } catch (Exception e) {
            btnStartDate.setText(getString(R.string.calendar_recurrence_date, selectedDate));
        }
        btnStartDate.setAllCaps(false);
        btnStartDate.setTextSize(16f);
        btnStartDate.setVisibility(View.VISIBLE);
        layout.addView(btnStartDate);

        // Time button (for "once" mode)
        final Button btnSetTime = new Button(requireContext());
        btnSetTime.setText(R.string.calendar_recurrence_time_optional);
        btnSetTime.setAllCaps(false);
        btnSetTime.setTextSize(16f);
        btnSetTime.setVisibility(View.VISIBLE);
        layout.addView(btnSetTime);

        // Person / group picker
        final Long[] selectedPersonId = {null};
        final Long[] selectedGroupId  = {null};
        List<Person> persons = db.getPersons();
        List<PersonGroup> groups = db.getPersonGroups();
        if (!persons.isEmpty() || !groups.isEmpty()) {
            TextView tvPersonLabel = new TextView(requireContext());
            tvPersonLabel.setText(R.string.calendar_person_label);
            tvPersonLabel.setTextSize(14f);
            tvPersonLabel.setPadding(0, dpToPx(8), 0, dpToPx(4));
            layout.addView(tvPersonLabel);

            LinearLayout llPersonPicker = new LinearLayout(requireContext());
            llPersonPicker.setOrientation(LinearLayout.HORIZONTAL);
            layout.addView(llPersonPicker);

            buildPersonPickerButtons(llPersonPicker, persons, selectedPersonId, selectedGroupId);
        }

        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                boolean isOnce = (checkedId == rbIds[0]);
                btnEndDate.setVisibility(isOnce ? View.GONE : View.VISIBLE);
                btnStartDate.setVisibility(isOnce ? View.VISIBLE : View.GONE);
                // Time is always available – applies to all occurrences in the series
                btnSetTime.setVisibility(View.VISIBLE);
            }
        });

        btnEndDate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Calendar cur = Calendar.getInstance();
                try {
                    cur.setTime(DATE_FMT.parse(endDate[0]));
                } catch (ParseException e) { /* keep today */ }
                new DatePickerDialog(requireContext(),
                        new DatePickerDialog.OnDateSetListener() {
                            @Override
                            public void onDateSet(DatePicker picker, int year, int month, int day) {
                                Calendar chosen = Calendar.getInstance();
                                chosen.set(year, month, day);
                                endDate[0] = DATE_FMT.format(chosen.getTime());
                                btnEndDate.setText(getString(R.string.calendar_recurrence_until,
                                        LABEL_FMT.format(chosen.getTime())));
                            }
                        },
                        cur.get(Calendar.YEAR),
                        cur.get(Calendar.MONTH),
                        cur.get(Calendar.DAY_OF_MONTH)
                ).show();
            }
        });

        btnStartDate.setOnClickListener(v -> {
            Calendar cur = Calendar.getInstance();
            try { cur.setTime(DATE_FMT.parse(startDate[0])); } catch (ParseException ignored) {}
            new DatePickerDialog(requireContext(), (picker, year, month, day) -> {
                Calendar chosen = Calendar.getInstance();
                chosen.set(year, month, day);
                startDate[0] = DATE_FMT.format(chosen.getTime());
                try {
                    btnStartDate.setText(getString(R.string.calendar_recurrence_date,
                            LABEL_FMT.format(chosen.getTime())));
                } catch (Exception ignored) {}
            }, cur.get(Calendar.YEAR), cur.get(Calendar.MONTH),
                    cur.get(Calendar.DAY_OF_MONTH)).show();
        });

        btnSetTime.setOnClickListener(v -> {
            Calendar cur = Calendar.getInstance();
            int hour = cur.get(Calendar.HOUR_OF_DAY);
            int minute = 0;
            if (appointmentTime[0] != null) {
                String[] parts = appointmentTime[0].split(":");
                try {
                    hour = Integer.parseInt(parts[0]);
                    minute = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
            new TimePickerDialog(requireContext(), (picker, h, m) -> {
                appointmentTime[0] = String.format(Locale.US, "%02d:%02d", h, m);
                btnSetTime.setText(getString(R.string.calendar_recurrence_time,
                        appointmentTime[0]));
            }, hour, minute, true).show();
        });

        pauseAutoAdvance();
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.calendar_recurrence_title)
                .setView(layout)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int checkedId = rg.getCheckedRadioButtonId();
                        int idx = 0;
                        for (int i = 0; i < rbIds.length; i++) {
                            if (rbIds[i] == checkedId) { idx = i; break; }
                        }
                        String recKey = recurrenceKeys[idx];
                        if ("once".equals(recKey)) {
                            db.addAppointment(startDate[0], appointmentTime[0], t.getTitle(),
                                    selectedPersonId[0], selectedGroupId[0]);
                        } else {
                            db.addRecurringAppointments(
                                    selectedDate, endDate[0], t.getTitle(), recKey,
                                    appointmentTime[0], selectedPersonId[0], selectedGroupId[0]);
                        }
                        refreshAppointments();
                        refreshDayStrip();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> resumeAutoAdvance());
        dialog.show();
    }

    // ── Add appointment dialog ────────────────────────────────────────────────

    private void showAddAppointmentDialog() {
        if (!isAdded() || getContext() == null) return;

        final List<Template> templates = db.getTemplates();

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_appointment, null);

        ((TextView) dialogView.findViewById(R.id.tv_dialog_date))
                .setText(tvSelectedDate.getText());

        final LinearLayout llTemplates = dialogView.findViewById(R.id.ll_templates);
        final EditText etCustom = dialogView.findViewById(R.id.et_custom_title);
        final Button btnCustomDate = dialogView.findViewById(R.id.btn_custom_date);
        final Button btnCustomTime = dialogView.findViewById(R.id.btn_custom_time);
        final LinearLayout llPersonPicker = dialogView.findViewById(R.id.ll_person_picker);

        final String[] customDate = {selectedDate};
        final String[] customTime = {null};
        final Long[] customPersonId = {null};
        final Long[] customGroupId  = {null};

        // Populate person picker
        List<Person> persons = db.getPersons();
        buildPersonPickerButtons(llPersonPicker, persons, customPersonId, customGroupId);

        // Initialise date button text
        try {
            btnCustomDate.setText(getString(R.string.calendar_recurrence_date,
                    LABEL_FMT.format(DATE_FMT.parse(selectedDate))));
        } catch (Exception ignored) {
            btnCustomDate.setText(selectedDate);
        }

        btnCustomDate.setOnClickListener(v -> {
            Calendar cur = Calendar.getInstance();
            try { cur.setTime(DATE_FMT.parse(customDate[0])); } catch (ParseException ignored) {}
            new DatePickerDialog(requireContext(), (picker, year, month, day) -> {
                Calendar chosen = Calendar.getInstance();
                chosen.set(year, month, day);
                customDate[0] = DATE_FMT.format(chosen.getTime());
                try {
                    btnCustomDate.setText(getString(R.string.calendar_recurrence_date,
                            LABEL_FMT.format(chosen.getTime())));
                } catch (Exception ignored) {}
            }, cur.get(Calendar.YEAR), cur.get(Calendar.MONTH),
                    cur.get(Calendar.DAY_OF_MONTH)).show();
        });

        btnCustomTime.setOnClickListener(v -> {
            Calendar cur = Calendar.getInstance();
            int hour = cur.get(Calendar.HOUR_OF_DAY);
            int minute = 0;
            if (customTime[0] != null) {
                String[] parts = customTime[0].split(":");
                try {
                    hour = Integer.parseInt(parts[0]);
                    minute = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
            new TimePickerDialog(requireContext(), (picker, h, m) -> {
                customTime[0] = String.format(Locale.US, "%02d:%02d", h, m);
                btnCustomTime.setText(getString(R.string.calendar_recurrence_time,
                        customTime[0]));
            }, hour, minute, true).show();
        });

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.calendar_add_appointment)
                .setView(dialogView)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String custom = etCustom.getText().toString().trim();
                        if (!custom.isEmpty()) {
                            db.addAppointment(customDate[0], customTime[0], custom,
                                    customPersonId[0], customGroupId[0]);
                            refreshAppointments();
                            refreshDayStrip();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        // Template buttons open the recurrence selector (consistent with header row)
        for (final Template t : templates) {
            Button btn = new Button(requireContext());
            btn.setText(t.getTitle());
            btn.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 4, 0, 4);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    showRecurrenceDialog(t);
                }
            });
            llTemplates.addView(btn);
        }

        pauseAutoAdvance();
        dialog.setOnDismissListener(d -> resumeAutoAdvance());
        dialog.show();
    }

    // ── Delete appointment ────────────────────────────────────────────────────

    private void confirmDeleteAppointment(final Appointment appointment) {
        if (!isAdded() || getContext() == null) return;
        pauseAutoAdvance();
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.calendar_delete_appointment)
                .setMessage(getString(R.string.calendar_delete_appointment_confirm,
                        appointment.getTitle()))
                .setNegativeButton(R.string.cancel, null);

        if (appointment.getSeriesId() != null) {
            // Part of a series: offer per-entry or full-series delete
            builder.setPositiveButton(R.string.calendar_delete_this_only,
                    (d, which) -> {
                        db.deleteAppointment(appointment.getId());
                        refreshAppointments();
                        refreshDayStrip();
                    });
            builder.setNeutralButton(R.string.calendar_delete_series,
                    (d, which) -> {
                        db.deleteSeriesById(appointment.getSeriesId());
                        refreshAppointments();
                        refreshDayStrip();
                    });
        } else {
            builder.setPositiveButton(R.string.delete, (d, which) -> {
                db.deleteAppointment(appointment.getId());
                refreshAppointments();
                refreshDayStrip();
            });
        }

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> resumeAutoAdvance());
        dialog.show();
    }

    // ── Manage templates dialog ───────────────────────────────────────────────

    private void showManageTemplatesDialog() {
        if (!isAdded() || getContext() == null) return;

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_manage_templates, null);

        LinearLayout llList = dialogView.findViewById(R.id.ll_template_list);
        final EditText etNew = dialogView.findViewById(R.id.et_new_template);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.calendar_manage_templates)
                .setView(dialogView)
                .setPositiveButton(R.string.calendar_add_template, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                refreshTemplateButtons();
                resumeAutoAdvance();
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                String name = etNew.getText().toString().trim();
                                if (!name.isEmpty()) {
                                    db.addTemplate(name);
                                    etNew.setText("");
                                    rebuildTemplateList(llList);
                                }
                            }
                        });
            }
        });

        rebuildTemplateList(llList);
        pauseAutoAdvance();
        dialog.show();
    }

    /** Rebuilds the template row list inside the manage-templates dialog. */
    private void rebuildTemplateList(final LinearLayout container) {
        container.removeAllViews();
        List<Template> templates = db.getTemplates();
        for (final Template t : templates) {
            View row = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_template_row, container, false);
            ((TextView) row.findViewById(R.id.tv_template_title)).setText(t.getTitle());
            row.findViewById(R.id.btn_delete_template)
                    .setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            db.deleteTemplate(t.getId());
                            rebuildTemplateList(container);
                        }
                    });
            container.addView(row);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (db != null) db.close();
    }

    // ── Person picker helper ──────────────────────────────────────────────────

    /**
     * Fills a horizontal LinearLayout with "Keine" + one button per person + one button per group.
     * Tapping a person button selects that person (clears group); tapping a group button selects
     * that group (clears person); tapping "Keine" clears both.
     */
    private void buildPersonPickerButtons(final LinearLayout container,
                                          final List<Person> persons,
                                          final Long[] selectedPersonId,
                                          final Long[] selectedGroupId) {
        container.removeAllViews();
        int marginPx = dpToPx(6);
        int accentColor   = ContextCompat.getColor(requireContext(), R.color.accent);
        int textSecondary = ContextCompat.getColor(requireContext(), R.color.text_secondary);

        boolean noneSelected = selectedPersonId[0] == null && selectedGroupId[0] == null;

        // "Keine" button
        Button btnNone = new Button(requireContext());
        btnNone.setText(R.string.calendar_person_none);
        btnNone.setAllCaps(false);
        btnNone.setTextSize(13f);
        btnNone.setTextColor(noneSelected ? accentColor : textSecondary);
        LinearLayout.LayoutParams lpNone = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lpNone.setMargins(0, 0, marginPx, 0);
        btnNone.setLayoutParams(lpNone);
        btnNone.setOnClickListener(v -> {
            selectedPersonId[0] = null;
            selectedGroupId[0]  = null;
            buildPersonPickerButtons(container, persons, selectedPersonId, selectedGroupId);
        });
        container.addView(btnNone);

        for (final Person p : persons) {
            Button btn = new Button(requireContext());
            btn.setText(p.getName());
            btn.setAllCaps(false);
            btn.setTextSize(13f);
            int personColor;
            try {
                personColor = Color.parseColor(p.getColor());
            } catch (IllegalArgumentException e) {
                personColor = accentColor;
            }
            boolean isSelected = selectedPersonId[0] != null
                    && selectedPersonId[0].equals(p.getId());
            btn.setTextColor(isSelected ? personColor : textSecondary);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, marginPx, 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                selectedPersonId[0] = p.getId();
                selectedGroupId[0]  = null;
                buildPersonPickerButtons(container, persons, selectedPersonId, selectedGroupId);
            });
            container.addView(btn);
        }

        // Group buttons
        List<PersonGroup> groups = db.getPersonGroups();
        for (final PersonGroup g : groups) {
            Button btn = new Button(requireContext());
            btn.setText("👥 " + g.getName());
            btn.setAllCaps(false);
            btn.setTextSize(13f);
            boolean isSelected = selectedGroupId[0] != null && selectedGroupId[0].equals(g.getId());
            btn.setTextColor(isSelected ? accentColor : textSecondary);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, marginPx, 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                selectedGroupId[0]  = g.getId();
                selectedPersonId[0] = null;
                buildPersonPickerButtons(container, persons, selectedPersonId, selectedGroupId);
            });
            container.addView(btn);
        }
    }

    // ── Manage persons dialog ─────────────────────────────────────────────────

    private void showManagePersonsDialog() {
        if (!isAdded() || getContext() == null) return;

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_manage_persons, null);

        final LinearLayout llPersonList = dialogView.findViewById(R.id.ll_person_list);
        final LinearLayout llGroupList  = dialogView.findViewById(R.id.ll_group_list);
        final EditText etNewPerson = dialogView.findViewById(R.id.et_new_person);
        final EditText etNewGroup  = dialogView.findViewById(R.id.et_new_group);

        dialogView.findViewById(R.id.btn_add_person).setOnClickListener(v -> {
            String name = etNewPerson.getText().toString().trim();
            if (!name.isEmpty()) {
                // Auto-assign next color from palette
                int colorIdx = db.getPersons().size() % CalendarDatabaseHelper.PERSON_COLORS.length;
                db.addPerson(name, CalendarDatabaseHelper.PERSON_COLORS[colorIdx]);
                etNewPerson.setText("");
                rebuildPersonList(llPersonList);
            }
        });

        dialogView.findViewById(R.id.btn_add_group).setOnClickListener(v -> {
            String name = etNewGroup.getText().toString().trim();
            if (!name.isEmpty()) {
                db.addPersonGroup(name);
                etNewGroup.setText("");
                rebuildGroupList(llGroupList);
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.calendar_manage_persons)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnDismissListener(d -> {
            activeFilterPersonIds = null;
            activeFilterGroupId   = null;
            refreshPersonFilter();
            refreshAppointments();
            refreshDayStrip();
            resumeAutoAdvance();
        });

        rebuildPersonList(llPersonList);
        rebuildGroupList(llGroupList);
        pauseAutoAdvance();
        dialog.show();
    }

    private void rebuildPersonList(final LinearLayout container) {
        container.removeAllViews();
        List<Person> persons = db.getPersons();
        if (persons.isEmpty()) {
            TextView tvEmpty = new TextView(requireContext());
            tvEmpty.setText("–");
            tvEmpty.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            container.addView(tvEmpty);
            return;
        }
        for (final Person p : persons) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
            row.setLayoutParams(rowLp);

            // Color dot
            View dot = new View(requireContext());
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dpToPx(12), dpToPx(12));
            dotLp.setMargins(0, 0, dpToPx(8), 0);
            dot.setLayoutParams(dotLp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            try {
                gd.setColor(Color.parseColor(p.getColor()));
            } catch (IllegalArgumentException e) {
                gd.setColor(Color.GRAY);
            }
            dot.setBackground(gd);
            row.addView(dot);

            // Name
            TextView tv = new TextView(requireContext());
            tv.setText(p.getName());
            tv.setTextSize(16f);
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(tvLp);
            row.addView(tv);

            // Delete button
            Button btnDel = new Button(requireContext());
            btnDel.setText(R.string.delete);
            btnDel.setAllCaps(false);
            btnDel.setTextSize(13f);
            btnDel.setOnClickListener(v -> {
                db.deletePerson(p.getId());
                rebuildPersonList(container);
            });
            row.addView(btnDel);

            container.addView(row);
        }
    }

    private void rebuildGroupList(final LinearLayout container) {
        container.removeAllViews();
        List<PersonGroup> groups = db.getPersonGroups();
        List<Person> allPersons = db.getPersons();
        if (groups.isEmpty()) {
            TextView tvEmpty = new TextView(requireContext());
            tvEmpty.setText("–");
            tvEmpty.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            container.addView(tvEmpty);
            return;
        }
        for (final PersonGroup g : groups) {
            LinearLayout groupSection = new LinearLayout(requireContext());
            groupSection.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            secLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
            groupSection.setLayoutParams(secLp);

            // Group name row
            LinearLayout nameRow = new LinearLayout(requireContext());
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvName = new TextView(requireContext());
            tvName.setText("👥 " + g.getName());
            tvName.setTextSize(16f);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(tvLp);
            nameRow.addView(tvName);

            Button btnDel = new Button(requireContext());
            btnDel.setText(R.string.delete);
            btnDel.setAllCaps(false);
            btnDel.setTextSize(13f);
            btnDel.setOnClickListener(v -> {
                db.deletePersonGroup(g.getId());
                rebuildGroupList(container);
            });
            nameRow.addView(btnDel);
            groupSection.addView(nameRow);

            // Member checkboxes
            List<Long> memberIds = g.getMemberIds();
            for (final Person p : allPersons) {
                CheckBox cb = new CheckBox(requireContext());
                cb.setText(p.getName());
                cb.setChecked(memberIds.contains(p.getId()));
                cb.setTextSize(14f);
                cb.setPadding(dpToPx(16), dpToPx(2), 0, dpToPx(2));
                cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        db.addGroupMember(g.getId(), p.getId());
                    } else {
                        db.removeGroupMember(g.getId(), p.getId());
                    }
                });
                groupSection.addView(cb);
            }

            container.addView(groupSection);
        }
    }
}

package com.kitchenboard.calendar;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CalendarView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kitchenboard.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CalendarFragment extends Fragment {

    private static final SimpleDateFormat DATE_FMT  =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat LABEL_FMT =
            new SimpleDateFormat("EEEE, dd. MMMM yyyy", Locale.GERMANY);

    private CalendarDatabaseHelper db;
    private AppointmentAdapter adapter;
    private TextView tvSelectedDate;
    private TextView tvEmpty;

    /** Currently selected date in YYYY-MM-DD format. */
    private String selectedDate;

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

        tvSelectedDate = view.findViewById(R.id.tv_selected_date);
        tvEmpty = view.findViewById(R.id.tv_appointments_empty);

        RecyclerView rv = view.findViewById(R.id.rv_appointments);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);

        // Default to today
        selectedDate = DATE_FMT.format(new Date());
        updateDateLabel();

        CalendarView calendarView = view.findViewById(R.id.calendar_view);
        calendarView.setOnDateChangeListener(new CalendarView.OnDateChangeListener() {
            @Override
            public void onSelectedDayChange(@NonNull CalendarView view,
                                            int year, int month, int dayOfMonth) {
                Calendar cal = Calendar.getInstance();
                cal.set(year, month, dayOfMonth);
                selectedDate = DATE_FMT.format(cal.getTime());
                updateDateLabel();
                refreshAppointments();
            }
        });

        FloatingActionButton fab = view.findViewById(R.id.fab_add_appointment);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddAppointmentDialog();
            }
        });

        ImageButton btnManage = view.findViewById(R.id.btn_manage_templates);
        btnManage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showManageTemplatesDialog();
            }
        });

        adapter.setOnDeleteListener(new AppointmentAdapter.OnDeleteListener() {
            @Override
            public void onDelete(Appointment appointment) {
                confirmDeleteAppointment(appointment);
            }
        });

        refreshAppointments();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateDateLabel() {
        try {
            Date d = DATE_FMT.parse(selectedDate);
            tvSelectedDate.setText(LABEL_FMT.format(d));
        } catch (Exception e) {
            tvSelectedDate.setText(selectedDate);
        }
    }

    private void refreshAppointments() {
        List<Appointment> list = db.getAppointmentsForDate(selectedDate);
        adapter.setItems(list);
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ── Add appointment dialog ────────────────────────────────────────────────

    private void showAddAppointmentDialog() {
        final List<Template> templates = db.getTemplates();

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_appointment, null);

        ((TextView) dialogView.findViewById(R.id.tv_dialog_date))
                .setText(tvSelectedDate.getText());

        final LinearLayout llTemplates = dialogView.findViewById(R.id.ll_templates);
        final EditText etCustom = dialogView.findViewById(R.id.et_custom_title);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.calendar_add_appointment)
                .setView(dialogView)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String custom = etCustom.getText().toString().trim();
                        if (!custom.isEmpty()) {
                            db.addAppointment(selectedDate, custom);
                            refreshAppointments();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        // Build one button per standard template; each dismisses the dialog on tap
        for (final Template t : templates) {
            android.widget.Button btn = new android.widget.Button(requireContext());
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
                    db.addAppointment(selectedDate, t.getTitle());
                    refreshAppointments();
                    dialog.dismiss();
                }
            });
            llTemplates.addView(btn);
        }

        dialog.show();
    }

    // ── Delete appointment ────────────────────────────────────────────────────

    private void confirmDeleteAppointment(final Appointment appointment) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.calendar_delete_appointment)
                .setMessage(getString(R.string.calendar_delete_appointment_confirm,
                        appointment.getTitle()))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        db.deleteAppointment(appointment.getId());
                        refreshAppointments();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Manage templates dialog ───────────────────────────────────────────────

    private void showManageTemplatesDialog() {
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

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                // Override positive button to avoid auto-dismiss
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
}

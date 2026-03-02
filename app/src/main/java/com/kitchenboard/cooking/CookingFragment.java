package com.kitchenboard.cooking;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kitchenboard.R;

import java.util.ArrayList;
import java.util.List;

public class CookingFragment extends Fragment {

    private static final int[] FILTER_DAYS          = {7, 14, 30, 60, 9999};
    private static final int   DEFAULT_FILTER_INDEX = 2; // 30 Tage

    private CookingDatabaseHelper db;
    private DishAdapter           recentAdapter;
    private DishAdapter           suggestionsAdapter;
    private RecyclerView          rvRecentlyCooked;
    private RecyclerView          rvSuggestions;
    private Spinner               spinnerFilterDays;
    private Button                btnSortToggle;

    private int     currentFilterIndex = DEFAULT_FILTER_INDEX;
    private boolean sortByDuration     = false;

    private ActivityResultLauncher<Intent> voiceLauncher;
    /** Holds a reference to the notes EditText currently shown in the edit dialog. */
    private EditText pendingNotesField;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        voiceLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null
                                && pendingNotesField != null) {
                            ArrayList<String> matches = result.getData()
                                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                            if (matches != null && !matches.isEmpty()) {
                                String current = pendingNotesField.getText().toString().trim();
                                String spoken  = matches.get(0);
                                pendingNotesField.setText(
                                        current.isEmpty() ? spoken : current + " " + spoken);
                            }
                        }
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cooking, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = new CookingDatabaseHelper(requireContext());

        // ── Left panel: recently cooked ───────────────────────────────────────
        rvRecentlyCooked = view.findViewById(R.id.rv_recently_cooked);
        rvRecentlyCooked.setLayoutManager(new LinearLayoutManager(requireContext()));
        recentAdapter = new DishAdapter(new ArrayList<Dish>(),
                new DishAdapter.OnDishClickListener() {
                    @Override
                    public void onDishClick(Dish dish) {
                        markAsCooked(dish);
                    }

                    @Override
                    public void onDishLongClick(Dish dish) {
                        showEditDishDialog(dish, null);
                    }
                });
        rvRecentlyCooked.setAdapter(recentAdapter);

        // ── Right panel: suggestions ──────────────────────────────────────────
        rvSuggestions = view.findViewById(R.id.rv_suggestions);
        rvSuggestions.setLayoutManager(new LinearLayoutManager(requireContext()));
        suggestionsAdapter = new DishAdapter(new ArrayList<Dish>(),
                new DishAdapter.OnDishClickListener() {
                    @Override
                    public void onDishClick(Dish dish) {
                        markAsCooked(dish);
                    }

                    @Override
                    public void onDishLongClick(Dish dish) {
                        showEditDishDialog(dish, null);
                    }
                });
        rvSuggestions.setAdapter(suggestionsAdapter);

        // ── Filter spinner ────────────────────────────────────────────────────
        spinnerFilterDays = view.findViewById(R.id.spinner_filter_days);
        String[] filterLabels = {"7 Tage", "14 Tage", "30 Tage", "60 Tage",
                getString(R.string.cooking_all_filter)};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, filterLabels);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFilterDays.setAdapter(spinnerAdapter);
        spinnerFilterDays.setSelection(DEFAULT_FILTER_INDEX);
        spinnerFilterDays.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                currentFilterIndex = position;
                refreshSuggestionsList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        // ── Sort toggle button ────────────────────────────────────────────────
        btnSortToggle = view.findViewById(R.id.btn_sort_toggle);
        btnSortToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sortByDuration = !sortByDuration;
                btnSortToggle.setText(sortByDuration
                        ? getString(R.string.cooking_sort_by_duration)
                        : getString(R.string.cooking_sort_by_date));
                refreshSuggestionsList();
            }
        });

        // ── FAB ───────────────────────────────────────────────────────────────
        FloatingActionButton fab = view.findViewById(R.id.fab_manage_dishes);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showManageDialog();
            }
        });

        refreshLists();
    }

    // ── Data helpers ──────────────────────────────────────────────────────────

    private void refreshLists() {
        refreshRecentList();
        refreshSuggestionsList();
    }

    private void refreshRecentList() {
        recentAdapter.setItems(db.getRecentlyCooked(15));
    }

    private void refreshSuggestionsList() {
        int days = FILTER_DAYS[currentFilterIndex];
        List<Dish> suggestions = sortByDuration
                ? db.getLongNotCookedByDuration(days)
                : db.getLongNotCooked(days);
        suggestionsAdapter.setItems(suggestions);
    }

    private void markAsCooked(Dish dish) {
        db.markAsCooked(dish.id);
        Toast.makeText(requireContext(),
                getString(R.string.cooking_marked_cooked, dish.name),
                Toast.LENGTH_SHORT).show();
        refreshLists();
    }

    // ── Manage dialog ─────────────────────────────────────────────────────────

    private void showManageDialog() {
        ScrollView scrollView = new ScrollView(requireContext());
        final LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.panel_padding);
        container.setPadding(pad, pad, pad, pad);
        scrollView.addView(container);

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.cooking_manage))
                .setView(scrollView)
                .setNegativeButton(R.string.cancel, null);

        final AlertDialog dialog = builder.create();
        populateManageDialog(container, dialog);
        dialog.show();
    }

    private void populateManageDialog(final LinearLayout container, final AlertDialog dialog) {
        container.removeAllViews();

        // "Neues Gericht" button at top
        Button btnAddNew = new Button(requireContext());
        btnAddNew.setText(getString(R.string.cooking_add_new));
        btnAddNew.setAllCaps(false);
        btnAddNew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showEditDishDialog(null, new Runnable() {
                    @Override
                    public void run() {
                        showManageDialog();
                    }
                });
            }
        });
        container.addView(btnAddNew);

        // Divider
        container.addView(makeDivider());

        // One row per dish
        List<Dish> allDishes = db.getAllDishes();
        int spacing = getResources().getDimensionPixelSize(R.dimen.spacing_small);

        for (final Dish dish : allDishes) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, spacing, 0, spacing);

            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            TextView tvName = new TextView(requireContext());
            tvName.setText(dish.name);
            tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
            tvName.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
            tvName.setLayoutParams(nameParams);
            row.addView(tvName);

            Button btnEdit = new Button(requireContext());
            btnEdit.setText("\u270F");
            btnEdit.setAllCaps(false);
            btnEdit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    showEditDishDialog(dish, new Runnable() {
                        @Override
                        public void run() {
                            showManageDialog();
                        }
                    });
                }
            });
            row.addView(btnEdit);

            Button btnDelete = new Button(requireContext());
            btnDelete.setText("\uD83D\uDDD1");
            btnDelete.setAllCaps(false);
            btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.cooking_delete_confirm, dish.name))
                            .setPositiveButton(R.string.delete,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface di, int which) {
                                            db.deleteDish(dish.id);
                                            refreshLists();
                                            populateManageDialog(container, dialog);
                                        }
                                    })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                }
            });
            row.addView(btnDelete);

            container.addView(row);
            container.addView(makeDivider());
        }
    }

    // ── Add / edit dish dialog ────────────────────────────────────────────────

    private void showEditDishDialog(final Dish existingDish, final Runnable onDismissCallback) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_edit_dish, null);

        final EditText etName        = dialogView.findViewById(R.id.et_dish_name);
        final EditText etDuration    = dialogView.findViewById(R.id.et_duration_minutes);
        final EditText etIngredients = dialogView.findViewById(R.id.et_ingredients);
        final EditText etNotes       = dialogView.findViewById(R.id.et_notes);
        ImageButton    btnVoice      = dialogView.findViewById(R.id.btn_voice_input);

        // Pre-fill when editing
        if (existingDish != null) {
            etName.setText(existingDish.name);
            if (existingDish.durationMinutes > 0) {
                etDuration.setText(String.valueOf(existingDish.durationMinutes));
            }
            if (existingDish.ingredients != null) etIngredients.setText(existingDish.ingredients);
            if (existingDish.notes != null)       etNotes.setText(existingDish.notes);
        }

        btnVoice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingNotesField = etNotes;
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
                intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                        getString(R.string.cooking_voice_input));
                try {
                    voiceLauncher.launch(intent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(requireContext(),
                            "Spracherkennung nicht verfügbar", Toast.LENGTH_SHORT).show();
                }
            }
        });

        String title = existingDish == null
                ? getString(R.string.cooking_add_title)
                : getString(R.string.cooking_edit_title);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(dialogView)
                .setPositiveButton("Speichern", null) // overridden below to prevent auto-dismiss
                .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        if (onDismissCallback != null) onDismissCallback.run();
                    }
                })
                .create();

        dialog.show();

        // Override positive button to validate before dismissing
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = etName.getText().toString().trim();
                if (name.isEmpty()) {
                    etName.setError(getString(R.string.cooking_name_hint));
                    return;
                }
                int duration = 0;
                String durStr = etDuration.getText().toString().trim();
                if (!durStr.isEmpty()) {
                    try { duration = Integer.parseInt(durStr); }
                    catch (NumberFormatException ignored) { }
                }
                String ingredients = etIngredients.getText().toString().trim();
                String notes       = etNotes.getText().toString().trim();

                if (existingDish == null) {
                    db.addDish(name, duration,
                            ingredients.isEmpty() ? null : ingredients,
                            notes.isEmpty()       ? null : notes);
                } else {
                    db.updateDish(existingDish.id, name, duration,
                            ingredients.isEmpty() ? null : ingredients,
                            notes.isEmpty()       ? null : notes);
                }
                refreshLists();
                dialog.dismiss();
                if (onDismissCallback != null) onDismissCallback.run();
            }
        });
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private View makeDivider() {
        View divider = new View(requireContext());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.divider));
        return divider;
    }
}

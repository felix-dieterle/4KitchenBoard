package com.kitchenboard.shopping;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kitchenboard.R;

import java.util.List;

public class ShoppingFragment extends Fragment {

    private static final String PREFS_NAME = "shopping_prefs";
    private static final String PREF_SERVER_URL = "server_url";

    private ShoppingDatabaseHelper db;
    private ShoppingAdapter adapter;
    private TextView tvEmpty;
    private TextView tvSyncStatus;

    /** Non-null when a valid server URL is configured. */
    private ShoppingApiClient apiClient;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_shopping, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = new ShoppingDatabaseHelper(requireContext());
        adapter = new ShoppingAdapter();
        tvEmpty = view.findViewById(R.id.tv_empty);
        tvSyncStatus = view.findViewById(R.id.tv_sync_status);

        RecyclerView recyclerView = view.findViewById(R.id.rv_shopping);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        FloatingActionButton fab = view.findViewById(R.id.fab_add);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddItemDialog();
            }
        });

        ImageButton btnSyncConfigure = view.findViewById(R.id.btn_sync_configure);
        btnSyncConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSyncConfigDialog();
            }
        });

        adapter.setOnItemCheckedListener(new ShoppingAdapter.OnItemCheckedListener() {
            @Override
            public void onItemChecked(ShoppingItem item) {
                if (apiClient != null) {
                    apiClient.checkItem(item.getId(), new ShoppingApiClient.Callback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            refreshList();
                        }
                        @Override
                        public void onError(String message) {
                            showSyncError();
                        }
                    });
                } else {
                    db.checkItem(item.getId());
                    refreshList();
                }
            }
        });

        adapter.setOnItemLongClickListener(new ShoppingAdapter.OnItemLongClickListener() {
            @Override
            public void onItemLongClick(final ShoppingItem item) {
                showDeleteConfirmation(item);
            }
        });

        adapter.setOnQuantityChangedListener(new ShoppingAdapter.OnQuantityChangedListener() {
            @Override
            public void onQuantityChanged(ShoppingItem item, int newQuantity) {
                if (apiClient != null) {
                    apiClient.updateItemQuantity(item.getId(), newQuantity,
                            new ShoppingApiClient.Callback<Void>() {
                        @Override
                        public void onSuccess(Void result) { /* quantity updated on server */ }
                        @Override
                        public void onError(String message) { showSyncError(); }
                    });
                } else {
                    db.updateItemQuantity(item.getId(), newQuantity);
                }
            }
        });

        // Initialise API client from stored preferences
        initApiClient();
        refreshList();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-read server URL in case it was updated
        initApiClient();
        if (apiClient != null) {
            refreshList();
        }
    }

    // ── Sync helpers ──────────────────────────────────────────────────────────

    private void initApiClient() {
        String url = loadServerUrl();
        apiClient = (url != null && !url.isEmpty()) ? new ShoppingApiClient(url) : null;
    }

    private String loadServerUrl() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_SERVER_URL, "");
    }

    private void saveServerUrl(String url) {
        requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_SERVER_URL, url)
                .apply();
    }

    private void showSyncConfigDialog() {
        final EditText etUrl = new EditText(requireContext());
        etUrl.setHint(R.string.sync_url_hint);
        etUrl.setSingleLine(true);
        etUrl.setText(loadServerUrl());

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sync_url_title)
                .setMessage(R.string.sync_url_message)
                .setView(etUrl)
                .setPositiveButton(R.string.sync_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = etUrl.getText().toString().trim();
                        saveServerUrl(url);
                        initApiClient();
                        refreshList();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showSyncError() {
        if (tvSyncStatus == null) return;
        tvSyncStatus.setText(R.string.sync_status_error);
        tvSyncStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.error));
        tvSyncStatus.setVisibility(View.VISIBLE);
    }

    private void showSyncOk() {
        if (tvSyncStatus == null) return;
        tvSyncStatus.setText(R.string.sync_status_ok);
        tvSyncStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent));
        tvSyncStatus.setVisibility(View.VISIBLE);
    }

    // ── List management ───────────────────────────────────────────────────────

    private void refreshList() {
        if (apiClient != null) {
            apiClient.fetchItems(new ShoppingApiClient.Callback<List<ShoppingItem>>() {
                @Override
                public void onSuccess(List<ShoppingItem> items) {
                    showSyncOk();
                    adapter.setItems(items);
                    tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override
                public void onError(String message) {
                    showSyncError();
                    // Fall back to local data so the UI is never empty on error
                    List<ShoppingItem> localItems = db.getActiveItems();
                    adapter.setItems(localItems);
                    tvEmpty.setVisibility(localItems.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        } else {
            if (tvSyncStatus != null) tvSyncStatus.setVisibility(View.GONE);
            List<ShoppingItem> items = db.getActiveItems();
            adapter.setItems(items);
            tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void showAddItemDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_item, null);

        final AutoCompleteTextView etName =
                dialogView.findViewById(R.id.et_item_name);
        final AutoCompleteTextView etCategory =
                dialogView.findViewById(R.id.et_category);
        final TextView tvQuantity = dialogView.findViewById(R.id.tv_quantity);
        final Button btnMinus = dialogView.findViewById(R.id.btn_qty_minus);
        final Button btnPlus = dialogView.findViewById(R.id.btn_qty_plus);

        // Mutable quantity holder
        final int[] quantity = {1};
        tvQuantity.setText("1");

        btnMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (quantity[0] > 1) {
                    quantity[0]--;
                    tvQuantity.setText(String.valueOf(quantity[0]));
                }
            }
        });
        btnPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                quantity[0]++;
                tvQuantity.setText(String.valueOf(quantity[0]));
            }
        });

        // Populate name suggestions from history
        List<String> history = db.getAllItemNames();
        ArrayAdapter<String> suggestAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, history);
        etName.setAdapter(suggestAdapter);
        etName.setThreshold(1);

        // Populate category suggestions from saved categories
        List<String> categories = db.getCategories();
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, categories);
        etCategory.setAdapter(catAdapter);
        etCategory.setThreshold(1);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.add_item)
                .setView(dialogView)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String name = etName.getText().toString().trim();
                        String rawCategory = etCategory.getText().toString().trim();
                        // Use default category when none provided
                        final String category = rawCategory.isEmpty()
                                ? getString(R.string.category_default) : rawCategory;
                        if (name.isEmpty()) return;
                        final int qty = quantity[0];

                        if (apiClient != null) {
                            apiClient.addItem(name, category, qty,
                                    new ShoppingApiClient.Callback<ShoppingItem>() {
                                @Override
                                public void onSuccess(ShoppingItem item) {
                                    // Save category locally for autocomplete suggestions only
                                    db.addCategory(category);
                                    refreshList();
                                }
                                @Override
                                public void onError(String message) {
                                    showSyncError();
                                }
                            });
                        } else {
                            db.addCategory(category);
                            db.addItem(name, category, qty);
                            refreshList();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.show();

        // Pressing Next on the name field moves focus to the category field.
        // Must be set after show() so dialog.getButton() returns a non-null reference.
        etName.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    etCategory.requestFocus();
                    return true;
                }
                return false;
            }
        });

        // Pressing Done or Next on the category field triggers Add.
        etCategory.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_NEXT
                        || actionId == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    return true;
                }
                return false;
            }
        });
    }

    private void showDeleteConfirmation(final ShoppingItem item) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_item)
                .setMessage(getString(R.string.delete_item_confirm, item.getName()))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (apiClient != null) {
                            apiClient.deleteItem(item.getId(),
                                    new ShoppingApiClient.Callback<Void>() {
                                @Override
                                public void onSuccess(Void result) {
                                    refreshList();
                                }
                                @Override
                                public void onError(String message) {
                                    showSyncError();
                                }
                            });
                        } else {
                            db.deleteItem(item.getId());
                            refreshList();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (db != null) db.close();
    }
}

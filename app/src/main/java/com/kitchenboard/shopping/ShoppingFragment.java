package com.kitchenboard.shopping;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.kitchenboard.R;
import com.kitchenboard.feedback.FeatureRequestHelper;

import java.util.List;
import java.util.Locale;

public class ShoppingFragment extends Fragment {

    private static final String PREFS_NAME         = "shopping_prefs";
    private static final String PREF_SERVER_URL    = "server_url";
    private static final String PREF_SERVER_HOST   = "server_host";
    private static final String PREF_SERVER_BASEPATH = "server_basepath";
    private static final String PREF_BOARD_TOKEN   = "board_token";
    private static final String PREF_API_TOKEN     = "api_token";

    /** Default API base path used when no custom path has been configured. */
    private static final String DEFAULT_BASEPATH = "/apps/kitchenboard/api.php";
    private static final String PREF_PENDING_QR_NAME = "pending_qr_name";
    private static final String PREF_PENDING_QR_CATEGORY = "pending_qr_category";

    private static final int QR_SIZE_PX = 512;

    /** Periodic sync interval: 5 minutes. */
    private static final long SYNC_INTERVAL_MS = 5 * 60 * 1000L;

    private ShoppingDatabaseHelper db;
    private ShoppingAdapter adapter;
    private TextView tvEmpty;
    private TextView tvSyncStatus;

    /** Non-null when a valid server URL is configured. */
    private ShoppingApiClient apiClient;

    /**
     * Cached list of stores with GPS coordinates, used for geofence registration.
     * Refreshed every time the fragment resumes.
     */
    private java.util.List<StoreLocation> cachedStoreLocations = new java.util.ArrayList<>();

    /** Handler for periodic sync on the main thread. */
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            periodicSync();
            syncHandler.postDelayed(this, SYNC_INTERVAL_MS);
        }
    };

    private final FeatureRequestHelper featureRequestHelper =
            new FeatureRequestHelper(this, "Einkaufen");

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

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
        adapter.setNoShopLabel(getString(R.string.no_shop_label));
        adapter.setDatabase(db);
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

        FloatingActionButton fabScan = view.findViewById(R.id.fab_scan);
        fabScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchQrScanner();
            }
        });

        FloatingActionButton fabPrint = view.findViewById(R.id.fab_print);
        fabPrint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPrintQrDialog();
            }
        });

        FloatingActionButton fabShare = view.findViewById(R.id.fab_share);
        fabShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareShoppingList();
            }
        });

        ImageButton btnSyncConfigure = view.findViewById(R.id.btn_sync_configure);
        btnSyncConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSyncConfigDialog();
            }
        });

        ImageButton btnFeatureRequest = view.findViewById(R.id.btn_feature_request);
        btnFeatureRequest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                featureRequestHelper.show();
            }
        });

        setupRotationToggle(view, 0);

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

        adapter.setOnShowQrListener(new ShoppingAdapter.OnShowQrListener() {
            @Override
            public void onShowQr(ShoppingItem item) {
                showQrCodeDialog(item);
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

        // Grouping toggle: category vs shop
        RadioGroup rgGrouping = view.findViewById(R.id.rg_grouping);
        rgGrouping.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                adapter.setGroupByShop(checkedId == R.id.rb_group_shop);
                refreshList();
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
            syncHandler.removeCallbacks(syncRunnable);
            syncHandler.postDelayed(syncRunnable, SYNC_INTERVAL_MS);
            refreshList();
        }
        checkPendingQrItem();
        // Load stores with GPS coordinates and register proximity alerts.
        cachedStoreLocations = db.getStoresWithLocation();
        StoreGeofenceHelper.registerAll(requireContext(), cachedStoreLocations);
    }

    @Override
    public void onPause() {
        super.onPause();
        syncHandler.removeCallbacks(syncRunnable);
        // Remove proximity alerts to avoid stale registrations when stores change.
        StoreGeofenceHelper.unregisterAll(requireContext(), cachedStoreLocations);
    }

    // ── Sync helpers ──────────────────────────────────────────────────────────

    private void initApiClient() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String url      = prefs.getString(PREF_SERVER_URL, "");
        String token    = prefs.getString(PREF_BOARD_TOKEN, "");
        String apiToken = prefs.getString(PREF_API_TOKEN, "");
        apiClient = (url != null && !url.isEmpty()) ? new ShoppingApiClient(requireContext(), url, token, apiToken) : null;
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

    /**
     * Constructs a full API URL from a host (hostname or IP, optionally with scheme)
     * and a base path. Prepends {@code http://} when no scheme is present.
     */
    private static String buildServerUrl(String host, String basepath) {
        if (host == null || host.trim().isEmpty()) return "";
        host = host.trim();
        if (!host.startsWith("http://") && !host.startsWith("https://")) {
            host = "http://" + host;
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        if (basepath == null || basepath.trim().isEmpty()) {
            basepath = DEFAULT_BASEPATH;
        } else {
            basepath = basepath.trim();
            if (!basepath.startsWith("/")) {
                basepath = "/" + basepath;
            }
        }
        return host + basepath;
    }

    /** Extracts the host (with scheme and optional port) from a full URL for migration. */
    private static String extractHost(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            java.net.URL u = new java.net.URL(url);
            String port = u.getPort() != -1 ? ":" + u.getPort() : "";
            return u.getProtocol() + "://" + u.getHost() + port;
        } catch (Exception e) {
            return url;
        }
    }

    /** Extracts the base path from a full URL for migration. */
    private static String extractBasePath(String url) {
        if (url == null || url.isEmpty()) return DEFAULT_BASEPATH;
        try {
            String path = new java.net.URL(url).getPath();
            return path.isEmpty() ? DEFAULT_BASEPATH : path;
        } catch (Exception e) {
            return DEFAULT_BASEPATH;
        }
    }

    private void showSyncConfigDialog() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        int pad = requireContext().getResources().getDimensionPixelSize(
                R.dimen.panel_padding);

        // Populate host/basepath from saved values; fall back to parsing legacy full URL.
        String savedHost     = prefs.getString(PREF_SERVER_HOST, "");
        String savedBasepath = prefs.getString(PREF_SERVER_BASEPATH, "");
        if (savedHost.isEmpty()) {
            String legacyUrl = prefs.getString(PREF_SERVER_URL, "");
            if (!legacyUrl.isEmpty()) {
                savedHost     = extractHost(legacyUrl);
                savedBasepath = extractBasePath(legacyUrl);
            }
        }
        if (savedBasepath.isEmpty()) {
            savedBasepath = DEFAULT_BASEPATH;
        }

        final EditText etHost = new EditText(requireContext());
        etHost.setHint(R.string.sync_host_hint);
        etHost.setSingleLine(true);
        etHost.setText(savedHost);

        final EditText etBasepath = new EditText(requireContext());
        etBasepath.setHint(R.string.sync_basepath_hint);
        etBasepath.setSingleLine(true);
        etBasepath.setText(savedBasepath);

        final TextView tvTokenDesc = new TextView(requireContext());
        tvTokenDesc.setText(R.string.board_token_description);
        tvTokenDesc.setTextSize(12f);

        final EditText etToken = new EditText(requireContext());
        etToken.setHint(R.string.board_token_hint);
        etToken.setSingleLine(true);
        etToken.setText(prefs.getString(PREF_BOARD_TOKEN, ""));

        final TextView tvApiTokenDesc = new TextView(requireContext());
        tvApiTokenDesc.setText(R.string.api_token_description);
        tvApiTokenDesc.setTextSize(12f);

        final EditText etApiToken = new EditText(requireContext());
        etApiToken.setHint(R.string.api_token_hint);
        etApiToken.setSingleLine(true);
        etApiToken.setText(prefs.getString(PREF_API_TOKEN, ""));

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(etHost);
        layout.addView(etBasepath);
        layout.addView(tvTokenDesc);
        layout.addView(etToken);
        layout.addView(tvApiTokenDesc);
        layout.addView(etApiToken);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.sync_url_title)
                .setMessage(R.string.sync_url_message)
                .setView(layout)
                .setPositiveButton(R.string.sync_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String host     = etHost.getText().toString().trim();
                        String basepath = etBasepath.getText().toString().trim();
                        String token    = etToken.getText().toString().trim();
                        String apiToken = etApiToken.getText().toString().trim();
                        String url      = buildServerUrl(host, basepath);
                        prefs.edit()
                                .putString(PREF_SERVER_HOST,    host)
                                .putString(PREF_SERVER_BASEPATH, basepath)
                                .putString(PREF_SERVER_URL,     url)
                                .putString(PREF_BOARD_TOKEN,    token)
                                .putString(PREF_API_TOKEN,      apiToken)
                                .apply();
                        initApiClient();
                        refreshList();
                    }
                })
                .setNeutralButton(R.string.board_token_copy_config, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.show();
        // Override neutral button to avoid auto-dismiss so we can copy config
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String host     = etHost.getText().toString().trim();
            String basepath = etBasepath.getText().toString().trim();
            String token    = etToken.getText().toString().trim();
            String apiToken = etApiToken.getText().toString().trim();
            String url      = buildServerUrl(host, basepath);
            String config = url
                    + (token.isEmpty()    ? "" : "\nToken: "     + token)
                    + (apiToken.isEmpty() ? "" : "\nAPI-Token: " + apiToken);
            ClipboardManager cm = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("KitchenBoard Sync Config", config));
            Toast.makeText(requireContext(), R.string.board_token_copied, Toast.LENGTH_SHORT).show();
        });
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

    /**
     * Fetches the current item list from the server and updates the displayed list.
     * Falls back to the currently displayed data on error so the UI remains functional.
     */
    private void periodicSync() {
        if (apiClient == null || !isAdded()) return;
        apiClient.fetchItems(new ShoppingApiClient.Callback<List<ShoppingItem>>() {
            @Override
            public void onSuccess(List<ShoppingItem> remoteItems) {
                if (!isAdded()) return;
                showSyncOk();
                adapter.setItems(remoteItems);
                tvEmpty.setVisibility(remoteItems.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                showSyncError();
                // Continue using currently displayed data – no UI change needed
            }
        });
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
        final TextView tvShopName = dialogView.findViewById(R.id.tv_shop_name);
        final Button btnSearchShop = dialogView.findViewById(R.id.btn_search_shop);
        final Button btnKnownStores = dialogView.findViewById(R.id.btn_known_stores);
        final RadioGroup rgPriority = dialogView.findViewById(R.id.rg_priority);

        // Mutable quantity holder
        final int[] quantity = {1};
        tvQuantity.setText("1");

        // Mutable shop holder
        final String[] selectedShop = {""};
        final double[] selectedShopLat = {0.0};
        final double[] selectedShopLon = {0.0};

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

        // Known stores button opens the curated store grid
        btnKnownStores.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new KnownStoresDialog(requireContext(),
                        new KnownStoresDialog.OnStoreSelectedListener() {
                    @Override
                    public void onStoreSelected(String storeName) {
                        selectedShop[0] = storeName;
                        tvShopName.setText(storeName);
                        tvShopName.setTextColor(
                                ContextCompat.getColor(requireContext(), R.color.text_primary));
                    }
                }).show();
            }
        });

        // Shop search button opens the map picker dialog
        btnSearchShop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new ShopPickerDialog(requireContext(), new ShopPickerDialog.OnShopSelectedListener() {
                    @Override
                    public void onShopSelected(String shopName, double latitude, double longitude) {
                        selectedShop[0]    = shopName;
                        selectedShopLat[0] = latitude;
                        selectedShopLon[0] = longitude;
                        tvShopName.setText(shopName);
                        tvShopName.setTextColor(
                                ContextCompat.getColor(requireContext(), R.color.text_primary));
                    }
                }).show();
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
                        final String shop = selectedShop[0];
                        final int priority = getSelectedPriority(rgPriority);

                        if (apiClient != null) {
                            apiClient.addItem(name, category, qty, shop, priority,
                                    new ShoppingApiClient.Callback<ShoppingItem>() {
                                @Override
                                public void onSuccess(ShoppingItem item) {
                                    // Save category locally for autocomplete suggestions only
                                    db.addCategory(category);
                                    saveStoreLocationIfPresent(shop,
                                            selectedShopLat[0], selectedShopLon[0]);
                                    refreshList();
                                }
                                @Override
                                public void onError(String message) {
                                    showSyncError();
                                }
                            });
                        } else {
                            db.addCategory(category);
                            db.addItem(name, category, qty, shop, priority);
                            saveStoreLocationIfPresent(shop,
                                    selectedShopLat[0], selectedShopLon[0]);
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

    /** Maps the checked RadioButton in the priority group to a ShoppingItem priority constant. */
    private int getSelectedPriority(RadioGroup rg) {
        int checkedId = rg.getCheckedRadioButtonId();
        if (checkedId == R.id.rb_priority_high) return ShoppingItem.PRIORITY_HIGH;
        if (checkedId == R.id.rb_priority_low)  return ShoppingItem.PRIORITY_LOW;
        return ShoppingItem.PRIORITY_NORMAL;
    }

    /**
     * Persists GPS coordinates for a store when the user picked it from the map.
     * No-op if the shop name is empty or coordinates are both zero.
     * Refreshes the geofence registrations after saving.
     */
    private void saveStoreLocationIfPresent(String shop, double lat, double lon) {
        if (shop == null || shop.isEmpty()) return;
        if (lat == 0.0 && lon == 0.0) return;
        db.upsertStore(shop, lat, lon, 200);
        // Refresh geofences so the new store is immediately monitored.
        StoreGeofenceHelper.unregisterAll(requireContext(), cachedStoreLocations);
        cachedStoreLocations = db.getStoresWithLocation();
        StoreGeofenceHelper.registerAll(requireContext(), cachedStoreLocations);
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
        syncHandler.removeCallbacks(syncRunnable);
        if (db != null) db.close();
    }

    // ── QR code helpers ───────────────────────────────────────────────────────

    /** Stores a pending add-from-QR item so it survives the Activity lifecycle. */
    public static void storePendingQrItem(Context context, String name, String category) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_PENDING_QR_NAME, name)
                .putString(PREF_PENDING_QR_CATEGORY, category != null ? category : "")
                .apply();
    }

    /** Called from onResume to process any pending deep-link/scan item. */
    private void checkPendingQrItem() {
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String name = prefs.getString(PREF_PENDING_QR_NAME, null);
        String category = prefs.getString(PREF_PENDING_QR_CATEGORY, null);
        if (name != null && !name.isEmpty()) {
            prefs.edit()
                    .remove(PREF_PENDING_QR_NAME)
                    .remove(PREF_PENDING_QR_CATEGORY)
                    .apply();
            showQrConfirmDialog(name,
                    category != null && !category.isEmpty()
                            ? category : getString(R.string.category_default));
        }
    }

    /** Launches the ZXing in-app QR/barcode scanner. */
    private void launchQrScanner() {
        IntentIntegrator integrator = IntentIntegrator.forSupportFragment(this);
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.setPrompt(getString(R.string.scan_qr));
        integrator.initiateScan();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                handleScanResult(result.getContents());
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** Handles raw text returned by the scanner. */
    private void handleScanResult(String content) {
        try {
            Uri uri = Uri.parse(content);
            if ("kitchenboard".equals(uri.getScheme()) && "add".equals(uri.getHost())) {
                String name = uri.getQueryParameter("name");
                String category = uri.getQueryParameter("category");
                if (name != null && !name.isEmpty()) {
                    showQrConfirmDialog(name,
                            category != null && !category.isEmpty()
                                    ? category : getString(R.string.category_default));
                    return;
                }
            }
        } catch (Exception ignored) { /* fall through */ }
        Toast.makeText(requireContext(), R.string.qr_invalid, Toast.LENGTH_SHORT).show();
    }

    /** Shows a pre-filled add-item dialog when an item is added via QR scan / deep link. */
    private void showQrConfirmDialog(final String name, final String category) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_item, null);

        final AutoCompleteTextView etName = dialogView.findViewById(R.id.et_item_name);
        final AutoCompleteTextView etCategory = dialogView.findViewById(R.id.et_category);
        final TextView tvQuantity = dialogView.findViewById(R.id.tv_quantity);
        final Button btnMinus = dialogView.findViewById(R.id.btn_qty_minus);
        final Button btnPlus = dialogView.findViewById(R.id.btn_qty_plus);
        final TextView tvShopName = dialogView.findViewById(R.id.tv_shop_name);
        final Button btnSearchShop = dialogView.findViewById(R.id.btn_search_shop);
        final Button btnKnownStores = dialogView.findViewById(R.id.btn_known_stores);
        final RadioGroup rgPriority = dialogView.findViewById(R.id.rg_priority);

        etName.setText(name);
        etCategory.setText(category);

        final int[] quantity = {1};
        tvQuantity.setText("1");
        final String[] selectedShop = {""};
        final double[] selectedShopLat = {0.0};
        final double[] selectedShopLon = {0.0};

        btnMinus.setOnClickListener(v -> {
            if (quantity[0] > 1) {
                quantity[0]--;
                tvQuantity.setText(String.valueOf(quantity[0]));
            }
        });
        btnPlus.setOnClickListener(v -> {
            quantity[0]++;
            tvQuantity.setText(String.valueOf(quantity[0]));
        });

        btnKnownStores.setOnClickListener(v ->
                new KnownStoresDialog(requireContext(), storeName -> {
                    selectedShop[0] = storeName;
                    tvShopName.setText(storeName);
                    tvShopName.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.text_primary));
                }).show());

        btnSearchShop.setOnClickListener(v -> new ShopPickerDialog(requireContext(),
                (shopName, latitude, longitude) -> {
                    selectedShop[0]    = shopName;
                    selectedShopLat[0] = latitude;
                    selectedShopLon[0] = longitude;
                    tvShopName.setText(shopName);
                    tvShopName.setTextColor(
                            ContextCompat.getColor(requireContext(), R.color.text_primary));
                }).show());

        List<String> categories = db.getCategories();
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, categories);
        etCategory.setAdapter(catAdapter);
        etCategory.setThreshold(1);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.qr_add_item)
                .setView(dialogView)
                .setPositiveButton(R.string.add, (dialog, which) -> {
                    final String itemName = etName.getText().toString().trim();
                    String rawCat = etCategory.getText().toString().trim();
                    final String itemCategory = rawCat.isEmpty()
                            ? getString(R.string.category_default) : rawCat;
                    if (itemName.isEmpty()) return;
                    final int qty = quantity[0];
                    final String shop = selectedShop[0];
                    final int priority = getSelectedPriority(rgPriority);

                    if (apiClient != null) {
                        apiClient.addItem(itemName, itemCategory, qty, shop, priority,
                                new ShoppingApiClient.Callback<ShoppingItem>() {
                            @Override
                            public void onSuccess(ShoppingItem item) {
                                db.addCategory(itemCategory);
                                saveStoreLocationIfPresent(shop,
                                        selectedShopLat[0], selectedShopLon[0]);
                                refreshList();
                            }
                            @Override
                            public void onError(String message) { showSyncError(); }
                        });
                    } else {
                        db.addCategory(itemCategory);
                        db.addItem(itemName, itemCategory, qty, shop, priority);
                        saveStoreLocationIfPresent(shop,
                                selectedShopLat[0], selectedShopLon[0]);
                        refreshList();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Generates and displays a QR code for a shopping item. */
    private void showQrCodeDialog(ShoppingItem item) {
        Uri uri = new Uri.Builder()
                .scheme("kitchenboard")
                .authority("add")
                .appendQueryParameter("name", item.getName())
                .appendQueryParameter("category", item.getCategory())
                .build();

        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.encodeBitmap(
                    uri.toString(), BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX);

            View dialogView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.dialog_qr_code, null);
            ImageView ivQr = dialogView.findViewById(R.id.iv_qr_code);
            ivQr.setImageBitmap(bitmap);

            new AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.qr_code_for, item.getName()))
                    .setView(dialogView)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.qr_generation_error, Toast.LENGTH_SHORT).show();
        }
    }

    /** Shows a dialog to select items and options for QR code printing. */
    private void showPrintQrDialog() {
        // Snapshot of the current item list (headers are filtered out in the adapter's rows)
        final List<ShoppingItem> allItems = adapter.getItems();
        if (allItems.isEmpty()) {
            Toast.makeText(requireContext(), R.string.shopping_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_print_qr, null);

        // Paper size spinner
        final Spinner spinnerSize = dialogView.findViewById(R.id.spinner_paper_size);
        final QrCodePrintHelper.PaperSize defaultSize = QrCodePrintHelper.PaperSize.forLocale();
        String[] sizeLabels = {"A4", "Letter"};
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, sizeLabels);
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSize.setAdapter(sizeAdapter);
        spinnerSize.setSelection(defaultSize == QrCodePrintHelper.PaperSize.A4 ? 0 : 1);

        // Labels checkbox
        final CheckBox cbLabels = dialogView.findViewById(R.id.cb_show_labels);

        // Item selection recycler view
        final RecyclerView rv = dialogView.findViewById(R.id.rv_print_items);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        final PrintItemSelectionAdapter selAdapter =
                new PrintItemSelectionAdapter(allItems);
        rv.setAdapter(selAdapter);

        // Select-all checkbox
        final CheckBox cbSelectAll = dialogView.findViewById(R.id.cb_select_all);
        cbSelectAll.setOnCheckedChangeListener((buttonView, isChecked) ->
                selAdapter.setAllChecked(isChecked));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.print_qr_title)
                .setView(dialogView)
                .setPositiveButton(R.string.print, (dialog, which) -> {
                    List<ShoppingItem> selected = selAdapter.getSelectedItems();
                    if (selected.isEmpty()) {
                        Toast.makeText(requireContext(),
                                R.string.print_no_items, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean showLabels = cbLabels.isChecked();
                    QrCodePrintHelper.PaperSize paperSize =
                            spinnerSize.getSelectedItemPosition() == 0
                                    ? QrCodePrintHelper.PaperSize.A4
                                    : QrCodePrintHelper.PaperSize.LETTER;
                    new QrCodePrintHelper(requireContext()).print(
                            selected, showLabels, paperSize,
                            getString(R.string.print_job_name));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ── Share helper ──────────────────────────────────────────────────────────

    /** Formats the current shopping list as plain text and opens the system share sheet. */
    private void shareShoppingList() {
        List<ShoppingItem> items = adapter.getItems();
        if (items.isEmpty()) {
            Toast.makeText(requireContext(), R.string.shopping_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.shopping_title)).append(":\n\n");
        String currentCategory = null;
        for (ShoppingItem item : items) {
            if (!item.getCategory().equals(currentCategory)) {
                currentCategory = item.getCategory();
                sb.append(currentCategory).append(":\n");
            }
            sb.append("- ").append(item.getName());
            if (item.getQuantity() > 1) {
                sb.append(" (").append(item.getQuantity()).append("x)");
            }
            sb.append("\n");
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, sb.toString().trim());
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)));
    }

    // ── Rotation-toggle helper ────────────────────────────────────────────────

    private static final String PREF_PAGE_IN_ROTATION = "page_%d_in_rotation";

    private void setupRotationToggle(View view, int pageIndex) {
        ImageButton btn = view.findViewById(R.id.btn_rotation_toggle);
        if (btn == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        updateRotationToggleAlpha(btn, prefs.getBoolean(
                String.format(Locale.US, PREF_PAGE_IN_ROTATION, pageIndex), true));
        btn.setOnClickListener(v -> {
            boolean current = prefs.getBoolean(
                    String.format(Locale.US, PREF_PAGE_IN_ROTATION, pageIndex), true);
            boolean newValue = !current;
            prefs.edit()
                    .putBoolean(String.format(Locale.US, PREF_PAGE_IN_ROTATION, pageIndex), newValue)
                    .apply();
            updateRotationToggleAlpha(btn, newValue);
        });
    }

    private static void updateRotationToggleAlpha(ImageButton btn, boolean inRotation) {
        btn.setAlpha(inRotation ? 1.0f : 0.25f);
    }
}

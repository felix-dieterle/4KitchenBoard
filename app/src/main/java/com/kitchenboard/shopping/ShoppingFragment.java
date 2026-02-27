package com.kitchenboard.shopping;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kitchenboard.R;

import java.util.Arrays;
import java.util.List;

public class ShoppingFragment extends Fragment {

    static final String[] CATEGORIES = {
            "Fruits & Vegetables",
            "Dairy & Eggs",
            "Meat & Fish",
            "Bakery",
            "Beverages",
            "Snacks",
            "Household",
            "Other"
    };

    private ShoppingDatabaseHelper db;
    private ShoppingAdapter adapter;
    private TextView tvEmpty;

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

        adapter.setOnItemCheckedListener(new ShoppingAdapter.OnItemCheckedListener() {
            @Override
            public void onItemChecked(ShoppingItem item) {
                db.checkItem(item.getId());
                refreshList();
            }
        });

        adapter.setOnItemLongClickListener(new ShoppingAdapter.OnItemLongClickListener() {
            @Override
            public void onItemLongClick(final ShoppingItem item) {
                showDeleteConfirmation(item);
            }
        });

        refreshList();
    }

    private void refreshList() {
        List<ShoppingItem> items = db.getActiveItems();
        adapter.setItems(items);
        tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showAddItemDialog() {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_item, null);

        final AutoCompleteTextView etName =
                dialogView.findViewById(R.id.et_item_name);
        final Spinner spinnerCategory =
                dialogView.findViewById(R.id.spinner_category);

        // Populate name suggestions from history
        List<String> history = db.getAllItemNames();
        ArrayAdapter<String> suggestAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, history);
        etName.setAdapter(suggestAdapter);
        etName.setThreshold(1);

        // Populate category spinner
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, Arrays.asList(CATEGORIES));
        catAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(catAdapter);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.add_item)
                .setView(dialogView)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = etName.getText().toString().trim();
                        String category = (String) spinnerCategory.getSelectedItem();
                        if (!name.isEmpty() && category != null) {
                            db.addItem(name, category);
                            refreshList();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showDeleteConfirmation(final ShoppingItem item) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.delete_item)
                .setMessage(getString(R.string.delete_item_confirm, item.getName()))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        db.deleteItem(item.getId());
                        refreshList();
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

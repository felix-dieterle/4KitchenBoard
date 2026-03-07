package com.kitchenboard.immobilien;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.kitchenboard.R;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * RecyclerView adapter that shows the list of {@link ImmobilienAlert}s together with
 * their current "new / total" listing counts.
 */
public class ImmobilienAdapter extends RecyclerView.Adapter<ImmobilienAdapter.ViewHolder> {

    public interface AlertListener {
        void onEditClick(ImmobilienAlert alert);
        void onAlertClick(ImmobilienAlert alert);
    }

    private final List<ImmobilienAlert>   alerts;
    private final List<int[]>             counts; // [0]=new, [1]=total per alert
    private final AlertListener           listener;

    public ImmobilienAdapter(List<ImmobilienAlert> alerts, List<int[]> counts,
                             AlertListener listener) {
        this.alerts   = alerts;
        this.counts   = counts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_immobilien_alert, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        ImmobilienAlert alert = alerts.get(position);
        int[] cnt   = counts.get(position);
        int newCnt  = cnt[0];
        int totalCnt = cnt[1];

        h.tvName.setText(alert.name);
        h.tvUrl.setText(alert.searchUrl);

        // Last-check label
        if (alert.lastCheckMs == 0) {
            h.tvLastCheck.setText(R.string.immobilien_never_checked);
        } else {
            String ts = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT, DateFormat.SHORT).format(new Date(alert.lastCheckMs));
            h.tvLastCheck.setText(h.itemView.getContext()
                    .getString(R.string.immobilien_last_check, ts));
        }

        // New-listings badge
        if (newCnt > 0) {
            h.tvNewBadge.setVisibility(View.VISIBLE);
            h.tvNewBadge.setText(
                    h.itemView.getContext().getString(R.string.immobilien_new_count, newCnt));
        } else {
            h.tvNewBadge.setVisibility(View.GONE);
        }

        // Total count
        h.tvTotal.setText(
                h.itemView.getContext().getString(R.string.immobilien_total_count, totalCnt));

        // Active indicator
        h.tvActive.setText(alert.active ? "●" : "○");
        h.tvActive.setTextColor(ContextCompat.getColor(h.itemView.getContext(),
                alert.active ? R.color.module_immobilien : R.color.text_secondary));

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onAlertClick(alert);
        });
        h.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEditClick(alert);
        });
    }

    @Override
    public int getItemCount() {
        return alerts.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvUrl;
        final TextView tvLastCheck;
        final TextView tvNewBadge;
        final TextView tvTotal;
        final TextView tvActive;
        final View     btnEdit;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName      = itemView.findViewById(R.id.tv_alert_name);
            tvUrl       = itemView.findViewById(R.id.tv_alert_url);
            tvLastCheck = itemView.findViewById(R.id.tv_alert_last_check);
            tvNewBadge  = itemView.findViewById(R.id.tv_alert_new_badge);
            tvTotal     = itemView.findViewById(R.id.tv_alert_total);
            tvActive    = itemView.findViewById(R.id.tv_alert_active);
            btnEdit     = itemView.findViewById(R.id.btn_alert_edit);
        }
    }
}

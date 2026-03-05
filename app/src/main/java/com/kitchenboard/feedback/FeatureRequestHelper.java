package com.kitchenboard.feedback;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.speech.RecognizerIntent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.kitchenboard.BuildConfig;
import com.kitchenboard.R;

import java.util.ArrayList;

/**
 * Helper that adds a "feature request" flow to any Fragment.
 * Shows a dialog with a text field (populated via voice input) and submits
 * the entered text as a GitHub issue in the felix-dieterle/4KitchenBoard repo.
 *
 * Usage:
 * <pre>
 *   // In the Fragment's field section (before onStart):
 *   private final FeatureRequestHelper featureRequestHelper =
 *       new FeatureRequestHelper(this, "Modulname");
 *
 *   // In onViewCreated:
 *   view.findViewById(R.id.btn_feature_request).setOnClickListener(
 *       v -> featureRequestHelper.show());
 * </pre>
 */
public class FeatureRequestHelper {

    private final Fragment fragment;
    private final String moduleName;
    private final ActivityResultLauncher<Intent> voiceLauncher;

    /** Holds a reference to the active dialog's input field while a voice pick is in progress. */
    private EditText pendingInputField;

    public FeatureRequestHelper(Fragment fragment, String moduleName) {
        this.fragment = fragment;
        this.moduleName = moduleName;
        this.voiceLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null
                                && pendingInputField != null) {
                            ArrayList<String> matches = result.getData()
                                    .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                            if (matches != null && !matches.isEmpty()) {
                                pendingInputField.setText(matches.get(0));
                            }
                        }
                    }
                });
    }

    /** Shows the feature-request dialog. */
    public void show() {
        Context context = fragment.requireContext();
        View dialogView = LayoutInflater.from(context)
                .inflate(R.layout.dialog_feature_request, null);

        EditText etRequest = dialogView.findViewById(R.id.et_feature_request);
        ImageButton btnVoice = dialogView.findViewById(R.id.btn_voice_feature_request);

        pendingInputField = etRequest;

        btnVoice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                try {
                    voiceLauncher.launch(intent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(context,
                            R.string.cooking_speech_unavailable,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        new AlertDialog.Builder(context)
                .setTitle(fragment.getString(R.string.feature_request_title, moduleName))
                .setView(dialogView)
                .setPositiveButton(R.string.feature_request_submit,
                        (dialog, which) -> {
                            String text = etRequest.getText().toString().trim();
                            if (!text.isEmpty()) {
                                submitIssue(context, text);
                            }
                        })
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener(d -> pendingInputField = null)
                .show();
    }

    private void submitIssue(Context context, String text) {
        String token = BuildConfig.GITHUB_ISSUE_TOKEN;
        if (token.isEmpty()) {
            Toast.makeText(context, R.string.feature_request_no_token,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String issueTitle = "[Feature Request] " + moduleName + ": " + text;
        GitHubIssueClient.createIssue(token, issueTitle, null,
                new GitHubIssueClient.Callback() {
                    @Override
                    public void onSuccess() {
                        Toast.makeText(context, R.string.feature_request_submitted,
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(context, R.string.feature_request_error,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }
}

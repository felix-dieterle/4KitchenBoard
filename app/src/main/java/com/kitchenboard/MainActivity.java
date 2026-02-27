package com.kitchenboard;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.kitchenboard.shopping.ShoppingFragment;
import com.kitchenboard.update.UpdateChecker;
import com.kitchenboard.weather.WeatherFragment;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.replace(R.id.container_weather, new WeatherFragment());
            ft.replace(R.id.container_shopping, new ShoppingFragment());
            ft.commit();

            checkForUpdates();
        }
    }

    private void checkForUpdates() {
        UpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE, new UpdateChecker.UpdateCallback() {
            @Override
            public void onUpdateAvailable(final String tagName, final String downloadUrl) {
                if (isFinishing()) return;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.update_available_title)
                        .setMessage(getString(R.string.update_available_message, tagName))
                        .setPositiveButton(R.string.update_download, (dialog, which) -> {
                            Intent intent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(downloadUrl));
                            startActivity(intent);
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }

            @Override
            public void onNoUpdate() {
                // nothing to do
            }

            @Override
            public void onError(String message) {
                // silently ignore update check errors
            }
        });
    }
}

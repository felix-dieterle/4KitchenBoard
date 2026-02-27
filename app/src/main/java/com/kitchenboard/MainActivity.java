package com.kitchenboard;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.kitchenboard.shopping.ShoppingFragment;
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
        }
    }
}

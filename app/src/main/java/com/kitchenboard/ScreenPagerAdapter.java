package com.kitchenboard;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.kitchenboard.calendar.CalendarFragment;

public class ScreenPagerAdapter extends FragmentStateAdapter {

    public ScreenPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case 1:  return new CalendarFragment();
            default: return new CombinedFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}

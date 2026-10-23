package local.syc.rokidadb;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

final class ScreenStyle {
    static final int TEXT = 0xff8fc9a7;
    static final int MUTED = 0xff639b7c;

    static void apply(Activity activity) {
        activity.getWindow().setStatusBarColor(Color.BLACK);
        activity.getWindow().setNavigationBarColor(Color.BLACK);
        activity.getWindow().setBackgroundDrawableResource(android.R.color.black);
        style(activity.findViewById(android.R.id.content));
    }

    static void style(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xff365343, TEXT}));
            text.setHighlightColor(0xff163524);
        }
        if (view instanceof Button) {
            Button button = (Button) view;
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.BLACK);
            background.setCornerRadius(6);
            background.setStroke(1, 0xff264e38);
            button.setBackground(background);
            button.setAllCaps(false);
            button.setMinHeight(54);
            button.setMinimumHeight(54);
            button.setPadding(10, 8, 10, 8);
            button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            if (button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) button.getLayoutParams();
                params.topMargin = 4; params.bottomMargin = 4;
            }
        }
        if (view instanceof CheckBox) ((CheckBox) view).setButtonTintList(ColorStateList.valueOf(MUTED));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) style(group.getChildAt(index));
        }
    }
}

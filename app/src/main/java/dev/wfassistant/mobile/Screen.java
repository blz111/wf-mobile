package dev.wfassistant.mobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Screen {
    static void heading(LinearLayout parent, String text) {
        TextView title = text(parent, text);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextSize(27);
    }

    static TextView text(LinearLayout parent, String text) {
        TextView label = new TextView(parent.getContext());
        label.setText(text);
        label.setTextSize(15);
        label.setTextColor(Color.rgb(21, 35, 39));
        label.setPadding(0, dp(parent.getContext(), 6), 0, dp(parent.getContext(), 6));
        parent.addView(label);
        return label;
    }

    static Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(parent.getContext());
        button.setText(text);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

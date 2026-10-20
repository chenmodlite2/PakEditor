package com.chenmodlite.pakeditor;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Shell — replaced by the full cooking implementation. */
public class CookActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new FrameLayout(this));
    }
}

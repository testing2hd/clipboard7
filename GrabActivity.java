package com.linkrouter.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/**
 * The "Download video" launcher entry for your sidebar. It draws nothing and closes at once; the folder
 * picker and the Share / Copy link steps are done by the accessibility service on top of the video app.
 */
public class GrabActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!LinkAccessibilityService.requestStart()) {
            Toast.makeText(this, "Turn on Link Router in Accessibility first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        finish();
    }
}

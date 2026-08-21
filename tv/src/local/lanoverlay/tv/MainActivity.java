package local.lanoverlay.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

public final class MainActivity extends Activity {
    private TextView permissionStatus;
    private TextView receiverStatus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startReceiver();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(56), dp(32), dp(56), dp(32));
        root.setBackgroundColor(Color.rgb(18, 20, 24));

        TextView title = text("mirror-to-tv", 28, Color.WHITE);
        root.addView(title, matchWrap());

        TextView description = text(
                "Receives media over your local network and displays it above other Android TV apps. Control is provided by the desktop panel.",
                17,
                Color.rgb(205, 211, 220));
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.setMargins(0, dp(14), 0, dp(20));
        root.addView(description, descriptionParams);

        permissionStatus = text("", 17, Color.WHITE);
        root.addView(permissionStatus, matchWrap());

        receiverStatus = text("", 17, Color.rgb(150, 220, 170));
        LinearLayout.LayoutParams receiverParams = matchWrap();
        receiverParams.setMargins(0, dp(8), 0, dp(18));
        root.addView(receiverStatus, receiverParams);

        Button permission = new Button(this);
        permission.setText("Allow display over other apps");
        permission.setTextSize(16);
        permission.setAllCaps(false);
        permission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestOverlayPermission();
            }
        });
        root.addView(permission, new LinearLayout.LayoutParams(dp(430), dp(60)));

        Button hide = new Button(this);
        hide.setText("Remove all overlays now");
        hide.setTextSize(16);
        hide.setAllCaps(false);
        hide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent command = new Intent(MainActivity.this, OverlayService.class);
                command.setAction(OverlayService.ACTION_HIDE);
                startService(command);
            }
        });
        LinearLayout.LayoutParams hideParams = new LinearLayout.LayoutParams(dp(430), dp(60));
        hideParams.setMargins(0, dp(12), 0, 0);
        root.addView(hide, hideParams);

        setContentView(root);
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        startReceiver();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            updateStatus();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void startReceiver() {
        Intent service = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    private void updateStatus() {
        if (permissionStatus == null || receiverStatus == null) {
            return;
        }
        boolean allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        permissionStatus.setText(allowed
                ? "✓ Display over other apps is allowed"
                : "⚠ Display over other apps must be allowed once");
        permissionStatus.setTextColor(allowed
                ? Color.rgb(150, 220, 170)
                : Color.rgb(255, 190, 110));
        receiverStatus.setText("Receiver: " + localAddress() + ":" + Config.PORT + " — running");
    }

    private String localAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "TV IP";
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setLineSpacing(0, 1.12f);
        return text;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

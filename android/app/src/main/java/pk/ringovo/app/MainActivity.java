package pk.ringovo.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.BridgeActivity;

/**
 * Ringovo Android wrapper.
 *
 * Loads app.ringovo.pk in the WebView for the UI, and registers the native
 * {@link LinphonePlugin} SIP voice engine. Calls run natively through Linphone
 * (earpiece, hardware echo-cancel, proper mic gain) — NOT through WebView
 * WebRTC, which can only play to the loudspeaker.
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Register the native SIP plugin BEFORE the bridge starts.
        registerPlugin(LinphonePlugin.class);
        super.onCreate(savedInstanceState);

        // Linphone needs the microphone; request it up front.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.MODIFY_AUDIO_SETTINGS
                    },
                    100);
        }
    }
}

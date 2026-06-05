package pk.ringovo.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.BridgeActivity;

import java.util.List;

/**
 * Ringovo Android wrapper.
 *
 * Loads app.ringovo.pk in the Capacitor WebView and forces the OS into
 * voice-call audio mode so WebRTC call audio routes to the EARPIECE (like a
 * normal phone call) and the mic runs through the voice-call gain pipeline.
 *
 * - Requests RECORD_AUDIO at runtime so the WebView's getUserMedia works.
 * - On Android 12+ uses AudioManager.setCommunicationDevice(BUILTIN_EARPIECE).
 * - On older Android uses MODE_IN_COMMUNICATION + setSpeakerphoneOn(false).
 *
 * A tiny JS bridge (window.RingovoAudio) lets the web app flip the loudspeaker
 * on/off from its in-call Speaker button.
 */
public class MainActivity extends BridgeActivity {

    private AudioManager audioManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        String[] perms = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS
        };
        boolean need = false;
        for (String p : perms) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                need = true;
            }
        }
        if (need) {
            ActivityCompat.requestPermissions(this, perms, 100);
        }

        // Expose a tiny audio bridge to the web app for the Speaker toggle.
        bridge.getWebView().addJavascriptInterface(new AudioBridge(), "RingovoAudio");
    }

    @Override
    public void onResume() {
        super.onResume();
        setSpeakerphone(false); // earpiece by default
    }

    @Override
    public void onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            }
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    /** Route call audio to earpiece (speaker=false) or loudspeaker (speaker=true). */
    private void setSpeakerphone(boolean speaker) {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int wanted = speaker
                        ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
                List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
                AudioDeviceInfo target = null;
                for (AudioDeviceInfo d : devices) {
                    if (d.getType() == wanted) {
                        target = d;
                        break;
                    }
                }
                if (target != null) {
                    audioManager.setCommunicationDevice(target);
                }
            } else {
                audioManager.setSpeakerphoneOn(speaker);
            }
        } catch (Exception ignored) {
        }
    }

    /** Called from JS: window.RingovoAudio.setSpeakerphone(true/false). */
    public class AudioBridge {
        @android.webkit.JavascriptInterface
        public void setSpeakerphone(final boolean on) {
            runOnUiThread(() -> MainActivity.this.setSpeakerphone(on));
        }

        @android.webkit.JavascriptInterface
        public boolean isNativeWrapper() {
            return true;
        }
    }
}

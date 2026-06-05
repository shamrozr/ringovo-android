package pk.ringovo.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.linphone.core.Account;
import org.linphone.core.AccountParams;
import org.linphone.core.Address;
import org.linphone.core.AudioDevice;
import org.linphone.core.AuthInfo;
import org.linphone.core.Call;
import org.linphone.core.Core;
import org.linphone.core.CoreListenerStub;
import org.linphone.core.Factory;
import org.linphone.core.MediaEncryption;
import org.linphone.core.RegistrationState;
import org.linphone.core.TransportType;

import java.util.ArrayList;
import java.util.List;

/**
 * Native SIP voice engine for Ringovo (Linphone Android SDK).
 *
 * Calls run on the OS voice-call pipeline: earpiece by default, hardware echo
 * cancellation, proper mic gain, Bluetooth/wired-headset routing, and a
 * proximity wake-lock that blanks the screen when held to the ear.
 */
@CapacitorPlugin(name = "Linphone")
public class LinphonePlugin extends Plugin {

    private Core core;
    private CoreListenerStub listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String currentRoute = "earpiece"; // earpiece | speaker | bluetooth | headset
    private boolean userChoseRoute = false;
    private PowerManager.WakeLock proximityLock;

    @Override
    public void load() {
        main.post(() -> {
            try {
                Factory factory = Factory.instance();
                factory.setDebugMode(true, "RingovoLinphone");
                core = factory.createCore(null, null, getContext());
                // Match the Asterisk native endpoint (media_encryption=no → plain RTP).
                core.setMediaEncryption(MediaEncryption.None);

                PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ringovo:proximity");
                    proximityLock.setReferenceCounted(false);
                }

                listener = new CoreListenerStub() {
                    @Override
                    public void onAccountRegistrationStateChanged(Core core, Account account, RegistrationState state, String message) {
                        JSObject d = new JSObject();
                        d.put("state", state != null ? state.name() : "Unknown");
                        d.put("message", message);
                        notifyListeners("registrationState", d);
                    }

                    @Override
                    public void onCallStateChanged(Core core, Call call, Call.State state, String message) {
                        JSObject d = new JSObject();
                        d.put("state", state != null ? state.name() : "Unknown");
                        d.put("message", message);
                        Address remote = call != null ? call.getRemoteAddress() : null;
                        d.put("remote", remote != null ? remote.asStringUriOnly() : "");
                        notifyListeners("callState", d);

                        if (state == Call.State.StreamsRunning || state == Call.State.Connected) {
                            if (!userChoseRoute) autoRoute();
                            applyRoute();
                            notifyAudioDevices();
                        } else if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                            userChoseRoute = false;
                            currentRoute = "earpiece";
                            releaseProximity();
                        }
                    }

                    @Override
                    public void onAudioDevicesListUpdated(Core core) {
                        // A Bluetooth/wired headset was just (dis)connected mid-call.
                        if (!userChoseRoute) autoRoute();
                        applyRoute();
                        notifyAudioDevices();
                    }
                };
                core.addListener(listener);
                core.start();
            } catch (Exception e) {
                JSObject d = new JSObject();
                d.put("state", "Error");
                d.put("message", "init failed: " + e.getMessage());
                notifyListeners("registrationState", d);
            }
        });
    }

    @PluginMethod
    public void register(final PluginCall pcall) {
        final String username = pcall.getString("username");
        final String password = pcall.getString("password");
        final String domain = pcall.getString("domain");
        final String port = pcall.getString("port", "5061");
        if (username == null || password == null || domain == null) {
            pcall.reject("username, password and domain are required");
            return;
        }
        main.post(() -> {
            try {
                Factory f = Factory.instance();
                core.clearAccounts();
                core.clearAllAuthInfo();
                AuthInfo ai = f.createAuthInfo(username, null, password, null, null, domain);
                core.addAuthInfo(ai);
                AccountParams params = core.createAccountParams();
                params.setIdentityAddress(f.createAddress("sip:" + username + "@" + domain));
                Address server = f.createAddress("sip:" + domain + ":" + port);
                server.setTransport(TransportType.Tls);
                params.setServerAddress(server);
                params.setRegisterEnabled(true);
                Account account = core.createAccount(params);
                core.addAccount(account);
                core.setDefaultAccount(account);
                pcall.resolve();
            } catch (Exception e) {
                pcall.reject("register failed: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void call(final PluginCall pcall) {
        final String number = pcall.getString("number");
        final String domain = pcall.getString("domain");
        if (number == null || domain == null) { pcall.reject("number and domain are required"); return; }
        main.post(() -> {
            try {
                userChoseRoute = false;
                core.inviteAddress(Factory.instance().createAddress("sip:" + number + "@" + domain));
                pcall.resolve();
            } catch (Exception e) { pcall.reject("call failed: " + e.getMessage()); }
        });
    }

    @PluginMethod
    public void accept(final PluginCall pcall) {
        main.post(() -> {
            try { Call c = core.getCurrentCall(); if (c != null) c.accept(); pcall.resolve(); }
            catch (Exception e) { pcall.reject(e.getMessage()); }
        });
    }

    @PluginMethod
    public void hangup(final PluginCall pcall) {
        main.post(() -> {
            try { if (core.getCallsNb() > 0) core.terminateAllCalls(); releaseProximity(); pcall.resolve(); }
            catch (Exception e) { pcall.reject(e.getMessage()); }
        });
    }

    @PluginMethod
    public void setMicMuted(final PluginCall pcall) {
        final boolean muted = Boolean.TRUE.equals(pcall.getBoolean("muted", false));
        main.post(() -> {
            try { core.setMicEnabled(!muted); pcall.resolve(); }
            catch (Exception e) { pcall.reject(e.getMessage()); }
        });
    }

    /** Legacy: speaker on/off. Kept for compatibility. */
    @PluginMethod
    public void setSpeaker(final PluginCall pcall) {
        final boolean on = Boolean.TRUE.equals(pcall.getBoolean("on", false));
        setRoute(on ? "speaker" : "earpiece", pcall);
    }

    /** Pick audio output explicitly: earpiece | speaker | bluetooth | headset. */
    @PluginMethod
    public void setAudioRoute(final PluginCall pcall) {
        final String route = pcall.getString("route", "earpiece");
        setRoute(route, pcall);
    }

    @PluginMethod
    public void getAudioDevices(final PluginCall pcall) {
        main.post(() -> {
            JSObject res = new JSObject();
            res.put("devices", audioDevicesArray());
            res.put("current", currentRoute);
            pcall.resolve(res);
        });
    }

    @PluginMethod
    public void unregister(final PluginCall pcall) {
        main.post(() -> {
            try { core.clearAccounts(); pcall.resolve(); }
            catch (Exception e) { pcall.reject(e.getMessage()); }
        });
    }

    // ---- internals -------------------------------------------------------

    private void setRoute(final String route, final PluginCall pcall) {
        main.post(() -> {
            userChoseRoute = true;
            currentRoute = route;
            applyRoute();
            notifyAudioDevices();
            if (pcall != null) pcall.resolve();
        });
    }

    /** Choose the best default output: bluetooth > wired headset > earpiece. */
    private void autoRoute() {
        boolean bt = false, headset = false;
        for (AudioDevice d : core.getAudioDevices()) {
            if (!d.hasCapability(AudioDevice.Capabilities.CapabilityPlay)) continue;
            AudioDevice.Type t = d.getType();
            if (t == AudioDevice.Type.Bluetooth || t == AudioDevice.Type.BluetoothA2DP) bt = true;
            if (t == AudioDevice.Type.Headset || t == AudioDevice.Type.Headphones) headset = true;
        }
        currentRoute = bt ? "bluetooth" : (headset ? "headset" : "earpiece");
    }

    /** Apply currentRoute to the active call + manage the proximity lock. */
    private void applyRoute() {
        try {
            AudioDevice chosen = findDevice(currentRoute);
            if (chosen != null) {
                Call c = core.getCurrentCall();
                if (c != null) c.setOutputAudioDevice(chosen);
                else core.setOutputAudioDevice(chosen);
            }
            // Blank the screen only when the phone is at the ear (earpiece).
            if ("earpiece".equals(currentRoute) && core.getCallsNb() > 0) acquireProximity();
            else releaseProximity();
        } catch (Exception ignored) {}
    }

    private AudioDevice findDevice(String route) {
        AudioDevice.Type want;
        switch (route) {
            case "speaker": want = AudioDevice.Type.Speaker; break;
            case "bluetooth": want = AudioDevice.Type.Bluetooth; break;
            case "headset": want = AudioDevice.Type.Headset; break;
            default: want = AudioDevice.Type.Earpiece; break;
        }
        AudioDevice fallback = null;
        for (AudioDevice d : core.getAudioDevices()) {
            if (!d.hasCapability(AudioDevice.Capabilities.CapabilityPlay)) continue;
            AudioDevice.Type t = d.getType();
            if (t == want) return d;
            // bluetooth may be reported as BluetoothA2DP; headset as Headphones
            if (route.equals("bluetooth") && t == AudioDevice.Type.BluetoothA2DP) fallback = d;
            if (route.equals("headset") && t == AudioDevice.Type.Headphones) fallback = d;
        }
        return fallback;
    }

    private JSArray audioDevicesArray() {
        List<String> seen = new ArrayList<>();
        JSArray arr = new JSArray();
        try {
            for (AudioDevice d : core.getAudioDevices()) {
                if (!d.hasCapability(AudioDevice.Capabilities.CapabilityPlay)) continue;
                String r = routeForType(d.getType());
                if (r == null || seen.contains(r)) continue;
                seen.add(r);
                arr.put(r);
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private String routeForType(AudioDevice.Type t) {
        if (t == AudioDevice.Type.Earpiece) return "earpiece";
        if (t == AudioDevice.Type.Speaker) return "speaker";
        if (t == AudioDevice.Type.Bluetooth || t == AudioDevice.Type.BluetoothA2DP) return "bluetooth";
        if (t == AudioDevice.Type.Headset || t == AudioDevice.Type.Headphones) return "headset";
        return null;
    }

    private void notifyAudioDevices() {
        JSObject d = new JSObject();
        d.put("devices", audioDevicesArray());
        d.put("current", currentRoute);
        notifyListeners("audioRoute", d);
    }

    private void acquireProximity() {
        try { if (proximityLock != null && !proximityLock.isHeld()) proximityLock.acquire(60 * 60 * 1000L); } catch (Exception ignored) {}
    }

    private void releaseProximity() {
        try { if (proximityLock != null && proximityLock.isHeld()) proximityLock.release(); } catch (Exception ignored) {}
    }
}

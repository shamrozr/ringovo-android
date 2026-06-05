package pk.ringovo.app;

import android.os.Handler;
import android.os.Looper;

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

/**
 * Native SIP voice engine for Ringovo, wrapping the Linphone Android SDK.
 *
 * The web app (app.ringovo.pk) calls this plugin when running inside the native
 * app so calls run on the OS voice-call pipeline — earpiece by default,
 * hardware echo cancellation, proper mic gain — instead of WebView WebRTC.
 *
 * JS usage (Capacitor):
 *   const Linphone = registerPlugin('Linphone')
 *   await Linphone.register({ username:'1003n', password:'…', domain:'app.ringovo.pk', port:'5061' })
 *   await Linphone.call({ number:'03001234567', domain:'app.ringovo.pk' })
 *   Linphone.addListener('registrationState', e => …)
 *   Linphone.addListener('callState', e => …)
 */
@CapacitorPlugin(name = "Linphone")
public class LinphonePlugin extends Plugin {

    private Core core;
    private CoreListenerStub listener;
    private boolean speakerOn = false;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void load() {
        main.post(() -> {
            try {
                Factory factory = Factory.instance();
                factory.setDebugMode(true, "RingovoLinphone");
                core = factory.createCore(null, null, getContext());
                // SRTP to match the Asterisk native endpoint (media_encryption=sdes).
                core.setMediaEncryption(MediaEncryption.SRTP);
                core.setMediaEncryptionMandatory(false);

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
                        if (state == Call.State.StreamsRunning) {
                            routeAudio(speakerOn);
                        }
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
        if (number == null || domain == null) {
            pcall.reject("number and domain are required");
            return;
        }
        main.post(() -> {
            try {
                Address addr = Factory.instance().createAddress("sip:" + number + "@" + domain);
                core.inviteAddress(addr);
                pcall.resolve();
            } catch (Exception e) {
                pcall.reject("call failed: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void accept(final PluginCall pcall) {
        main.post(() -> {
            try {
                Call c = core.getCurrentCall();
                if (c != null) c.accept();
                pcall.resolve();
            } catch (Exception e) {
                pcall.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void hangup(final PluginCall pcall) {
        main.post(() -> {
            try {
                if (core.getCallsNb() > 0) core.terminateAllCalls();
                pcall.resolve();
            } catch (Exception e) {
                pcall.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void setMicMuted(final PluginCall pcall) {
        final boolean muted = Boolean.TRUE.equals(pcall.getBoolean("muted", false));
        main.post(() -> {
            try {
                core.setMicEnabled(!muted);
                pcall.resolve();
            } catch (Exception e) {
                pcall.reject(e.getMessage());
            }
        });
    }

    @PluginMethod
    public void setSpeaker(final PluginCall pcall) {
        speakerOn = Boolean.TRUE.equals(pcall.getBoolean("on", false));
        main.post(() -> {
            routeAudio(speakerOn);
            pcall.resolve();
        });
    }

    @PluginMethod
    public void unregister(final PluginCall pcall) {
        main.post(() -> {
            try {
                core.clearAccounts();
                pcall.resolve();
            } catch (Exception e) {
                pcall.reject(e.getMessage());
            }
        });
    }

    /** Route in-call audio to earpiece (default) or loudspeaker. */
    private void routeAudio(boolean speaker) {
        try {
            AudioDevice.Type want = speaker ? AudioDevice.Type.Speaker : AudioDevice.Type.Earpiece;
            for (AudioDevice d : core.getAudioDevices()) {
                if (d.getType() == want && d.hasCapability(AudioDevice.Capabilities.CapabilityPlay)) {
                    Call c = core.getCurrentCall();
                    if (c != null) {
                        c.setOutputAudioDevice(d);
                    } else {
                        core.setOutputAudioDevice(d);
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }
}

package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns down what is playing while a filtered offer is being declined. Dasher rings for an offer with its own
 * sound, not its notifications, until the offer closes; this cuts that ring from the moment the offer is judged
 * rather than when Dasher closes it.
 *
 * Only the media and alarm streams are touched: the ringer, notification and system streams share the phone's
 * ringer mode, and muting them would switch the whole phone to vibrate. A stream is turned down only while something
 * other than navigation or a call plays on it, and nothing happens while a call rings or is in progress; a call
 * starting mid-decline puts everything back at once. Each stream is saved before it changes and put back when the
 * decline ends, after {@link #MAX_MS} at most, or at the next start if the app died in between. The alarm stream
 * cannot be muted, so it is turned to its lowest level instead. Android offers no way to stop another app's
 * vibration.
 */
final class OfferSilencer {
    static final long MAX_MS = 20_000;
    private static final int[] STREAMS = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM};
    private static final String PREFS = "silencer";

    private final Context context;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::stop;
    private final AudioManager.AudioPlaybackCallback newPlayers = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> playing) {
            silence(playing);
        }
    };
    private boolean silencing;
    /** When a passing offer's alert last asked to be heard; elapsed-realtime clock, 0 for never. */
    private static volatile long passingAlertAt;

    OfferSilencer(Context context) {
        this.context = context.getApplicationContext();
        this.audio = this.context.getSystemService(AudioManager.class);
    }

    boolean isSilencing() {
        return silencing;
    }

    /** Silences every stream playing now, and any that starts before {@link #stop}. */
    void start() {
        if (audio == null) return;
        if (!silencing) {
            silencing = true;
            audio.registerAudioPlaybackCallback(newPlayers, handler);
            handler.postDelayed(timeout, MAX_MS);
        }
        silence(audio.getActivePlaybackConfigurations());
    }

    /** Puts back everything {@link #start} changed. Safe to call at any time. */
    void stop() {
        if (silencing) {
            silencing = false;
            handler.removeCallbacks(timeout);
            if (audio != null) audio.unregisterAudioPlaybackCallback(newPlayers);
        }
        restore(context);
    }

    /**
     * A passing offer is about to ring: its alert outranks a decline still in progress, so the sound comes back now
     * and stays up for {@link #MAX_MS} while the alert plays.
     */
    static void yieldToPassingAlert(Context context) {
        passingAlertAt = SystemClock.elapsedRealtime();
        restore(context);
    }

    private static boolean yielding() {
        long since = SystemClock.elapsedRealtime() - passingAlertAt;
        return passingAlertAt != 0 && since >= 0 && since < MAX_MS;
    }

    /** Forgets a recent passing alert, as a process restart would. */
    static void forgetCache() {
        passingAlertAt = 0;
    }

    /** What is playing now, for diagnostics: "alarm (usage 4), media (usage 12)" or "nothing". */
    static String playing(Context context) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return "unknown";
        List<String> names = new ArrayList<>();
        for (AudioPlaybackConfiguration config : audio.getActivePlaybackConfigurations()) {
            AudioAttributes attributes = config.getAudioAttributes();
            String name = streamName(attributes.getVolumeControlStream()) + " (usage " + attributes.getUsage() + ")";
            if (!names.contains(name)) names.add(name);
        }
        return names.isEmpty() ? "nothing" : String.join(", ", names);
    }

    private void silence(List<AudioPlaybackConfiguration> playing) {
        if (!silencing || yielding()) return;
        if (audio.getMode() != AudioManager.MODE_NORMAL) {
            // A call is ringing or in progress: it must be heard as the phone is set, so put everything back.
            restore(context);
            return;
        }
        for (AudioPlaybackConfiguration config : playing) {
            AudioAttributes attributes = config.getAudioAttributes();
            if (spoken(attributes.getUsage())) continue;
            int stream = attributes.getVolumeControlStream();
            if (handled(stream)) silence(stream);
        }
    }

    /** Navigation directions and calls share the media stream on some phones; they never trigger turning it down. */
    private static boolean spoken(int usage) {
        return usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                || usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
                || usage == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
                || usage == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                || usage == AudioAttributes.USAGE_ASSISTANT;
    }

    private void silence(int stream) {
        SharedPreferences prefs = prefs(context);
        String key = key(stream);
        if (prefs.contains(key)) return;
        int volume = audio.getStreamVolume(stream);
        if (volume <= floor(audio, stream) || audio.isStreamMute(stream)) return;
        // Saved before anything changes, so a crash between here and stop() is undone at the next start.
        prefs.edit().putInt(key, volume).commit();
        try {
            if (stream == AudioManager.STREAM_ALARM) {
                audio.setStreamVolume(stream, floor(audio, stream), 0);
                // What the phone actually set, so only a level still at our value is put back later.
                prefs.edit().putInt(setKey(stream), audio.getStreamVolume(stream)).commit();
            } else {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
            }
            DiagnosticLog.log(context, "sound", "silenced " + streamName(stream) + " while declining");
        } catch (SecurityException refused) {
            prefs.edit().remove(key).remove(setKey(stream)).commit();
        }
    }

    /** Puts back any stream this class silenced, including after a crash. Leaves alone what the user changed. */
    static void restore(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getAll().isEmpty()) return;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null) {
            for (int stream : STREAMS) {
                String key = key(stream);
                if (!prefs.contains(key)) continue;
                try {
                    if (stream == AudioManager.STREAM_ALARM) {
                        // Left alone if the user has changed it since.
                        if (audio.getStreamVolume(stream) == prefs.getInt(setKey(stream), -1)) {
                            audio.setStreamVolume(stream, prefs.getInt(key, 0), 0);
                        }
                    } else if (audio.isStreamMute(stream)) {
                        audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                    }
                } catch (SecurityException refused) {
                    DiagnosticLog.log(context, "sound", "could not restore " + streamName(stream));
                }
            }
        }
        prefs.edit().clear().commit();
    }

    private static boolean handled(int stream) {
        for (int candidate : STREAMS) {
            if (candidate == stream) return true;
        }
        return false;
    }

    private static int floor(AudioManager audio, int stream) {
        return Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(stream) : 0;
    }

    private static String streamName(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC: return "media";
            case AudioManager.STREAM_ALARM: return "alarm";
            case AudioManager.STREAM_NOTIFICATION: return "notification";
            case AudioManager.STREAM_RING: return "ringer";
            case AudioManager.STREAM_SYSTEM: return "system";
            case AudioManager.STREAM_VOICE_CALL: return "call";
            default: return "stream " + stream;
        }
    }

    private static String key(int stream) {
        return "stream_" + stream;
    }

    private static String setKey(int stream) {
        return "set_" + stream;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

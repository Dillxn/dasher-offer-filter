package com.local.dasherfilter;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

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
 *
 * <p>One instance lives on the screen reader's scanner thread: it is started and stopped there, and Android's news
 * of new players and the time limit arrive there too. Putting the sound back can also happen on the main thread (a
 * passing offer's alert, a next offer's notification, the app opening, the service stopping), so turning a stream
 * down and putting streams back are done under one lock, and the owner's guard (the declined offer is still the one
 * on screen, no newer offer, not stopped) is asked again under that lock right before each stream goes down: a stream
 * is never turned down after it was put back for good, or over a next offer, and never left down with nothing saved
 * to put it back.
 */
final class OfferSilencer {
    static final long MAX_MS = 20_000;
    private static final int[] STREAMS = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM};
    private static final String PREFS = "silencer";

    private static final Object LOCK = new Object();

    private final Context context;
    private final AudioManager audio;
    private final Handler handler;
    private final Runnable timeout = this::stop;
    /** Whether a stream may go down now; asked on the owner's thread, under {@link #LOCK}. */
    private final BooleanSupplier mayQuiet;
    private final AudioManager.AudioPlaybackCallback newPlayers = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> playing) {
            // A new player, perhaps a next offer's ring: only while the declined offer is still the one on screen.
            if (mayQuiet.getAsBoolean()) silence(playing);
        }
    };
    private boolean silencing;
    /** When a passing offer's alert last asked to be heard; elapsed-realtime clock, 0 for never. */
    private static volatile long passingAlertAt;

    /**
     * @param handler where Android's news of new players and the time limit arrive: the caller's own thread
     * @param mayQuiet whether the declined offer's ring may still be turned down, asked on that thread right before
     *                 each stream goes down (under the lock {@link #nextOffer} takes too)
     */
    OfferSilencer(Context context, Handler handler, BooleanSupplier mayQuiet) {
        this.context = context.getApplicationContext();
        this.audio = this.context.getSystemService(AudioManager.class);
        this.handler = handler;
        this.mayQuiet = mayQuiet;
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
     * and stays up for {@link #MAX_MS} while the alert plays. Called on the main thread.
     */
    static void yieldToPassingAlert(Context context) {
        synchronized (LOCK) {
            passingAlertAt = SystemClock.elapsedRealtime();
            restoreLocked(context);
        }
    }

    /**
     * A next offer was announced (by notification, on the main thread): {@code newer} marks it (the offer generation
     * moves on), and the sound comes back at once, both under the lock, so a stream that a decline in progress was
     * about to turn down stays up. Called for every new offer; with nothing turned down it changes nothing.
     */
    static void nextOffer(Context context, Runnable newer) {
        synchronized (LOCK) {
            newer.run();
            restoreLocked(context);
        }
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
        synchronized (LOCK) {
            // A passing alert or a next offer may have asked for the sound back on another thread since the owner
            // decided to silence, and the service may have stopped: asked again here, where they cannot change.
            if (!silencing || yielding() || !mayQuiet.getAsBoolean()) return;
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
    }

    /**
     * Puts back any stream this class silenced, including after a crash. Leaves alone what the user changed. Safe on
     * any thread.
     */
    static void restore(Context context) {
        synchronized (LOCK) {
            restoreLocked(context);
        }
    }

    private static void restoreLocked(Context context) {
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

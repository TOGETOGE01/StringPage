package com.stringpage.app;

import alphaTab.collections.List;
import alphaTab.midi.AlphaTabRestEvent;
import alphaTab.midi.MidiFile;
import alphaTab.synth.IAudioSampleSynthesizer;
import alphaTab.synth.IAlphaSynth;
import alphaTab.synth.AlphaSynthWrapper;
import alphaTab.synth.BackingTrackPlayer;
import alphaTab.synth.BackingTrackSyncPoint;
import alphaTab.synth.MidiFileSequencer;
import alphaTab.synth.MidiSequencerState;
import alphaTab.synth.SynthConstants;
import alphaTab.synth.synthesis.SynthEvent;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** JVM bridge for the sequencer hooks in the pinned alphaTab dependency. */
public final class AlphaTabMetronomeBridge {
    private static final AtomicLong nextId = new AtomicLong(-1);
    private static final Map<Long, Clock> pending = new ConcurrentHashMap<>();
    private static final Map<MidiSequencerState, Clock> active = Collections.synchronizedMap(new WeakHashMap<>());

    interface Clock {
        void render(double start, double end, double speed, Clicks clicks);
        void mute();
    }

    interface Clicks {
        void emit(double time, double tick, int count, double ticks, double duration);
    }

    private AlphaTabMetronomeBridge() {}

    static long register(MidiFile midi, Clock clock) {
        long id = nextId.getAndDecrement();
        pending.put(id, clock);
        // The Android worker serializes the MIDI. A silent rest carries the registration across it.
        midi.addEvent(new AlphaTabRestEvent(0, 0, id));
        return id;
    }

    static void cancel(long id) { pending.remove(id); }

    static MetronomeTimeMap recordingMap(IAlphaSynth player, double duration) {
        if (!(player instanceof AlphaSynthWrapper wrapper) || !(wrapper.getInstance() instanceof BackingTrackPlayer backing)) {
            return new RecordingMetronomeMap(new double[0], new double[0]);
        }
        MidiFileSequencer sequencer = backing.getSequencer$android_release();
        List<BackingTrackSyncPoint> points = sequencer.getCurrentSyncPoints();
        int count = (int) points.getLength();
        if (count == 0) return new RecordingMetronomeMap(new double[0], new double[0]);
        double[] score = new double[count + 1];
        double[] media = new double[count + 1];
        for (int i = 0; i < count; i++) {
            score[i] = points.get(i).getSynthTime();
            media[i] = points.get(i).getSyncTime();
        }
        score[count] = sequencer.getCurrentEndTime() * sequencer.getPlaybackSpeed();
        media[count] = duration;
        return new RecordingMetronomeMap(score, media);
    }

    public static void configure(MidiSequencerState state) {
        SynthEvent marker = null;
        Clock clock = null;
        for (SynthEvent event : state.getSynthData()) {
            if (event.getTime() > 0) break;
            if (event.getEvent() instanceof AlphaTabRestEvent rest && rest.getChannel() < 0) {
                clock = pending.remove((long) rest.getChannel());
                marker = event;
                break;
            }
        }
        if (clock == null) return;
        List<SynthEvent> events = new List<>();
        for (SynthEvent event : state.getSynthData()) {
            if (!event.isMetronome() && event != marker) events.push(event);
        }
        state.setSynthData(events);
        active.put(state, clock);
    }

    public static void renderBlock(MidiSequencerState main, MidiSequencerState state,
                                   IAudioSampleSynthesizer synth, double speed) {
        if (main != state) return;
        Clock clock = active.get(main);
        if (clock == null) return;
        if (synth.getMetronomeVolume() <= 0) {
            clock.mute();
            return;
        }
        double blockEnd = state.getCurrentTime();
        double start = blockEnd - SynthConstants.getMicroBufferSize() / synth.getOutSampleRate() * 1000 * speed;
        double limit = state.getPlaybackRange() == null ? state.getEndTime() : state.getPlaybackRangeEndTime();
        double end = Math.min(blockEnd, limit);
        if (start < end) {
            clock.render(start, end, speed, (time, tick, count, ticks, duration) -> {
                SynthEvent event = SynthEvent.newMetronomeEvent(0, tick, count, ticks, duration);
                event.setTime(time);
                synth.dispatchEvent(event);
            });
        }
    }
}

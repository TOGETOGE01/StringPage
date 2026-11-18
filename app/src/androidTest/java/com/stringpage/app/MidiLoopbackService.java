package com.stringpage.app;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;
import java.io.IOException;

public final class MidiLoopbackService extends MidiDeviceService {
    @Override public MidiReceiver[] onGetInputPortReceivers() {
        return new MidiReceiver[] { new MidiReceiver() {
            @Override public void onSend(byte[] data, int offset, int count, long timestamp) throws IOException {
                getOutputPortReceivers()[0].send(data, offset, count, timestamp);
            }
        } };
    }
}

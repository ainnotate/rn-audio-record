package com.goodatlas.audiorecord;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import android.media.AudioManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class RNAudioRecordModule extends ReactContextBaseJavaModule {

    private final String TAG = "RNAudioRecord";
    private final ReactApplicationContext reactContext;
    private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;

    private int sampleRateInHz;
    private int channelConfig;
    private int audioFormat;
    private int audioSource;

    private AudioRecord recorder;
    private int bufferSize;
    private boolean isRecording;

    private String tmpFile;
    private String outFile;
    private Promise stopRecordingPromise;
    private boolean isPaused;

    boolean isBTConnected = false;

    AudioManager audioManager;

    public RNAudioRecordModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "RNAudioRecord";
    }


    // Broadcast receiver - not used for now
    private BroadcastReceiver mBluetoothScoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
            Log.d(TAG, "Aidac init recording state = " + state);
            if (AudioManager.SCO_AUDIO_STATE_CONNECTED == state) {
                Log.d(TAG, "Aidac got state connected...");
                isBTConnected = true;
                /*
                 * Now the connection has been established to the bluetooth device.
                 * Record audio or whatever (on another thread).With AudioRecord you can record with an object created like this:
                 * new AudioRecord(MediaRecorder.AudioSource.MIC, 8000, AudioFormat.CHANNEL_CONFIGURATION_MONO,
                 * AudioFormat.ENCODING_PCM_16BIT, audioBufferSize);
                 *
                 * After finishing, don't forget to unregister this receiver and
                 * to stop the bluetooth connection with am.stopBluetoothSco();
                 */
            }
        }
    };


    public static boolean isBluetoothHeadsetConnected() {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        return mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()
                && mBluetoothAdapter.getProfileConnectionState(BluetoothHeadset.HEADSET) == BluetoothHeadset.STATE_CONNECTED;
    } 

    @ReactMethod
    public void init(ReadableMap options) {
        sampleRateInHz = 44100;
        isPaused = false;

        Log.d(TAG, "Aidac audio - init recording");

        if (options.hasKey("sampleRate")) {
            sampleRateInHz = options.getInt("sampleRate");
        }

        channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (options.hasKey("channels")) {
            if (options.getInt("channels") == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
        }

        audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        if (options.hasKey("bitsPerSample")) {
            if (options.getInt("bitsPerSample") == 8) {
                audioFormat = AudioFormat.ENCODING_PCM_8BIT;
            }
        }

        audioSource = AudioSource.VOICE_RECOGNITION;
        if (options.hasKey("audioSource")) {
            audioSource = options.getInt("audioSource");
        }

        String documentDirectoryPath = getReactApplicationContext().getFilesDir().getAbsolutePath();
        outFile = documentDirectoryPath + "/" + "audio.wav";
        tmpFile = documentDirectoryPath + "/" + "temp.pcm";
        if (options.hasKey("wavFile")) {
            String fileName = options.getString("wavFile");
            outFile = documentDirectoryPath + "/" + fileName;
        }

        isRecording = false;
        eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);

        bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
    }

    @ReactMethod
    public void start() {
        isRecording = true;
        isPaused = false;

        if(isBluetoothHeadsetConnected()) {
            Log.d(TAG, "Aidac BT device is connected...");
            isBTConnected = true;
        } else {
            Log.d(TAG, "Aidac BT device is NOT connected...");
            isBTConnected = false;
        }

/*        IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        reactContext.registerReceiver(mBluetoothScoReceiver, intentFilter);
*/
        if(isBTConnected) {
            audioManager = (AudioManager) reactContext.getApplicationContext().getSystemService(reactContext.getApplicationContext().AUDIO_SERVICE);

            // Start Bluetooth SCO.
            audioManager.setMode(audioManager.MODE_NORMAL);
            audioManager.setBluetoothScoOn(true);
            audioManager.startBluetoothSco();
        }


        int recordingBufferSize = bufferSize * 3;
        recorder = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, recordingBufferSize);

        recorder.startRecording();
        Log.d(TAG, "Aidac started recording");

        Thread recordingThread = new Thread(new Runnable() {
            public void run() {
                try {
                    int bytesRead;
                    int count = 0;
                    String base64Data;
                    byte[] buffer = new byte[bufferSize];
                    FileOutputStream os = new FileOutputStream(tmpFile);

                    while (isRecording) {
                        bytesRead = recorder.read(buffer, 0, buffer.length);

                        if(isPaused) 
                            continue;

                        // skip first 2 buffers to eliminate "click sound"
                        if (bytesRead > 0 && ++count > 2) {
                            base64Data = Base64.encodeToString(buffer, Base64.NO_WRAP);
                            eventEmitter.emit("data", base64Data);
                            os.write(buffer, 0, bytesRead);
                        }
                    }

                    isPaused = false;
                    recorder.stop();
                    os.close();
                    saveAsWav();
                    stopRecordingPromise.resolve(outFile);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        recordingThread.start();
    }

    @ReactMethod
    public void stop(Promise promise) {
        isRecording = false;
        isPaused = false;
        stopRecordingPromise = promise;

        //reactContext.unregisterReceiver(mBluetoothScoReceiver);
        if(isBTConnected) {
            // Stop Bluetooth SCO.
            audioManager.stopBluetoothSco();
            audioManager.setMode(audioManager.MODE_NORMAL);
            audioManager.setBluetoothScoOn(false);
        }
    }

    @ReactMethod
    public void pause(boolean paused) {
        isPaused = paused;
    }


    private void saveAsWav() {
        try {
            FileInputStream in = new FileInputStream(tmpFile);
            FileOutputStream out = new FileOutputStream(outFile);
            long totalAudioLen = in.getChannel().size();;
            long totalDataLen = totalAudioLen + 36;

            addWavHeader(out, totalAudioLen, totalDataLen);

            byte[] data = new byte[bufferSize];
            int bytesRead;
            while ((bytesRead = in.read(data)) != -1) {
                out.write(data, 0, bytesRead);
            }
            Log.d(TAG, "file path:" + outFile);
            Log.d(TAG, "file size:" + out.getChannel().size());

            in.close();
            out.close();
            deleteTempFile();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addWavHeader(FileOutputStream out, long totalAudioLen, long totalDataLen)
            throws Exception {

        long sampleRate = sampleRateInHz;
        int channels = channelConfig == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
        int bitsPerSample = audioFormat == AudioFormat.ENCODING_PCM_8BIT ? 8 : 16;
        long byteRate =  sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        header[0] = 'R';                                    // RIFF chunk
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);           // how big is the rest of this file
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';                                    // WAVE chunk
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';                                   // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;                                    // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;                                     // format = 1 for PCM
        header[21] = 0;
        header[22] = (byte) channels;                       // mono or stereo
        header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);            // samples per second
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);              // bytes per second
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) blockAlign;                     // bytes in one sample, for all channels
        header[33] = 0;
        header[34] = (byte) bitsPerSample;                  // bits in a sample
        header[35] = 0;
        header[36] = 'd';                                   // beginning of the data chunk
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);         // how big is this data chunk
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
    }

    private void deleteTempFile() {
        File file = new File(tmpFile);
        file.delete();
    }
}

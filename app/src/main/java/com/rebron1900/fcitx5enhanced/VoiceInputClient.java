package com.rebron1900.fcitx5enhanced;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** 振幅回调接口 */
interface AmplitudeListener {
    void onAmplitude(float normalizedAmplitude);
}

/**
 * 通过 AIDL 连接 bibi keyboard 外部语音识别服务的客户端。
 * 每次按下创建新实例，松开后清理。
 *
 * 关键设计：
 * - mConsumed 原子标记：回调发到主线程前先标记为"已消费"，防止重复提交
 * - stopSequence: 先停止并等待录音线程 → 再 finishPcm → 确保数据完整
 * - cancel(): 标记已取消 + unbind → 新按下时清除旧 session
 */
public class VoiceInputClient {

    private static final String TAG = "VoiceInput";

    private static final String DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService";
    private static final String DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback";

    // 事务码 (FIRST_CALL_TRANSACTION=1)
    private static final int TRANSACTION_startPcmSession = 7;
    private static final int TRANSACTION_writePcm = 8;
    private static final int TRANSACTION_finishPcm = 9;
    private static final int TRANSACTION_cancelSession = 3;

    // 回调事务码
    private static final int CB_onPartial = 2;
    private static final int CB_onFinal = 3;
    private static final int CB_onError = 4;
    private static final int CB_onAmplitude = 5;

    private static final int STATE_IDLE = 0;
    private static final int STATE_RECORDING = 1;

    // ── 状态 ──
    private volatile boolean mHolding;
    private volatile boolean mConsumed;  // Atomic 语义：一旦标记就不再提交文字
    private final AtomicBoolean mCompletionClaimed = new AtomicBoolean();
    private volatile boolean mFinalized; // 收到最终结果（可能有 AI 润色版跟随）
    private volatile String mRawText;      // 第1次 onFinal 的原始识别文本
    private Runnable mCommitTimer;       // 延时 commit 兜底
    private InputMethodService mService;
    private volatile IBinder mRemote;
    private ServiceConnection mConnection;
    private boolean mBound;
    private volatile int mSessionId = -1;
    private volatile int mCurrentState = STATE_IDLE;
    private Thread mAudioThread;
    private volatile AudioRecord mAudioRecord;
    private volatile boolean mHasPcmFrame;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private WeakReference<InputConnection> mInputConnectionRef;
    private Runnable mOnDone;              // 强引用（原 WeakReference 易被 GC 导致波形无法复位）
    private AmplitudeListener mAmpListener;

    private static final class AudioCapture {
        final Thread thread;
        final AudioRecord record;

        AudioCapture(Thread thread, AudioRecord record) {
            this.thread = thread;
            this.record = record;
        }
    }

    /** 设置振幅回调（UI 更新） */
    public void setAmplitudeListener(AmplitudeListener l) {
        mAmpListener = l;
    }

    /**
     * 开始语音识别会话。
     */
    public void startVoiceInput(InputMethodService service, InputConnection ic, Runnable onDone) {
        mService = service;
        mHolding = true;
        mConsumed = false;
        mCompletionClaimed.set(false);
        mFinalized = false;
        mRawText = null;
        mHasPcmFrame = false;
        mInputConnectionRef = new WeakReference<>(ic);
        mOnDone = onDone;

        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!mHolding || mConsumed) {
                    Log.d(TAG, "onServiceConnected: already cancelled, unbinding");
                    doUnbind();
                    return;
                }
                try {
                    if (binder == null) throw new IllegalStateException("no binder");
                    mRemote = binder;

                    final Binder cbBinder = new Binder() {
                        @Override
                        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                            try {
                                if (mConsumed) {
                                    // 已取消：回应 success 但不做任何事
                                    if (reply != null) reply.writeNoException();
                                    return true;
                                }
                                switch (code) {
                                    case CB_onPartial: {
                                        data.enforceInterface(DESCRIPTOR_CB);
                                        data.readInt();
                                        String text = data.readString();
                                        if (text == null) text = "";
                                        final String t = text;
                                        mMainHandler.post(() -> {
                                            if (mConsumed) return;
                                            InputConnection icL = mInputConnectionRef != null
                                                    ? mInputConnectionRef.get() : null;
                                            if (icL != null) icL.setComposingText(t, 1);
                                        });
                                        if (reply != null) reply.writeNoException();
                                        return true;
                                    }
                                    case CB_onFinal: {
                                        data.enforceInterface(DESCRIPTOR_CB);
                                        data.readInt();
                                        String text = data.readString();
                                        if (text == null) text = "";
                                        final String t = text;

                                        // Binder 回调可并发；全部交给主线程串行处理，确保第 2 个
                                        // onFinal 一定能取消第 1 个结果的延时提交。
                                        mMainHandler.post(() -> handleFinalResult(t));
                                        if (reply != null) reply.writeNoException();
                                        return true;
                                    }
                                    case CB_onError: {
                                        data.enforceInterface(DESCRIPTOR_CB);
                                        data.readInt();
                                        int errCode = data.readInt();
                                        String msg = data.readString();
                                        Log.w(TAG, "ASR error: " + errCode + " " + msg);
                                        if (mConsumed) {
                                            if (reply != null) reply.writeNoException();
                                            return true;
                                        }
                                        showToast("语音识别错误: " + (msg != null ? msg : "code=" + errCode));
                                        completeWithoutResult();
                                        if (reply != null) reply.writeNoException();
                                        return true;
                                    }
                                    case CB_onAmplitude: {
                                        data.enforceInterface(DESCRIPTOR_CB);
                                        data.readInt();
                                        data.readFloat();
                                        if (reply != null) reply.writeNoException();
                                        return true;
                                    }
                                    case IBinder.INTERFACE_TRANSACTION: {
                                        if (reply != null) reply.writeString(DESCRIPTOR_CB);
                                        return true;
                                    }
                                    default:
                                        return super.onTransact(code, data, reply, flags);
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "callback transact failed", t);
                                return false;
                            }
                        }
                    };

                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    int sid = -999;
                    try {
                        data.writeInterfaceToken(DESCRIPTOR_SVC);
                        // presence=1, 带 SpeechConfig（使用已配置的 ASR 引擎）
                        data.writeInt(1);
                        data.writeString(null);  // vendorId=null → app 设置
                        data.writeInt(1);        // streamingPreferred=true
                        data.writeInt(-1);       // punctuationEnabled=null
                        data.writeInt(-1);       // autoStopOnSilence=null
                        data.writeString(null);  // sessionTag=null
                        data.writeStrongBinder(cbBinder);
                        binder.transact(TRANSACTION_startPcmSession, data, reply, 0);
                        reply.readException();
                        sid = reply.readInt();
                    } finally {
                        try { data.recycle(); } catch (Throwable ignored) {}
                        try { reply.recycle(); } catch (Throwable ignored) {}
                    }

                    if (sid <= 0) {
                        Log.w(TAG, "startPcmSession returned " + sid);
                        showToast("bibi 会话启动失败 (code=" + sid + ")");
                        completeWithoutResult();
                    } else {
                        mSessionId = sid;
                        mCurrentState = STATE_RECORDING;
                        startAudioStreaming();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "bind/start failed", t);
                    showToast("无法连接 bibi keyboard 服务");
                    completeWithoutResult();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "speech service disconnected");
                completeWithoutResult();
            }
        };
        mConnection = conn;

        ComponentName[] candidates = new ComponentName[]{
                new ComponentName("com.brycewg.asrkb.pro",
                        "com.brycewg.asrkb.api.ExternalSpeechService"),
                new ComponentName("com.brycewg.asrkb",
                        "com.brycewg.asrkb.api.ExternalSpeechService")
        };

        boolean anyBound = false;
        for (ComponentName c : candidates) {
            Intent intent = new Intent().setComponent(c);
            try {
                anyBound = service.bindService(intent, conn, Context.BIND_AUTO_CREATE);
                if (anyBound) {
                    // 立刻标记，避免极快的连接/取消回调落在循环末尾赋值之前。
                    mBound = true;
                    break;
                }
            } catch (Throwable t) {
                Log.d(TAG, "bind attempt failed: " + c.getPackageName(), t);
            }
        }
        if (!anyBound) {
            showToast("未找到 bibi keyboard");
            completeWithoutResult();
        }
    }

    /**
     * 停止语音识别（松开按钮时调用）。
     * 先停录音线程 → 再 finishPcm 告诉服务端处理音频。
     */
    public void stopVoiceInput() {
        if (!mHolding || mConsumed) return;
        mHolding = false;

        if (mCurrentState == STATE_IDLE) {
            Log.d(TAG, "stopVoiceInput: still binding, cancel");
            completeWithoutResult();
            return;
        }

        // 关键顺序同原版：先停录音 → 再 finishPcm（已在 finishSession 内部处理）
        if (mHasPcmFrame) {
            finishSession();
        } else {
            completeWithoutResult();
        }
    }

    /**
     * 强制取消当前会话（新按下时调用旧 client 的 cancel）。
     * 设置 mConsumed 后所有回调都不会提交文字。
     */
    public void cancel() {
        completeWithoutResult();
    }

    // ── 音频流 ──

    private void startAudioStreaming() {
        InputMethodService service = mService;
        if (service == null || service.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "缺少 RECORD_AUDIO 权限");
            showToast("需要麦克风权限");
            completeWithoutResult();
            return;
        }

        mAudioThread = new Thread(() -> {
            int sr = 16000;
            int ch = AudioFormat.CHANNEL_IN_MONO;
            int fmt = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioRecord.getMinBufferSize(sr, ch, fmt);
            int chunkBytes = (sr * 200 / 1000) * 2;
            int bufSize = Math.max(minBuf, chunkBytes * 2);
            AudioRecord rec = null;

            try {
                try {
                    rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            sr, ch, fmt, bufSize);
                    rec.startRecording();
                } catch (Throwable first) {
                    releaseAudioRecord(rec);
                    rec = null;
                    Log.w(TAG, "VOICE_RECOGNITION AudioRecord failed", first);
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            sr, ch, fmt, bufSize);
                    rec.startRecording();
                }
                mAudioRecord = rec;
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord not initialized");
                }

                byte[] chunk = new byte[chunkBytes];
                boolean notified = false;
                boolean streamFailed = false;
                while (!Thread.currentThread().isInterrupted()
                        && mSessionId > 0 && mRemote != null && !mConsumed && mHolding) {
                    int n;
                    try {
                        n = rec.read(chunk, 0, chunk.length);
                    } catch (Throwable t) {
                        Log.w(TAG, "AudioRecord.read failed", t);
                        streamFailed = true;
                        break;
                    }
                    if (n < 0) {
                        streamFailed = true;
                        break;
                    }
                    if (n == 0) {
                        try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                        continue;
                    }
                    if (!notified) {
                        notified = true;
                        Log.d(TAG, "audio streaming started");
                    }
                    writePcmFrame(chunk, n, sr, 1);
                    AmplitudeListener listener = mAmpListener;
                    if (listener != null) listener.onAmplitude(computeRmsAmplitude(chunk, n));
                }
                if (streamFailed && !mConsumed) completeWithoutResult();
            } catch (Throwable t) {
                Log.e(TAG, "audio streaming failed", t);
                if (!mConsumed) completeWithoutResult();
            } finally {
                if (mAudioRecord == rec) {
                    mAudioRecord = null;
                    releaseAudioRecord(rec);
                }
            }
        }, "Fcitx5Enh-Audio");
        mAudioThread.setDaemon(true);
        mAudioThread.start();
    }

    private AudioCapture stopAudioStreaming() {
        Thread thread = mAudioThread;
        mAudioThread = null;
        AudioRecord record = mAudioRecord;
        mAudioRecord = null;

        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) {}
        }
        if (thread != null) thread.interrupt();
        return new AudioCapture(thread, record);
    }

    private static void awaitAudioCapture(AudioCapture capture) {
        if (capture == null) return;
        if (capture.thread != null && capture.thread != Thread.currentThread()) {
            try {
                capture.thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseAudioRecord(capture.record);
    }

    private static void runSessionTask(Runnable task) {
        Thread worker = new Thread(task, "Fcitx5Enh-VoiceSession");
        worker.setDaemon(true);
        worker.start();
    }

    private static void releaseAudioRecord(AudioRecord record) {
        if (record == null) return;
        try { record.stop(); } catch (Throwable ignored) {}
        try { record.release(); } catch (Throwable ignored) {}
    }

    private void writePcmFrame(byte[] buf, int len, int sr, int ch) {
        if (mRemote == null || mSessionId <= 0) return;
        if (len > 0) mHasPcmFrame = true;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC);
            data.writeInt(mSessionId);
            if (len == buf.length) data.writeByteArray(buf);
            else {
                byte[] copy = new byte[len];
                System.arraycopy(buf, 0, copy, 0, len);
                data.writeByteArray(copy);
            }
            data.writeInt(sr);
            data.writeInt(ch);
            mRemote.transact(TRANSACTION_writePcm, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "writePcm failed", t);
        } finally {
            try { data.recycle(); } catch (Throwable ignored) {}
            try { reply.recycle(); } catch (Throwable ignored) {}
        }
    }

    // ── 会话控制 ──

    /** 先停录音并等待音频线程，再在后台发送 finishPcm。 */
    private void finishSession() {
        IBinder remote = mRemote;
        int sessionId = mSessionId;
        AudioCapture capture = stopAudioStreaming();
        runSessionTask(() -> {
            awaitAudioCapture(capture);
            // ACTION_CANCEL / detach may have completed the session while the audio thread stopped.
            if (mCompletionClaimed.get() || mConsumed) return;
            if (remote == null || sessionId <= 0) {
                completeWithoutResult();
                return;
            }

            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR_SVC);
                data.writeInt(sessionId);
                remote.transact(TRANSACTION_finishPcm, data, reply, 0);
                reply.readException();
            } catch (Throwable t) {
                Log.w(TAG, "finishSession failed", t);
                completeWithoutResult();
            } finally {
                try { data.recycle(); } catch (Throwable ignored) {}
                try { reply.recycle(); } catch (Throwable ignored) {}
            }
        });
    }

    private void cancelSession() {
        IBinder remote = mRemote;
        int sessionId = mSessionId;
        if (remote == null || sessionId <= 0) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC);
            data.writeInt(sessionId);
            remote.transact(TRANSACTION_cancelSession, data, reply, 0);
            reply.readException();
        } catch (Throwable t) {
            Log.w(TAG, "cancelSession failed", t);
        } finally {
            try { data.recycle(); } catch (Throwable ignored) {}
            try { reply.recycle(); } catch (Throwable ignored) {}
        }
    }

    private void doUnbind() {
        try {
            if (mBound && mConnection != null && mService != null) {
                mService.unbindService(mConnection);
            }
        } catch (Throwable ignored) {}
        mBound = false;
        mConnection = null;
        mRemote = null;
        mService = null;
        mSessionId = -1;
        mCurrentState = STATE_IDLE;
        mHasPcmFrame = false;
    }

    /** 主线程上串行处理最终识别结果，防止 Binder 回调并发导致定时器漏取消。 */
    private void handleFinalResult(String text) {
        if (mConsumed) return;

        if (claimFirstFinal(text)) {
            // 第 1 个结果：先作为 composing 显示，给 AI 润色结果最多 800ms 的替换窗口。
            InputConnection ic = mInputConnectionRef != null ? mInputConnectionRef.get() : null;
            if (ic != null) ic.setComposingText(text, 1);
            mCommitTimer = () -> {
                if (!claimCompletion()) return;
                mCommitTimer = null;
                InputConnection timerIc = mInputConnectionRef != null ? mInputConnectionRef.get() : null;
                if (timerIc != null) timerIc.commitText(mRawText != null ? mRawText : text, 1);
                Runnable done = mOnDone;
                if (done != null) done.run();
                doUnbind();
            };
            mMainHandler.postDelayed(mCommitTimer, 800);
            return;
        }

        // 第 2 个结果：AI 润色版，取消原始结果的兜底提交后立即提交。
        if (mCommitTimer != null) {
            mMainHandler.removeCallbacks(mCommitTimer);
            mCommitTimer = null;
        }
        if (!claimCompletion()) return;
        InputConnection ic = mInputConnectionRef != null ? mInputConnectionRef.get() : null;
        if (ic != null) ic.commitText(text, 1);
        Runnable done = mOnDone;
        if (done != null) done.run();
        doUnbind();
    }

    private synchronized boolean claimFirstFinal(String text) {
        if (mFinalized) return false;
        mFinalized = true;
        mRawText = text;
        return true;
    }

    private boolean claimCompletion() {
        if (!mCompletionClaimed.compareAndSet(false, true)) return false;
        mConsumed = true;
        return true;
    }

    private void completeWithoutResult() {
        if (!claimCompletion()) return;
        mHolding = false;
        // mCommitTimer 仅在主线程创建/访问，避免与 Binder 回调竞争。
        mMainHandler.post(() -> {
            if (mCommitTimer != null) {
                mMainHandler.removeCallbacks(mCommitTimer);
                mCommitTimer = null;
            }
        });
        AudioCapture capture = stopAudioStreaming();
        runSessionTask(() -> {
            awaitAudioCapture(capture);
            cancelSession();
            mMainHandler.post(() -> {
                doUnbind();
                Runnable done = mOnDone;
                if (done != null) done.run();
            });
        });
    }

    private void showToast(String msg) {
        final InputMethodService service = mService;
        if (service == null) return;
        mMainHandler.post(() -> Toast.makeText(service, msg, Toast.LENGTH_SHORT).show());
    }

    /** 从 PCM 16bit 数据计算 RMS 归一化振幅 */
    private float computeRmsAmplitude(byte[] buffer, int len) {
        int samples = len / 2;
        if (samples <= 0) return 0f;
        double sumSq = 0;
        for (int i = 0; i < samples; i++) {
            short sample = (short) ((buffer[i * 2 + 1] << 8) | (buffer[i * 2] & 0xFF));
            sumSq += (double) sample * sample;
        }
        float rms = (float) Math.sqrt(sumSq / samples);
        // 归一化到 0~1（16bit max = 32768）
        float norm = rms / 32768f;
        // 环境噪声通常在 0.01~0.05，提高灵敏度
        if (norm < 0.02f) norm = 0f;
        return Math.min(1f, norm * 4f);
    }
}

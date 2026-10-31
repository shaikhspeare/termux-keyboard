package dev.probe.ime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class ProbeIME extends InputMethodService {
    static final String TAG = "PROBEIME";
    static volatile ProbeIME INSTANCE;
    private TextView mLog;
    private final StringBuilder mBuf = new StringBuilder();

    @Override public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        Log.i(TAG, "onCreate");
    }

    @Override public void onStartInput(EditorInfo ei, boolean restarting) {
        super.onStartInput(ei, restarting);
        Log.i(TAG, "onStartInput restarting=" + restarting + " " + describe(ei));
    }

    @Override public void onStartInputView(EditorInfo ei, boolean restarting) {
        super.onStartInputView(ei, restarting);
        Log.i(TAG, "onStartInputView restarting=" + restarting + " inputType=0x"
                + Integer.toHexString(ei.inputType) + " class=" + (ei.inputType & InputType.TYPE_MASK_CLASS)
                + " variation=0x" + Integer.toHexString(ei.inputType & InputType.TYPE_MASK_VARIATION)
                + " flags=0x" + Integer.toHexString(ei.inputType & InputType.TYPE_MASK_FLAGS)
                + " imeOptions=0x" + Integer.toHexString(ei.imeOptions)
                + " pkg=" + ei.packageName + " fieldId=" + ei.fieldId
                + " initialSel=" + ei.initialSelStart + ".." + ei.initialSelEnd
                + " hint=" + ei.hintText + " label=" + ei.label);
        note("onStartInputView inputType=0x" + Integer.toHexString(ei.inputType)
                + " imeOptions=0x" + Integer.toHexString(ei.imeOptions) + " pkg=" + ei.packageName);
    }

    @Override public void onFinishInput() {
        Log.i(TAG, "onFinishInput");
        super.onFinishInput();
    }

    private static String describe(EditorInfo ei) {
        if (ei == null) return "EditorInfo=null";
        return "inputType=0x" + Integer.toHexString(ei.inputType)
                + " imeOptions=0x" + Integer.toHexString(ei.imeOptions)
                + " pkg=" + ei.packageName
                + " fieldId=" + ei.fieldId
                + " fieldName=" + ei.fieldName
                + " hint=" + ei.hintText
                + " initialSel=" + ei.initialSelStart + ".." + ei.initialSelEnd
                + " initialCapsMode=0x" + Integer.toHexString(ei.initialCapsMode);
    }

    @Override public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF202020);

        mLog = new TextView(this);
        mLog.setTextColor(0xFF00FF00);
        mLog.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(mLog);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 260);
        root.addView(scroll, lp);

        root.addView(row("a", "b", "g", "i", "git status", "ENTER"));
        root.addView(row("READ", "COMP", "DEL", "KEYA", "SEL", "CLR"));
        return root;
    }

    private LinearLayout row(final String... labels) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final String label : labels) {
            Button b = new Button(this);
            b.setText(label);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { run(label); }
            });
            row.addView(b, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    void note(String s) {
        Log.i(TAG, "NOTE " + s);
        mBuf.append(s).append('\n');
        if (mBuf.length() > 4000) mBuf.delete(0, mBuf.length() - 4000);
        if (mLog != null) mLog.post(() -> mLog.setText(mBuf.toString()));
    }

    InputConnection ic() { return getCurrentInputConnection(); }

    void run(String label) {
        InputConnection ic = ic();
        long t0 = SystemClock.uptimeMillis();
        String out;
        switch (label) {
            case "a": case "b": case "g": case "i": {
                boolean ok = ic != null && ic.commitText(label, 1);
                out = "commitText(\"" + label + "\") -> " + ok;
                break;
            }
            case "suffix": {
                boolean ok = ic != null && ic.commitText("t status", 1);
                out = "commitText(\"t status\") -> " + ok;
                break;
            }
            case "componly": {
                boolean a = ic != null && ic.setComposingText("xyz", 1);
                CharSequence before = ic == null ? null : ic.getTextBeforeCursor(50, 0);
                out = "setComposingText(\"xyz\") -> " + a + " before=[" + str(before) + "]";
                break;
            }
            case "ctrl": {
                boolean ok = ic != null && ic.commitText("\u0003", 1);
                out = "commitText(ETX) -> " + ok;
                break;
            }
            case "ENTER": {
                boolean ok = ic != null && ic.commitText("\n", 1);
                out = "commitText(\"\\n\") -> " + ok;
                break;
            }
            case "READ": {
                out = "READ before=[" + str(ic.getTextBeforeCursor(200, 0))
                        + "] after=[" + str(ic.getTextAfterCursor(200, 0))
                        + "] selected=[" + str(ic.getSelectedText(0)) + "]";
                break;
            }
            case "COMP": {
                boolean a = ic != null && ic.setComposingText("comp", 1);
                boolean b = ic != null && ic.finishComposingText();
                out = "setComposingText(\"comp\") -> " + a + " finishComposingText -> " + b;
                break;
            }
            case "DEL": {
                boolean ok = ic != null && ic.deleteSurroundingText(1, 0);
                out = "deleteSurroundingText(1,0) -> " + ok;
                break;
            }
            case "KEYA": {
                boolean ok = ic != null && ic.sendKeyEvent(new KeyEvent(
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
                if (ic != null) ok &= ic.sendKeyEvent(new KeyEvent(
                        KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A));
                out = "sendKeyEvent(KEYCODE_A) -> " + ok;
                break;
            }
            case "SEL": {
                ExtractedText et = ic == null ? null
                        : ic.getExtractedText(new ExtractedTextRequest(), 0);
                out = "getExtractedText -> " + (et == null ? "null"
                        : "text=[" + et.text + "] selStart=" + et.selectionStart
                          + " selEnd=" + et.selectionEnd)
                        + " capsMode=0x" + Integer.toHexString(ic == null ? -1 : ic.getCursorCapsMode(0));
                break;
            }
            case "sock": {
                try {
                    java.net.ServerSocket ss = new java.net.ServerSocket(0, 1,
                            java.net.InetAddress.getByName("127.0.0.1"));
                    out = "TCP loopback bind OK port=" + ss.getLocalPort();
                    ss.close();
                } catch (Throwable t) { out = "TCP loopback bind FAILED: " + t; }
                break;
            }
            case "lsock": {
                try {
                    android.net.LocalSocket ls = new android.net.LocalSocket();
                    ls.connect(new android.net.LocalSocketAddress("tkprobe",
                            android.net.LocalSocketAddress.Namespace.ABSTRACT));
                    try {
                        ls.getOutputStream().write("ping\n".getBytes());
                        ls.getOutputStream().flush();
                        byte[] buf = new byte[64];
                        int r = ls.getInputStream().read(buf);
                        out = "ABSTRACT connect OK, read=" + (r <= 0 ? "<empty>" : new String(buf, 0, r).trim());
                    } finally { ls.close(); }
                } catch (Throwable t) { out = "ABSTRACT connect FAILED: " + t; }
                break;
            }
            case "lserver": {
                try {
                    java.io.File f = new java.io.File(getCacheDir(), "probe.sock");
                    f.delete();
                    android.net.LocalServerSocket srv =
                            new android.net.LocalServerSocket(f.getAbsolutePath());
                    out = "FILESYSTEM LocalServerSocket OK at " + f.getAbsolutePath();
                    srv.close();
                } catch (Throwable t) { out = "FILESYSTEM LocalServerSocket FAILED: " + t; }
                break;
            }
            case "CLR": {
                mBuf.setLength(0);
                mLog.setText("");
                out = "cleared";
                break;
            }
            default:
                out = "unknown";
        }
        note(out + "  (" + (SystemClock.uptimeMillis() - t0) + "ms)");
    }

    private static String str(CharSequence cs) {
        return cs == null ? "<null>" : cs.toString();
    }

    public static class ProbeReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            ProbeIME svc = INSTANCE;
            Log.i(TAG, "broadcast " + intent.getStringExtra("cmd"));
            if (svc != null) svc.run(intent.getStringExtra("cmd"));
            else Log.i(TAG, "NOTE no IME instance");
        }
    }
}

package com.wentuyi.ime;

import android.graphics.Bitmap;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.EditorInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;
import java.io.File;
import java.io.FileOutputStream;

public class WentuyiIME extends InputMethodService implements KeyboardView.OnKeyboardActionListener {
    private KeyboardView kv;
    private Keyboard keyboard;

    @Override
    public View onCreateInputView() {
        kv = (KeyboardView) getLayoutInflater().inflate(R.layout.input, null);
        keyboard = new Keyboard(this, R.xml.qwerty);
        kv.setKeyboard(keyboard);
        kv.setOnKeyboardActionListener(this);
        return kv;
    }

    @Override public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (primaryCode == 1000) {
            onWentuyiAction();
        } else {
            ic.commitText(String.valueOf((char) primaryCode), 1);
        }
    }

    public void onWentuyiAction() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(1000, 0);
        if (text == null || text.length() == 0) return;
        Bitmap bitmap = AntiOcrRenderer.generateImage(text.toString(), 50);
        File imageFile = saveBitmapToFile(bitmap);
        if (imageFile != null) {
            // Simply copy to clipboard as a shortcut
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            // In a real app we'd use a FileProvider, but for a test APK this is enough
            cm.setPrimaryClip(ClipData.newPlainText("Wentuyi", "Image Generated (Test)"));
        }
        ic.deleteSurroundingText(text.length(), 0);
    }

    private File saveBitmapToFile(Bitmap bitmap) {
        try {
            File dir = new File(getFilesDir(), "images");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "wentuyi_msg.png");
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            return file;
        } catch (Exception e) { return null; }
    }

    @Override public void onPress(int primaryCode) {}
    @Override public void onRelease(int primaryCode) {}
    @Override public void onText(CharSequence text) {}
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}
}

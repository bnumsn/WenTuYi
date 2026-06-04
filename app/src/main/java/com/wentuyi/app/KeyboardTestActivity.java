package com.wentuyi.app;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipDescription;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;

public class KeyboardTestActivity extends Activity {
    private EditText textInput;
    private EditText wechatLikeInput;
    private RichContentEditText richInput;
    private TextView statusView;
    private ImageView imagePreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BuildConfig.DEBUG) {
            finish();
            return;
        }
        buildUi();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(247, 248, 243));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(22));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("键盘本地测试");
        title.setTextColor(Color.rgb(21, 24, 18));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(95, 102, 90));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setText("只用于本机验证输入法，不会发送消息");
        root.addView(statusView, matchWrapWithTop(8));

        textInput = new EditText(this);
        textInput.setMinLines(3);
        textInput.setMaxLines(5);
        textInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        textInput.setHint("普通输入框：验证直输、取、文、密文");
        textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(textInput, matchWrapWithTop(18));

        wechatLikeInput = new NoExtractEditText(this);
        wechatLikeInput.setMinLines(3);
        wechatLikeInput.setMaxLines(5);
        wechatLikeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        wechatLikeInput.setHint("仿微信框：getExtractedText 返回 null");
        wechatLikeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(wechatLikeInput, matchWrapWithTop(18));

        richInput = new RichContentEditText(this);
        richInput.setMinLines(3);
        richInput.setMaxLines(5);
        richInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        richInput.setHint("图片输入框：验证图、密图会插入到本页");
        richInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        richInput.setOnImageCommittedListener(this::handleCommittedImage);
        root.addView(richInput, matchWrapWithTop(18));

        imagePreview = new ImageView(this);
        imagePreview.setAdjustViewBounds(true);
        imagePreview.setMaxHeight(dp(420));
        imagePreview.setBackgroundColor(Color.WHITE);
        root.addView(imagePreview, matchWrapWithTop(12));

        Button clearButton = button("清空测试内容");
        clearButton.setOnClickListener(v -> clearTestContent());
        root.addView(clearButton, matchWrapWithTop(12));

        setContentView(scrollView);
    }

    private void handleCommittedImage(Uri uri) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                statusView.setText("图片插入失败：无法读取");
                return;
            }
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap == null) {
                statusView.setText("图片插入失败：无法解码");
                return;
            }
            imagePreview.setImageBitmap(bitmap);
            statusView.setText("已接收图片：" + bitmap.getWidth() + " x " + bitmap.getHeight());
        } catch (Exception e) {
            statusView.setText("图片插入失败：" + e.getClass().getSimpleName());
        }
    }

    private void clearTestContent() {
        textInput.setText("");
        wechatLikeInput.setText("");
        richInput.setText("");
        imagePreview.setImageDrawable(null);
        statusView.setText("测试内容已清空");
    }

    boolean commitImageForTest(Uri uri) {
        if (Build.VERSION.SDK_INT < 25) {
            return false;
        }
        EditorInfo editorInfo = new EditorInfo();
        InputConnection connection = richInput.onCreateInputConnection(editorInfo);
        if (connection == null || editorInfo.contentMimeTypes == null
                || editorInfo.contentMimeTypes.length == 0) {
            return false;
        }
        ClipDescription description = new ClipDescription(
                "文图易测试图片",
                new String[]{"image/png"}
        );
        InputContentInfo contentInfo = new InputContentInfo(uri, description, null);
        return connection.commitContent(contentInfo, 0, new Bundle());
    }

    String currentStatusForTest() {
        return statusView.getText().toString();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }

    private static class RichContentEditText extends EditText {
        private OnImageCommittedListener imageCommittedListener;

        RichContentEditText(Activity activity) {
            super(activity);
        }

        void setOnImageCommittedListener(OnImageCommittedListener listener) {
            imageCommittedListener = listener;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection baseConnection = super.onCreateInputConnection(outAttrs);
            if (Build.VERSION.SDK_INT >= 25) {
                outAttrs.contentMimeTypes = new String[]{"image/png", "image/*"};
                return new RichInputConnection(baseConnection, imageCommittedListener);
            }
            return baseConnection;
        }
    }

    private interface OnImageCommittedListener {
        void onImageCommitted(Uri uri);
    }

    /**
     * Reproduces the WeChat / QQ / many WebView-chat behaviour: the input box returns
     * null from getExtractedText, so an IME that relies on it sees no text. Verifies the
     * IME's getTextBeforeCursor/AfterCursor fallback for these apps.
     */
    private static class NoExtractEditText extends EditText {
        NoExtractEditText(Activity activity) {
            super(activity);
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            InputConnection base = super.onCreateInputConnection(outAttrs);
            if (base == null) {
                return null;
            }
            return new InputConnectionWrapper(base, false) {
                @Override
                public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
                    return null;
                }
            };
        }
    }

    @TargetApi(25)
    private static class RichInputConnection extends InputConnectionWrapper {
        private final OnImageCommittedListener imageCommittedListener;

        RichInputConnection(InputConnection target, OnImageCommittedListener listener) {
            super(target, false);
            imageCommittedListener = listener;
        }

        @Override
        public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
            if (inputContentInfo == null || imageCommittedListener == null) {
                return false;
            }
            try {
                if ((flags & INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                    inputContentInfo.requestPermission();
                }
                imageCommittedListener.onImageCommitted(inputContentInfo.getContentUri());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}

// The versus field (tool/versus): one text field for a keyboard to type into, and a record of what every key press
// did to it. It belongs to no keyboard, so each one meets the same ordinary field of another app.
//
// Started and reset by an intent, which puts a new, empty field in the old one's place (so the keyboard is told a
// field has been entered, as when the next message is begun, and not that the same one was started over):
//   am start -n com.infamousvague.versus/.Main --es tag s0042 --es mode plain|raw [--ez learn true]
// "plain" is a message field: text, sentences capitalised, autocorrect asked for, several lines.
// "raw" asks for no suggestions, which is where keyboards do no correcting: a key press types its own letter.
// Unless learn is true the field asks keyboards not to learn from it (IME_FLAG_NO_PERSONALIZED_LEARNING), so what
// one sentence was typed as cannot change how the next is read.
//
// The record is files/events.log (read with `run-as com.infamousvague.versus cat files/events.log`), begun afresh
// at every reset, one line an event, tab separated:
//   R  <ms>  <tag>  <mode>  <learn>  <keyboard in use>  <window has focus>      a reset
//   T  <ms>  <text as JSON>                                                    the field's text after a change
//   I  <ms>  <keyboard showing>  <its height, px>  <window height, px>         the keyboard's place changed
//   F  <ms>  <window has focus>                                                something came in front, or left
package com.infamousvague.versus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class Main extends Activity {
    private EditText field;
    private LinearLayout root;
    private TextView status;
    private File log;
    private String lastInsets = "";
    private int type = InputType.TYPE_CLASS_TEXT;
    private int options = 0;

    /** Takes the field away, if there is one, and puts a new empty one in its place, set up as the last reset asked. */
    private void newField() {
        if (field != null) root.removeView(field);
        EditText f = new EditText(this);
        f.setTextSize(18);
        f.setTextColor(Color.BLACK);
        f.setGravity(Gravity.TOP | Gravity.START);
        f.setPadding(24, 8, 24, 8);
        f.setBackgroundColor(Color.WHITE);
        f.setInputType(type);
        f.setImeOptions(options);
        f.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                write("T\t" + SystemClock.uptimeMillis() + "\t" + json(s.toString()));
            }
        });
        field = f;
        root.addView(f, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        log = new File(getFilesDir(), "events.log");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);
        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(24, 16, 24, 8);
        root.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        this.root = root;
        newField();
        setContentView(root);

        // The keyboard's height is read from the window itself each time the layout settles: by the time insets
        // reach a view the window has already made room for the keyboard and they say nothing of it.
        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) insets(insets);
            }
        });
        reset(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        reset(intent);
    }

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        write("F\t" + SystemClock.uptimeMillis() + "\t" + has);
        if (has) show();
    }

    /** The field emptied and set up as the intent asks, the record begun afresh, the keyboard told to start over. */
    private void reset(Intent intent) {
        String tag = intent.getStringExtra("tag");
        String mode = intent.getStringExtra("mode");
        boolean learn = intent.getBooleanExtra("learn", false);
        if (tag == null) tag = "-";
        if (mode == null) mode = "plain";
        int type = "raw".equals(mode)
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        int options = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | (learn ? 0 : EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        this.type = type;
        this.options = options;
        // A new field, not the old one emptied: a keyboard is then told a field has been entered (as when the next
        // message is begun), where restarting the same field tells it only that the app started over, and some
        // keyboards carry things across that (Ghost Keyboard stays on its ?123 page).
        newField();
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        status.setText("versus field   tag " + tag + "   mode " + mode + (learn ? "   learning allowed" : "   no learning") + "\n" + ime);
        log.delete();
        lastInsets = "";
        write("R\t" + SystemClock.uptimeMillis() + "\t" + tag + "\t" + mode + "\t" + learn + "\t" + ime + "\t" + hasWindowFocus());
        View decor = getWindow().getDecorView();
        if (decor.getRootWindowInsets() != null) insets(decor.getRootWindowInsets());
        show();
    }

    private void show() {
        field.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(field, 0);
    }

    private void insets(WindowInsets insets) {
        boolean showing = insets.isVisible(WindowInsets.Type.ime());
        int height = insets.getInsets(WindowInsets.Type.ime()).bottom;
        String now = showing + "\t" + height + "\t" + getWindow().getDecorView().getHeight();
        if (now.equals(lastInsets)) return;
        lastInsets = now;
        write("I\t" + SystemClock.uptimeMillis() + "\t" + now);
    }

    private void write(String line) {
        try (FileOutputStream out = new FileOutputStream(log, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.e("VersusField", "write", e);
        }
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20 || c > 0x7e) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}

package com.deepseekharness.app.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.deepseekharness.app.R;

/** Passwords stay in the current dialog. The caller owns and clears the returned copy. */
final class PortablePasswordFields extends LinearLayout {
  final EditText first, confirm;

  PortablePasswordFields(Context context) {
    super(context);
    setOrientation(VERTICAL);
    setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    TextView explanation = new TextView(context);
    explanation.setText(R.string.portable_password_help);
    addView(explanation);
    first = input(context, R.string.portable_password);
    confirm = input(context, R.string.portable_password_confirm);
    // In landscape, keep the form and its actions visible while a supporting IME edits the
    // password.
    for (EditText field : new EditText[] {first, confirm}) {
      field.setImeOptions(
          field.getImeOptions()
              | EditorInfo.IME_FLAG_NO_FULLSCREEN
              | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
      addView(field);
    }
  }

  static EditText input(Context context, int hint) {
    EditText field = new EditText(context);
    field.setSingleLine(true);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    field.setImeOptions(field.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    field.setSaveEnabled(false);
    field.setHint(hint);
    return field;
  }

  static char[] read(EditText field) {
    char[] value = new char[field.length()];
    field.getText().getChars(0, value.length, value, 0);
    return value;
  }

  char[] validated() {
    if (first.length() < 12 || first.length() > 1024) {
      first.setError(getResources().getString(R.string.portable_password_length));
      first.requestFocus();
      return null;
    }
    if (!android.text.TextUtils.equals(first.getText(), confirm.getText())) {
      confirm.setError(getResources().getString(R.string.portable_password_mismatch));
      confirm.requestFocus();
      return null;
    }
    return read(first);
  }

  void clear() {
    first.getText().clear();
    confirm.getText().clear();
  }
}
